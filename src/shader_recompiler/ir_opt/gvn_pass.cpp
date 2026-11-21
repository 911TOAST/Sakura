// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Global Value Numbering (GVN) — cross-block CSE for the GPU shader IR.
//
// For every eligible instruction we build a key from the opcode, type, flags
// and the *resolved* arguments (identity chains are followed so separately
// computed aliases of the same value are recognized). When a second
// instruction produces the same key, its uses are rerouted to the first
// representative, provided the representative's block dominates the second
// instruction (dominance is computed with a dataflow fixpoint over the CFG).
//
// Eligibility: the instruction must be pure (`!MayHaveSideEffects()`), not a
// pseudo-instruction, of scalar type, and not a memory load. Loads are
// excluded here on purpose — redundant load elimination where safe is the job
// of CsePass (see cse_pass.cpp). Instructions whose result can change through
// dynamic state between reads (condition flags, goto variables) are excluded
// because GVN cannot model their memory state.

#include <algorithm>
#include <array>
#include <bit>
#include <functional>
#include <map>
#include <set>
#include <utility>
#include <vector>

#include "shader_recompiler/frontend/ir/basic_block.h"
#include "shader_recompiler/frontend/ir/opcodes.h"
#include "shader_recompiler/frontend/ir/value.h"
#include "shader_recompiler/ir_opt/passes.h"

namespace Shader::Optimization {
namespace {

/// Total order over IR values: immediates compare by value, opaque values by
/// instruction pointer. Used to build comparable GVN keys.
struct ValueLess {
    static int Compare(const IR::Value& a, const IR::Value& b) {
        const IR::Inst* const ia{a.TryInstRecursive()};
        const IR::Inst* const ib{b.TryInstRecursive()};
        if (ia == nullptr && ib == nullptr) {
            // Immediate values include non-numeric IR operands such as attributes and
            // registers. Compare those payloads too; treating equal types as equal values
            // can merge reads from different attributes.
            const IR::Type ta{a.Type()};
            const IR::Type tb{b.Type()};
            if (ta != tb) {
                return static_cast<int>(ta) < static_cast<int>(tb) ? -1 : 1;
            }
            switch (ta) {
            case IR::Type::Void:
                return 0;
            case IR::Type::Reg: {
                const auto x{static_cast<u32>(a.Reg())}, y{static_cast<u32>(b.Reg())};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::Pred: {
                const auto x{static_cast<u32>(a.Pred())}, y{static_cast<u32>(b.Pred())};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::Attribute: {
                const auto x{static_cast<u32>(a.Attribute())};
                const auto y{static_cast<u32>(b.Attribute())};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::Patch: {
                const auto x{static_cast<u32>(a.Patch())}, y{static_cast<u32>(b.Patch())};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::U1: {
                const bool x{a.U1()}, y{b.U1()};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::U8: {
                const u8 x{a.U8()}, y{b.U8()};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::U16: {
                const u16 x{a.U16()}, y{b.U16()};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::U32: {
                const u32 x{a.U32()}, y{b.U32()};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::U64: {
                const u64 x{a.U64()}, y{b.U64()};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::F32: {
                const u32 x{std::bit_cast<u32>(a.F32())};
                const u32 y{std::bit_cast<u32>(b.F32())};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::F64: {
                const u64 x{std::bit_cast<u64>(a.F64())};
                const u64 y{std::bit_cast<u64>(b.F64())};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            default:
                // These types are represented by instructions rather than immediate payloads.
                // Keep distinct non-immediate values ordered by type if they ever reach this
                // fallback; they are not eligible GVN arguments in normal shader IR.
                return 0;
            }
        }
        if (ia == nullptr) {
            return -1;
        }
        if (ib == nullptr) {
            return 1;
        }
        return std::less<const IR::Inst*>{}(ia, ib) ? -1 : (std::less<const IR::Inst*>{}(ib, ia) ? 1 : 0);
    }

    bool operator()(const IR::Value& a, const IR::Value& b) const {
        return Compare(a, b) < 0;
    }
};

struct GvnKey {
    IR::Opcode op{};
    IR::Type type{};
    u32 flags{};
    std::array<IR::Value, 5> args{};

    bool operator<(const GvnKey& other) const {
        if (static_cast<int>(op) != static_cast<int>(other.op)) {
            return static_cast<int>(op) < static_cast<int>(other.op);
        }
        if (static_cast<int>(type) != static_cast<int>(other.type)) {
            return static_cast<int>(type) < static_cast<int>(other.type);
        }
        if (flags != other.flags) {
            return flags < other.flags;
        }
        for (size_t i = 0; i < args.size(); ++i) {
            const int c{ValueLess::Compare(args[i], other.args[i])};
            if (c != 0) {
                return c < 0;
            }
        }
        return false;
    }
};

bool IsGvnEligible(const IR::Inst& inst) {
    const IR::Opcode op{inst.GetOpcode()};
    switch (op) {
    case IR::Opcode::Identity:
    case IR::Opcode::Void:
    case IR::Opcode::Phi:
    case IR::Opcode::UndefU1:
    case IR::Opcode::UndefU8:
    case IR::Opcode::UndefU16:
    case IR::Opcode::UndefU32:
    case IR::Opcode::UndefU64:
        // Aliases, control-flow artifacts and "don't care" values.
        return false;
    default:
        break;
    }
    if (inst.MayHaveSideEffects()) {
        return false;
    }
    if (inst.IsPseudoInstruction() || inst.HasAssociatedPseudoOperation()) {
        return false;
    }
    switch (inst.Type()) {
    case IR::Type::U1:
    case IR::Type::U8:
    case IR::Type::U16:
    case IR::Type::U32:
    case IR::Type::U64:
    case IR::Type::F16:
    case IR::Type::F32:
    case IR::Type::F64:
        break;
    default:
        // Only scalar values are worth number: vectors are cheap for drivers.
        return false;
    }
    switch (op) {
    case IR::Opcode::GetZFlag:
    case IR::Opcode::GetSFlag:
    case IR::Opcode::GetCFlag:
    case IR::Opcode::GetOFlag:
    case IR::Opcode::GetGotoVariable:
    case IR::Opcode::GetIndirectBranchVariable:
        // Dynamic reads of mutable state; two reads may observe different values.
        return false;
    default:
        break;
    }
    // Memory reads are handled by other passes:
    switch (op) {
    case IR::Opcode::LoadGlobalU8:
    case IR::Opcode::LoadGlobalS8:
    case IR::Opcode::LoadGlobalU16:
    case IR::Opcode::LoadGlobalS16:
    case IR::Opcode::LoadGlobal32:
    case IR::Opcode::LoadGlobal64:
    case IR::Opcode::LoadGlobal128:
    case IR::Opcode::LoadLocal:
    case IR::Opcode::LoadStorageU8:
    case IR::Opcode::LoadStorageS8:
    case IR::Opcode::LoadStorageU16:
    case IR::Opcode::LoadStorageS16:
    case IR::Opcode::LoadStorage32:
    case IR::Opcode::LoadStorage64:
    case IR::Opcode::LoadStorage128:
    case IR::Opcode::LoadSharedU8:
    case IR::Opcode::LoadSharedS8:
    case IR::Opcode::LoadSharedU16:
    case IR::Opcode::LoadSharedS16:
    case IR::Opcode::LoadSharedU32:
    case IR::Opcode::LoadSharedU64:
    case IR::Opcode::LoadSharedU128:
    case IR::Opcode::ImageRead:
    case IR::Opcode::BindlessImageRead:
    case IR::Opcode::BoundImageRead:
    case IR::Opcode::ImageFetch:
    case IR::Opcode::BindlessImageFetch:
    case IR::Opcode::BoundImageFetch:
    case IR::Opcode::ImageGradient:
    case IR::Opcode::BindlessImageGradient:
    case IR::Opcode::BoundImageGradient:
    case IR::Opcode::ImageQueryDimensions:
    case IR::Opcode::BindlessImageQueryDimensions:
    case IR::Opcode::BoundImageQueryDimensions:
    case IR::Opcode::ImageQueryLod:
    case IR::Opcode::BindlessImageQueryLod:
    case IR::Opcode::BoundImageQueryLod:
    case IR::Opcode::ImageSampleImplicitLod:
    case IR::Opcode::BindlessImageSampleImplicitLod:
    case IR::Opcode::BoundImageSampleImplicitLod:
        return false;
    default:
        return true;
    }
}

/// Computes reachable blocks in reverse postorder plus dominator sets,
/// using a small iterative DFS and a standard dataflow fixpoint.
class DominanceAnalyzer {
public:
    explicit DominanceAnalyzer(const IR::Program& program) {
        if (program.blocks.empty()) {
            return;
        }
        entry = program.blocks.front();
        for (IR::Block* const block : program.blocks) {
            for (IR::Block* const succ : block->ImmSuccessors()) {
                succs[block].push_back(succ);
                preds[succ].push_back(block);
            }
        }
        // Iterative DFS postorder from the entry block.
        std::vector<std::pair<IR::Block*, size_t>> stack{{entry, 0}};
        std::set<IR::Block*> visited{entry};
        while (!stack.empty()) {
            std::pair<IR::Block*, size_t>& frame{stack.back()};
            if (frame.second < succs[frame.first].size()) {
                IR::Block* const succ{succs[frame.first][frame.second++]};
                if (visited.insert(succ).second) {
                    stack.emplace_back(succ, 0);
                }
            } else {
                rpo.push_back(frame.first);
                stack.pop_back();
            }
        }
        std::ranges::reverse(rpo);
        ComputeDominatorSets();
    }

    [[nodiscard]] const std::vector<IR::Block*>& ReversePostOrder() const {
        return rpo;
    }

    [[nodiscard]] bool Dominates(IR::Block* a, IR::Block* b) const {
        const auto it{dom.find(b)};
        return it != dom.end() && it->second.contains(a);
    }

private:
    void ComputeDominatorSets() {
        dom[entry] = {entry};
        bool changed{true};
        while (changed) {
            changed = false;
            for (IR::Block* const block : rpo) {
                if (block == entry) {
                    continue;
                }
                std::set<IR::Block*> intersection;
                bool first{true};
                for (IR::Block* const pred : preds[block]) {
                    const auto pred_it{dom.find(pred)};
                    if (pred_it == dom.end()) {
                        continue;
                    }
                    if (first) {
                        intersection = pred_it->second;
                        first = false;
                    } else {
                        std::erase_if(intersection, [&](IR::Block* value) {
                            return !pred_it->second.contains(value);
                        });
                    }
                }
                if (first) {
                    // No reachable predecessor: unreachable block, skip.
                    continue;
                }
                intersection.insert(block);
                if (dom[block] != intersection) {
                    dom[block] = intersection;
                    changed = true;
                }
            }
        }
    }

    IR::Block* entry{};
    std::map<IR::Block*, IR::BlockList> succs;
    std::map<IR::Block*, IR::BlockList> preds;
    std::map<IR::Block*, std::set<IR::Block*>> dom;
    std::vector<IR::Block*> rpo;
};

struct Representative {
    IR::Block* block;
    IR::Inst* inst;
};

} // Anonymous namespace

void GvnPass(IR::Program& program) {
    if (program.blocks.empty()) {
        return;
    }
    const DominanceAnalyzer dom{program};
    std::map<GvnKey, Representative> seen;
    for (IR::Block* const block : dom.ReversePostOrder()) {
        for (IR::Inst& inst : block->Instructions()) {
            if (!IsGvnEligible(inst)) {
                continue;
            }
            GvnKey key{inst.GetOpcode(), inst.Type(), inst.Flags<u32>(), {}};
            const size_t num_args{inst.NumArgs()};
            for (size_t i = 0; i < num_args; ++i) {
                key.args[i] = inst.Arg(i);
            }
            if (const auto it{seen.find(key)}; it != seen.end()) {
                const Representative& representative{it->second};
                if (representative.inst != &inst && dom.Dominates(representative.block, block)) {
                    inst.ReplaceUsesWith(IR::Value{representative.inst});
                }
            } else {
                seen.emplace(std::move(key), Representative{block, &inst});
            }
        }
    }
}

} // namespace Shader::Optimization

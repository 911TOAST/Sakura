// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Redundant load elimination ("CSE for memory"). For each block we keep a map
// of recently seen loads keyed by (opcode, resolved address) and replace a
// load with an earlier identical load when no memory write, atomic or
// barrier between them can change the value. Loads of global, storage, local
// and shared memory are handled; image reads are excluded because they are
// observed through the storage-image memory model.

#include <map>
#include <functional>
#include <utility>

#include "shader_recompiler/frontend/ir/basic_block.h"
#include "shader_recompiler/frontend/ir/value.h"
#include "shader_recompiler/ir_opt/passes.h"

namespace Shader::Optimization {
namespace {

struct ValueLess {
    static int Compare(const IR::Value& a, const IR::Value& b) {
        const IR::Inst* const ia{a.TryInstRecursive()};
        const IR::Inst* const ib{b.TryInstRecursive()};
        if (ia == nullptr && ib == nullptr) {
            const IR::Type ta{a.Type()};
            const IR::Type tb{b.Type()};
            if (ta != tb) {
                return static_cast<int>(ta) < static_cast<int>(tb) ? -1 : 1;
            }
            switch (ta) {
            case IR::Type::Void:
                return 0;
            case IR::Type::Reg: {
                const u64 x{static_cast<u64>(a.Reg())};
                const u64 y{static_cast<u64>(b.Reg())};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::Pred: {
                const u64 x{static_cast<u64>(a.Pred())};
                const u64 y{static_cast<u64>(b.Pred())};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::Attribute: {
                const u64 x{static_cast<u64>(a.Attribute())};
                const u64 y{static_cast<u64>(b.Attribute())};
                return x == y ? 0 : (x < y ? -1 : 1);
            }
            case IR::Type::Patch: {
                const u64 x{static_cast<u64>(a.Patch())};
                const u64 y{static_cast<u64>(b.Patch())};
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
            default:
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

struct LoadKey {
    IR::Opcode opcode;
    IR::Value address;
    IR::Value descriptor;
};

struct LoadKeyLess {
    bool operator()(const LoadKey& lhs, const LoadKey& rhs) const {
        if (lhs.opcode != rhs.opcode) {
            return static_cast<int>(lhs.opcode) < static_cast<int>(rhs.opcode);
        }
        const int address_order{ValueLess::Compare(lhs.address, rhs.address)};
        return address_order != 0 ? address_order < 0
                                  : ValueLess::Compare(lhs.descriptor, rhs.descriptor) < 0;
    }
};

bool IsRedundantLoad(const IR::Inst& inst) {
    switch (inst.GetOpcode()) {
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
        return true;
    default:
        return false;
    }
}

void RedundantLoadElimination(IR::Block& block) {
    std::map<LoadKey, IR::Inst*, LoadKeyLess> loads;
    for (auto it = block.begin(); it != block.end(); ++it) {
        IR::Inst& inst = *it;
        if (inst.MayHaveSideEffects()) {
            // Writes, atomics and barriers invalidate prior loads of the same
            // address.
            loads.clear();
            continue;
        }
        if (!IsRedundantLoad(inst)) {
            continue;
        }
        // Canonicalize identity chains before constructing the ordered key. In particular, an
        // identity that resolves to an immediate must compare by the immediate value, not as an
        // opaque instruction with no recursively referenced instruction.
        const IR::Value address{inst.Arg(0).Resolve()};
        const IR::Value descriptor{inst.NumArgs() > 1 ? inst.Arg(1).Resolve() : IR::Value{}};
        const LoadKey key{inst.GetOpcode(), address, descriptor};
        auto found{loads.find(key)};
        if (found == loads.end()) {
            found = loads.emplace(key, &inst).first;
        } else if (found->second != &inst) {
            inst.ReplaceUsesWith(IR::Value{found->second});
        }
    }
}

} // Anonymous namespace

void CsePass(IR::Program& program) {
    for (IR::Block* const block : program.blocks) {
        RedundantLoadElimination(*block);
    }
}

} // namespace Shader::Optimization

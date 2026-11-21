// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Copy propagation for the GPU shader IR:
//   * collapses phi nodes that merge the same value from every predecessor,
//     exposing additional copy-propagation opportunities in users.
//   * removes `select(c, a, a)` where both arms of a select are the same value.
// (Identity-chain resolution through `Value::Resolve()` is the existing
// mechanism; this pass additionally canonicalizes phis and selects.)

#include "shader_recompiler/frontend/ir/basic_block.h"
#include "shader_recompiler/frontend/ir/opcodes.h"
#include "shader_recompiler/frontend/ir/value.h"
#include "shader_recompiler/ir_opt/passes.h"

namespace Shader::Optimization {
namespace {

bool IsSelectOp(const IR::Opcode op) {
    switch (op) {
    case IR::Opcode::SelectU1:
    case IR::Opcode::SelectU8:
    case IR::Opcode::SelectU16:
    case IR::Opcode::SelectU32:
    case IR::Opcode::SelectU64:
    case IR::Opcode::SelectF16:
    case IR::Opcode::SelectF32:
    case IR::Opcode::SelectF64:
        return true;
    default:
        return false;
    }
}

void CollapseTrivialPhis(IR::Block& block) {
    for (IR::Inst& inst : block.Instructions()) {
        if (inst.GetOpcode() != IR::Opcode::Phi) {
            // Phis are always leading the block.
            break;
        }
        IR::Value same;
        bool all_same{true};
        for (size_t i = 0; i < inst.NumArgs(); ++i) {
            const IR::Value resolved{inst.Arg(i).Resolve()};
            if (same.IsEmpty()) {
                same = resolved;
            } else if (same != resolved) {
                all_same = false;
                break;
            }
        }
        if (all_same && !same.IsEmpty()) {
            inst.ReplaceUsesWith(same);
        }
    }
}

void SimplifySelectArms(IR::Block& block) {
    for (IR::Inst& inst : block.Instructions()) {
        if (IsSelectOp(inst.GetOpcode()) && inst.Arg(1).Resolve() == inst.Arg(2).Resolve()) {
            inst.ReplaceUsesWith(inst.Arg(1).Resolve());
        }
    }
}

} // Anonymous namespace

void CopyPropagationPass(IR::Program& program) {
    for (IR::Block* const block : program.blocks) {
        CollapseTrivialPhis(*block);
        SimplifySelectArms(*block);
    }
}

} // namespace Shader::Optimization

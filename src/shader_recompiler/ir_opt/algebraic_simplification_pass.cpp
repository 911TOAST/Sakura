// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "shader_recompiler/frontend/ir/basic_block.h"
#include "shader_recompiler/frontend/ir/opcodes.h"
#include "shader_recompiler/frontend/ir/value.h"
#include "shader_recompiler/ir_opt/passes.h"

namespace Shader::Optimization {
namespace {

bool IsU32(const IR::Value& value, const u32 immediate) {
    return value.IsImmediate() && value.U32() == immediate;
}

bool IsTrue(const IR::Value& value) {
    return value.IsImmediate() && value.U1();
}
bool IsFalse(const IR::Value& value) {
    return value.IsImmediate() && !value.U1();
}

bool IsSelect(IR::Opcode op) {
    switch (op) {
    case IR::Opcode::SelectU1: case IR::Opcode::SelectU8: case IR::Opcode::SelectU16:
    case IR::Opcode::SelectU32: case IR::Opcode::SelectU64: case IR::Opcode::SelectF16:
    case IR::Opcode::SelectF32: case IR::Opcode::SelectF64: return true;
    default: return false;
    }
}

void Simplify(IR::Inst& inst) {
    const IR::Opcode op{inst.GetOpcode()};
    const IR::Value a{inst.NumArgs() > 0 ? inst.Arg(0).Resolve() : IR::Value{}};
    const IR::Value b{inst.NumArgs() > 1 ? inst.Arg(1).Resolve() : IR::Value{}};
    const auto replace = [&](IR::Value v) { inst.ReplaceUsesWith(v); };
    const bool same{inst.NumArgs() > 1 && a == b};
    switch (op) {
    case IR::Opcode::IAdd32:
        if (IsU32(a, 0)) replace(b); else if (IsU32(b, 0)) replace(a); break;
    case IR::Opcode::ISub32:
        if (same) replace(IR::Value{u32{0}}); else if (IsU32(b, 0)) replace(a); break;
    case IR::Opcode::IMul32:
        if (IsU32(a, 0) || IsU32(b, 0)) replace(IR::Value{u32{0}});
        else if (IsU32(a, 1)) replace(b); else if (IsU32(b, 1)) replace(a); break;
    case IR::Opcode::BitwiseAnd32:
        if (IsU32(a, 0) || IsU32(b, 0)) replace(IR::Value{u32{0}});
        else if (IsU32(a, ~u32{0})) replace(b); else if (IsU32(b, ~u32{0})) replace(a);
        else if (same) replace(a); break;
    case IR::Opcode::BitwiseOr32:
        if (IsU32(a, ~u32{0}) || IsU32(b, ~u32{0})) replace(IR::Value{~u32{0}});
        else if (IsU32(a, 0)) replace(b); else if (IsU32(b, 0)) replace(a);
        else if (same) replace(a); break;
    case IR::Opcode::BitwiseXor32:
        if (IsU32(a, 0)) replace(b); else if (IsU32(b, 0)) replace(a);
        else if (same) replace(IR::Value{u32{0}}); break;
    case IR::Opcode::INeg32: {
        if (IsU32(a, 0)) replace(IR::Value{u32{0}});
        else if (IR::Inst* prev{a.TryInstRecursive()}; prev && prev->GetOpcode() == op)
            replace(prev->Arg(0));
        break;
    }
    case IR::Opcode::IAbs32:
        if (IR::Inst* prev{a.TryInstRecursive()}; prev && prev->GetOpcode() == op) replace(a);
        break;
    case IR::Opcode::FPNeg32:
        if (IR::Inst* prev{a.TryInstRecursive()}; prev && prev->GetOpcode() == op)
            replace(prev->Arg(0));
        break;
    case IR::Opcode::IEqual: case IR::Opcode::INotEqual: case IR::Opcode::ULessThan:
    case IR::Opcode::UGreaterThan: case IR::Opcode::SGreaterThan:
    case IR::Opcode::SGreaterThanEqual: case IR::Opcode::SLessThan:
    case IR::Opcode::SLessThanEqual: case IR::Opcode::ULessThanEqual:
        if (same) {
            const bool result{op == IR::Opcode::IEqual || op == IR::Opcode::SGreaterThanEqual ||
                              op == IR::Opcode::SLessThanEqual || op == IR::Opcode::ULessThanEqual};
            replace(IR::Value{result});
        }
        break;
    case IR::Opcode::ShiftLeftLogical32: case IR::Opcode::ShiftRightLogical32:
    case IR::Opcode::ShiftRightArithmetic32:
        if (IsU32(b, 0)) replace(a); break;
    case IR::Opcode::SMin32: case IR::Opcode::UMin32: case IR::Opcode::SMax32:
    case IR::Opcode::UMax32:
        if (same) replace(a); break;
    case IR::Opcode::LogicalAnd:
        if (IsTrue(a)) replace(b); else if (IsTrue(b)) replace(a);
        else if (IsFalse(a) || IsFalse(b)) replace(IR::Value{false}); break;
    case IR::Opcode::LogicalOr:
        if (IsFalse(a)) replace(b); else if (IsFalse(b)) replace(a);
        else if (IsTrue(a) || IsTrue(b)) replace(IR::Value{true}); break;
    case IR::Opcode::LogicalXor:
        if (IsFalse(a)) replace(b); else if (IsFalse(b)) replace(a); break;
    case IR::Opcode::LogicalNot:
        if (IR::Inst* prev{a.TryInstRecursive()}; prev && prev->GetOpcode() == op)
            replace(prev->Arg(0));
        break;
    default:
        if (IsSelect(op) && inst.Arg(1).Resolve() == inst.Arg(2).Resolve())
            replace(inst.Arg(1).Resolve());
        break;
    }
}

} // Anonymous namespace

void AlgebraicSimplificationPass(IR::Program& program) {
    for (IR::Block* const block : program.blocks) {
        for (IR::Inst& inst : block->Instructions()) Simplify(inst);
    }
}

} // namespace Shader::Optimization

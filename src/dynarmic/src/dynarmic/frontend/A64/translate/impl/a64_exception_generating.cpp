/* This file is part of the dynarmic project.
 * Copyright (c) 2018 MerryMage
 * SPDX-License-Identifier: 0BSD
 */

#include "dynarmic/frontend/A64/translate/impl/impl.h"

namespace Dynarmic::A64 {

bool TranslatorVisitor::BRK(Imm<16> /*imm16*/) {
    return RaiseException(Exception::Breakpoint);
}

bool TranslatorVisitor::SVC(Imm<16> imm16) {
    // svcGetSystemTick is a pure read of the emulated counter. If the frontend
    // has been told that GetCNTPCT() matches the guest's system tick, service
    // it inline and keep executing instead of exiting the JIT. Tick polling is
    // common in engine frame loops, so this avoids a full supervisor-call round
    // trip for every poll.
    if (options.fast_svc_get_system_tick && imm16.ZeroExtend() == 0x1E) {
        ir.PushRSB(ir.current_location->AdvancePC(4));
        ir.SetX(Reg::R0, ir.GetCNTPCT());
        ir.SetPC(ir.Imm64(ir.current_location->PC() + 4));
        ir.SetTerm(IR::Term::CheckHalt{IR::Term::PopRSBHint{}});
        return false;
    }

    ir.PushRSB(ir.current_location->AdvancePC(4));
    ir.SetPC(ir.Imm64(ir.current_location->PC() + 4));
    ir.CallSupervisor(imm16.ZeroExtend());
    ir.SetTerm(IR::Term::CheckHalt{IR::Term::PopRSBHint{}});
    return false;
}

}  // namespace Dynarmic::A64

// SPDX-FileCopyrightText: Copyright 2021 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include "shader_recompiler/frontend/ir/basic_block.h"
#include "shader_recompiler/frontend/ir/value.h"
#include "shader_recompiler/ir_opt/passes.h"

namespace Shader::Optimization {

void DeadCodeEliminationPass(IR::Program& program) {
    bool changed{};
    do {
        changed = false;
        for (IR::Block* const block : program.post_order_blocks) {
            auto it{block->end()};
            while (it != block->begin()) {
                --it;
                if (!it->HasUses() && !it->MayHaveSideEffects()) {
                    it->Invalidate();
                    it = block->Instructions().erase(it);
                    changed = true;
                }
            }
        }
    } while (changed);
}

} // namespace Shader::Optimization

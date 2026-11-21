// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "shader_recompiler/frontend/ir/abstract_syntax_list.h"
#include "shader_recompiler/frontend/ir/basic_block.h"
#include "shader_recompiler/frontend/ir/opcodes.h"
#include "shader_recompiler/frontend/ir/program.h"
#include "shader_recompiler/ir_opt/passes.h"

namespace Shader::Optimization {

void ControlFlowSimplificationPass(IR::Program& program) {
    auto& syntax{program.syntax_list};
    for (size_t i = 1; i < syntax.size(); ++i) {
        IR::AbstractSyntaxNode& node{syntax[i]};
        if (node.type != IR::AbstractSyntaxNode::Type::If || !node.data.if_node.cond.IsImmediate())
            continue;
        IR::AbstractSyntaxNode& header_node{syntax[i - 1]};
        if (header_node.type != IR::AbstractSyntaxNode::Type::Block) continue;
        IR::Block* const header{header_node.data.block};
        IR::Block* const body{node.data.if_node.body};
        IR::Block* const merge{node.data.if_node.merge};
        if (!header || !body || !merge || body == merge) continue;
        bool merge_has_phi{};
        for (const IR::Inst& inst : merge->Instructions()) {
            if (inst.GetOpcode() == IR::Opcode::Phi) { merge_has_phi = true; break; }
        }
        if (merge_has_phi) continue;
        if (node.data.if_node.cond.U1()) {
            header->ReplaceSuccessor(merge, body);
        } else {
            header->ReplaceSuccessor(body, merge);
            node.data.if_node.body = merge;
        }
    }
}

} // namespace Shader::Optimization

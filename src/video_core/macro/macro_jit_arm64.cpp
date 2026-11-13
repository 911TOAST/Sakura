#include "video_core/macro/macro_jit_arm64.h"

#ifdef ARCHITECTURE_arm64

#include <array>
#include <cstring>
#include <utility>
#include "common/logging.h"
#if defined(_WIN32)
#define NOMINMAX
#include <windows.h>
#else
#include <sys/mman.h>
#include <unistd.h>
#endif
#include <oaknut/oaknut.hpp>

#include "video_core/engines/maxwell_3d.h"
#include "video_core/macro.h"

using namespace oaknut;
using namespace oaknut::util;

namespace Tegra {

static std::atomic<uint64_t> mme_jit_hits{0};
static std::atomic<uint64_t> mme_jit_miss{0};

uint64_t MmeJitHits() noexcept {
    return mme_jit_hits.load(std::memory_order_relaxed);
}

uint64_t MmeJitMisses() noexcept {
    return mme_jit_miss.load(std::memory_order_relaxed);
}

struct CodeMem {
    void* rx{};
    size_t sz{};

    CodeMem() = default;
    CodeMem(const CodeMem&) = delete;
    CodeMem& operator=(const CodeMem&) = delete;
    CodeMem(CodeMem&& mem) noexcept
        : rx{std::exchange(mem.rx, nullptr)}, sz{std::exchange(mem.sz, 0)} {}

    bool Alloc(size_t n) {
#if defined(_WIN32)
        sz = (n + 4095) & ~size_t{4095};
        rx = VirtualAlloc(nullptr, sz, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
#else
        const long pg = sysconf(_SC_PAGESIZE);
        if (pg <= 0) {
            return false;
        }
        sz = (n + static_cast<size_t>(pg) - 1) & ~(static_cast<size_t>(pg) - 1);
        rx = mmap(nullptr, sz, PROT_READ | PROT_WRITE, MAP_ANON | MAP_PRIVATE, -1, 0);
        if (rx == MAP_FAILED) {
            rx = nullptr;
        }
#endif
        return rx != nullptr;
    }

    bool ProtectAndFlush(const void* src, size_t n) {
        std::memcpy(rx, src, n);
#if defined(_WIN32)
        DWORD old;
        return VirtualProtect(rx, sz, PAGE_EXECUTE_READ, &old) &&
               FlushInstructionCache(GetCurrentProcess(), rx, sz);
#else
        __builtin___clear_cache(static_cast<char*>(rx), static_cast<char*>(rx) + n);
        return mprotect(rx, sz, PROT_READ | PROT_EXEC) == 0;
#endif
    }

    ~CodeMem() {
        if (!rx) {
            return;
        }
#if defined(_WIN32)
        VirtualFree(rx, 0, MEM_RELEASE);
#else
        munmap(rx, sz);
#endif
    }
};

struct JITState {
    Engines::Maxwell3D* m3d{};
    Core::System* sys{};
    std::array<u32, 8> regs{};
};

using JITFn = bool (*)(JITState* ctx, const u32* par, const u32* par_end);

extern "C" void MmeSendHelper(Core::System* sys, Engines::Maxwell3D* m3d, u32 mth, u32 val) {
    m3d->CallMethod(*sys, mth & 0xfff, val, true);
}

static void EmitFetchParameter(VectorCodeGenerator& cg, WReg rd, Label& err) {
    cg.CMP(X20, X21);
    cg.B(HS, err);
    cg.LDR(rd, X20, POST_INDEXED, 4);
}

static void EmitGetRegister(VectorCodeGenerator& cg, u32 idx, WReg rd) {
    if (idx == 0) {
        cg.MOV(rd, WZR);
    } else {
        cg.LDR(rd, X19, static_cast<int>(offsetof(JITState, regs) + idx * 4));
    }
}

static void EmitSetRegister(VectorCodeGenerator& cg, u32 idx, WReg rs) {
    if (idx != 0) {
        cg.STR(rs, X19, static_cast<int>(offsetof(JITState, regs) + idx * 4));
    }
}

static void EmitSend(VectorCodeGenerator& cg, WReg val) {
    cg.MOV(W3, val);
    cg.LDR(X0, X19, static_cast<int>(offsetof(JITState, sys)));
    cg.LDR(X1, X19, static_cast<int>(offsetof(JITState, m3d)));
    cg.MOV(W2, W22);
    uintptr_t fn_addr = reinterpret_cast<uintptr_t>(&MmeSendHelper);
    cg.MOVZ(X8, static_cast<u16>(fn_addr & 0xffff));
    if ((fn_addr >> 16) & 0xffff) {
        cg.MOVK(X8, MovImm16{static_cast<u16>((fn_addr >> 16) & 0xffff), MovImm16Shift::SHL_16});
    }
    if ((fn_addr >> 32) & 0xffff) {
        cg.MOVK(X8, MovImm16{static_cast<u16>((fn_addr >> 32) & 0xffff), MovImm16Shift::SHL_32});
    }
    if ((fn_addr >> 48) & 0xffff) {
        cg.MOVK(X8, MovImm16{static_cast<u16>((fn_addr >> 48) & 0xffff), MovImm16Shift::SHL_48});
    }
    cg.BLR(X8);

    Label skip_inc;
    cg.TST(W22, 0x3f000);
    cg.B(EQ, skip_inc);
    cg.UBFX(W0, W22, 12, 6);
    cg.ADD(W1, W22, W0);
    cg.BFXIL(W22, W1, 0, 12);
    cg.l(skip_inc);
}

static void EmitProcessResult(VectorCodeGenerator& cg, Macro::ResultOperation op, u32 rd, Label& err) {
    switch (op) {
    case Macro::ResultOperation::IgnoreAndFetch:
        EmitFetchParameter(cg, W0, err);
        EmitSetRegister(cg, rd, W0);
        break;
    case Macro::ResultOperation::Move:
        EmitSetRegister(cg, rd, W23);
        break;
    case Macro::ResultOperation::MoveAndSetMethod:
        EmitSetRegister(cg, rd, W23);
        cg.MOV(W22, W23);
        break;
    case Macro::ResultOperation::FetchAndSend:
        EmitFetchParameter(cg, W0, err);
        EmitSetRegister(cg, rd, W0);
        EmitSend(cg, W23);
        break;
    case Macro::ResultOperation::MoveAndSend:
        EmitSetRegister(cg, rd, W23);
        EmitSend(cg, W23);
        break;
    case Macro::ResultOperation::FetchAndSetMethod:
        EmitFetchParameter(cg, W0, err);
        EmitSetRegister(cg, rd, W0);
        cg.MOV(W22, W23);
        break;
    case Macro::ResultOperation::MoveAndSetMethodFetchAndSend:
        EmitSetRegister(cg, rd, W23);
        cg.MOV(W22, W23);
        EmitFetchParameter(cg, W0, err);
        EmitSend(cg, W0);
        break;
    case Macro::ResultOperation::MoveAndSetMethodSend:
        EmitSetRegister(cg, rd, W23);
        cg.MOV(W22, W23);
        cg.UBFX(W0, W23, 12, 6);
        EmitSend(cg, W0);
        break;
    default:
        break;
    }
}

static bool EmitALU(VectorCodeGenerator& cg, Macro::Opcode op, Label& err) {
    EmitGetRegister(cg, op.src_a, W0);
    EmitGetRegister(cg, op.src_b, W1);

    switch (op.alu_operation) {
    case Macro::ALUOperation::Add:
        cg.ADDS(W23, W0, W1);
        cg.CSET(W24, CS);
        break;
    case Macro::ALUOperation::AddWithCarry:
        cg.CMP(W24, 1);
        cg.ADCS(W23, W0, W1);
        cg.CSET(W24, CS);
        break;
    case Macro::ALUOperation::Subtract:
        cg.SUBS(W23, W0, W1);
        cg.CSET(W24, CS);
        break;
    case Macro::ALUOperation::SubtractWithBorrow:
        cg.CMP(W24, 1);
        cg.SBCS(W23, W0, W1);
        cg.CSET(W24, CS);
        break;
    case Macro::ALUOperation::Xor:
        cg.EOR(W23, W0, W1);
        break;
    case Macro::ALUOperation::Or:
        cg.ORR(W23, W0, W1);
        break;
    case Macro::ALUOperation::And:
        cg.AND(W23, W0, W1);
        break;
    case Macro::ALUOperation::AndNot:
        cg.BIC(W23, W0, W1);
        break;
    case Macro::ALUOperation::Nand:
        cg.AND(W23, W0, W1);
        cg.MVN(W23, W23);
        break;
    default:
        return false;
    }
    EmitProcessResult(cg, op.result_operation, op.dst, err);
    return true;
}

static void EmitAddImmediate(VectorCodeGenerator& cg, Macro::Opcode op, Label& err) {
    if (op.result_operation == Macro::ResultOperation::Move && op.dst == 0) {
        return;
    }
    if (op.src_a == 0) {
        if (op.immediate == 0) {
            cg.MOV(W23, WZR);
        } else {
            cg.MOV(W23, static_cast<u32>(op.immediate));
        }
    } else {
        EmitGetRegister(cg, op.src_a, W23);
        if (op.immediate != 0) {
            cg.MOV(W0, static_cast<u32>(op.immediate));
            cg.ADD(W23, W23, W0);
        }
    }
    EmitProcessResult(cg, op.result_operation, op.dst, err);
}

static void EmitExtractInsert(VectorCodeGenerator& cg, Macro::Opcode op, Label& err) {
    EmitGetRegister(cg, op.src_a, W23);
    if (op.bf_size > 0) {
        EmitGetRegister(cg, op.src_b, W0);
        const u32 mask = (1u << op.bf_size) - 1;
        cg.LSR(W1, W0, Imm<5>{op.bf_src_bit.Value()});
        cg.AND(W1, W1, mask);
        cg.LSL(W1, W1, LslShift<32>{op.bf_dst_bit.Value()});
        cg.MOV(W2, ~(mask << op.bf_dst_bit));
        cg.AND(W23, W23, W2);
        cg.ORR(W23, W23, W1);
    }
    EmitProcessResult(cg, op.result_operation, op.dst, err);
}

static void EmitExtractShiftLeftImmediate(VectorCodeGenerator& cg, Macro::Opcode op, Label& err) {
    if (op.bf_size == 0) {
        cg.MOV(W23, WZR);
    } else {
        EmitGetRegister(cg, op.src_a, W0);
        EmitGetRegister(cg, op.src_b, W1);
        cg.LSRV(W1, W1, W0);
        cg.AND(W1, W1, (1u << op.bf_size) - 1);
        cg.LSL(W23, W1, LslShift<32>{op.bf_dst_bit.Value()});
    }
    EmitProcessResult(cg, op.result_operation, op.dst, err);
}

static void EmitExtractShiftLeftRegister(VectorCodeGenerator& cg, Macro::Opcode op, Label& err) {
    if (op.bf_size == 0) {
        cg.MOV(W23, WZR);
    } else {
        EmitGetRegister(cg, op.src_a, W0);
        EmitGetRegister(cg, op.src_b, W1);
        cg.LSR(W1, W1, Imm<5>{op.bf_src_bit.Value()});
        cg.AND(W1, W1, (1u << op.bf_size) - 1);
        cg.LSLV(W23, W1, W0);
    }
    EmitProcessResult(cg, op.result_operation, op.dst, err);
}

static void EmitRead(VectorCodeGenerator& cg, Macro::Opcode op, Label& err) {
    if (op.src_a == 0) {
        if (op.immediate == 0) {
            cg.MOV(W0, WZR);
        } else {
            cg.MOV(W0, static_cast<u32>(op.immediate));
        }
    } else {
        EmitGetRegister(cg, op.src_a, W0);
        if (op.immediate != 0) {
            cg.MOV(W1, static_cast<u32>(op.immediate));
            cg.ADD(W0, W0, W1);
        }
    }
    cg.MOV(W1, Engines::Maxwell3D::Regs::NUM_REGS);
    cg.CMP(W0, W1);
    cg.B(HS, err);
    cg.LDR(X1, X19, static_cast<int>(offsetof(JITState, m3d)));
    size_t off = offsetof(Engines::Maxwell3D, regs) + offsetof(Engines::Maxwell3D::Regs, reg_array);
    cg.MOV(X2, off);
    cg.ADD(X1, X1, X2);
    cg.LDR(W23, X1, W0, IndexExt::UXTW, 2);
    EmitProcessResult(cg, op.result_operation, op.dst, err);
}

static bool EmitOp(VectorCodeGenerator& cg, Macro::Opcode op, Label& err) {
    switch (op.operation) {
    case Macro::Operation::ALU:
        return EmitALU(cg, op, err);
    case Macro::Operation::AddImmediate:
        EmitAddImmediate(cg, op, err);
        return true;
    case Macro::Operation::ExtractInsert:
        EmitExtractInsert(cg, op, err);
        return true;
    case Macro::Operation::ExtractShiftLeftImmediate:
        EmitExtractShiftLeftImmediate(cg, op, err);
        return true;
    case Macro::Operation::ExtractShiftLeftRegister:
        EmitExtractShiftLeftRegister(cg, op, err);
        return true;
    case Macro::Operation::Read:
        EmitRead(cg, op, err);
        return true;
    default:
        return false;
    }
}

class MacroJITArm64Impl final : public DynamicCachedMacro {
public:
    explicit MacroJITArm64Impl(CodeMem mem_, JITFn fn_, std::span<const u32> code)
        : mem{std::move(mem_)}, fn{fn_}, interp{code} {}

    ~MacroJITArm64Impl() override {
        LOG_INFO(HW_GPU, "MME arm64: executions={}, compile_fallbacks={}, large_inputs={}, faults={}",
                 MmeJitHits(), MmeJitMisses(), large, faults);
    }

    void Execute(Core::System& sys, Engines::Maxwell3D& m3d, std::span<const u32> par,
                 u32 mth) override {
        std::array<u32, 4096> args;
        if (par.empty()) {
            ++faults;
            return;
        }
        if (par.size() > args.size()) {
            ++large;
            interp.Execute(sys, m3d, par, mth);
            return;
        }
        std::memcpy(args.data(), par.data(), par.size_bytes());
        JITState st;
        st.m3d = &m3d;
        st.sys = &sys;
        if (fn(&st, args.data(), args.data() + par.size())) {
            mme_jit_hits.fetch_add(1, std::memory_order_relaxed);
        } else {
            ++faults;
        }
    }

private:
    CodeMem mem;
    JITFn fn{};
    MacroInterpreterImpl interp;
    u64 large{};
    u64 faults{};
};

static std::unique_ptr<DynamicCachedMacro> Compile(std::span<const u32> code) {
    if (code.empty()) {
        return nullptr;
    }
    std::vector<u32> buf;
    VectorCodeGenerator cg{buf};
    std::vector<Label> lbls(code.size());
    Label end;
    Label err;
    Label ret;

    cg.STP(X29, X30, SP, PRE_INDEXED, -64);
    cg.STP(X19, X20, SP, 16);
    cg.STP(X21, X22, SP, 32);
    cg.STP(X23, X24, SP, 48);
    cg.MOV(X29, SP);
    cg.MOV(X19, X0);
    cg.MOV(X20, X1);
    cg.MOV(X21, X2);
    cg.MOV(W22, WZR);
    cg.MOV(W24, WZR);
    EmitFetchParameter(cg, W0, err);
    EmitSetRegister(cg, 1, W0);

    for (size_t i = 0; i < code.size(); ++i) {
        cg.l(lbls[i]);
        Macro::Opcode op{code[i]};
        if (op.operation == Macro::Operation::Branch) {
            const s64 tgt = static_cast<s64>(i) + op.immediate.Value();
            if (tgt < 0 || static_cast<u64>(tgt) >= code.size()) {
                return nullptr;
            }
            EmitGetRegister(cg, op.src_a, W0);
            Label skip;
            if (op.branch_condition == Macro::BranchCondition::Zero) {
                cg.CBNZ(W0, skip);
            } else {
                cg.CBZ(W0, skip);
            }
            if (!op.branch_annul &&
                (i + 1 >= code.size() || !EmitOp(cg, Macro::Opcode{code[i + 1]}, err))) {
                return nullptr;
            }
            cg.B(lbls[static_cast<size_t>(tgt)]);
            cg.l(skip);
        } else if (!EmitOp(cg, op, err)) {
            return nullptr;
        }
        if (op.is_exit) {
            if (i + 1 >= code.size() || !EmitOp(cg, Macro::Opcode{code[i + 1]}, err)) {
                return nullptr;
            }
            cg.B(end);
        }
    }

    cg.l(err);
    cg.MOV(W0, WZR);
    cg.B(ret);
    cg.l(end);
    cg.CMP(X20, X21);
    cg.CSET(W0, EQ);
    cg.l(ret);
    cg.LDP(X23, X24, SP, 48);
    cg.LDP(X21, X22, SP, 32);
    cg.LDP(X19, X20, SP, 16);
    cg.LDP(X29, X30, SP, POST_INDEXED, 64);
    cg.RET();

    CodeMem mem;
    if (!mem.Alloc(buf.size() * sizeof(u32)) ||
        !mem.ProtectAndFlush(buf.data(), buf.size() * sizeof(u32))) {
        return nullptr;
    }
    auto fn = reinterpret_cast<JITFn>(mem.rx);
    return std::make_unique<MacroJITArm64Impl>(std::move(mem), fn, code);
}

std::unique_ptr<DynamicCachedMacro> CompileMacroArm64(Core::System&, std::span<const u32> code) {
    try {
        if (auto jit = Compile(code)) {
            return jit;
        }
    } catch (const OaknutException& ex) {
        LOG_WARNING(HW_GPU, "MME arm64 assembler: {}", ex.what());
    }
    const auto n = mme_jit_miss.fetch_add(1, std::memory_order_relaxed) + 1;
    LOG_DEBUG(HW_GPU, "MME arm64: compile_fallbacks={}", n);
    return nullptr;
}

}

#endif

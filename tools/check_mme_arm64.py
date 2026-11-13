import argparse
import pathlib
import random
import struct
import subprocess
import tempfile

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn import arm64_const as reg


def imm(rd=0, ra=0, val=0, res=1, end=False):
    return 1 | (res << 4) | (int(end) << 7) | (rd << 8) | (ra << 11) | ((val & 0x3FFFF) << 14)


def alu(fn, rd, ra, rb, res=1, end=False):
    return (res << 4) | (int(end) << 7) | (rd << 8) | (ra << 11) | (rb << 14) | (fn << 17)


def branch(ra, off, nz=False, annul=False, end=False):
    return 7 | (int(nz) << 4) | (int(annul) << 5) | (int(end) << 7) | (ra << 11) | ((off & 0x3FFFF) << 14)


def cases():
    q = []
    nop = imm()
    stop = [imm(end=True), nop]
    for fn in [0, 1, 2, 3, 8, 9, 10, 11, 12]:
        for a in [0, 1, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF]:
            for b in [0, 1, 0xFFFFFFFF]:
                code = [imm(2, res=0), imm(val=0x1200, res=2),
                        alu(fn, 3, 1, 2, 4), imm(4, res=0),
                        alu(1, 5, 1, 2, 4), alu(3, 6, 1, 2, 4)] + stop
                q.append((f"alu-{fn}-{a}-{b}", code, [a, b, 11], 1))
    for res in range(8):
        code = [imm(val=0x1200, res=2), imm(2, val=0x1234, res=res)] + stop
        par = [17] + ([0x89ABCDEF] if res in [0, 3, 5, 6] else [])
        q.append((f"result-{res}", code, par, 1))
    for op in [2, 3, 4]:
        for width in [0, 1, 16, 31]:
            for src in [0, 1, 16, 31]:
                for dst in [0, 1, 16, 31]:
                    code = [imm(2, val=31), imm(val=0x1200, res=2),
                            op | (4 << 4) | (3 << 8) | (2 << 11) | (1 << 14) |
                            (src << 17) | (width << 22) | (dst << 27)] + stop
                    q.append((f"bits-{op}-{width}-{src}-{dst}", code, [0xF1234567], 1))
    for nz in [False, True]:
        for annul in [False, True]:
            for end in [False, True]:
                for val in [0, 1]:
                    code = [imm(val=0x1200, res=2), branch(1, 3, nz, annul, end),
                            imm(2, val=10, res=4), imm(2, val=20, res=4),
                            imm(2, val=30, res=4)] + stop
                    q.append((f"branch-{nz}-{annul}-{end}-{val}", code, [val], 1))
    q.append(("delay-exit", [branch(0, 3), imm(2, val=5, end=True),
                             imm(3, val=9), imm(4, val=13)] + stop, [1], 1))
    q.append(("exit-slot", [imm(2, val=3, end=True), imm(3, val=7),
                            imm(4, val=99)] + stop, [1], 1))
    for annul in [False, True]:
        code = [imm(2, val=9), imm(2, 2, -1), branch(2, -1, True, annul),
                imm(3, 3, 1)] + stop
        q.append((f"backward-{annul}", code, [1], 1))
    rng = random.Random(731)
    for idx in range(150):
        code = [imm(7, val=rng.randrange(32)), imm(val=0x1200, res=2)]
        par = [rng.getrandbits(32)]
        for _ in range(24):
            op = rng.randrange(6)
            res = rng.randrange(8)
            rd, ra, rb = (rng.randrange(7) for _ in range(3))
            if op == 0:
                word = alu(rng.choice([0, 1, 2, 3, 8, 9, 10, 11, 12]), rd, ra, rb, res)
            elif op == 1:
                word = imm(rd, ra, rng.randrange(-100, 101), res)
            elif op == 5:
                word = 5 | (res << 4) | (rd << 8) | (rng.randrange(0xE00) << 14)
            else:
                word = op | (res << 4) | (rd << 8) | ((7 if op in [3, 4] else ra) << 11) | (rb << 14)
                word |= rng.randrange(32) << 17 | rng.randrange(32) << 22 | rng.randrange(32) << 27
            code.append(word)
            if res in [0, 3, 5, 6]:
                par.append(rng.getrandbits(32))
        q.append((f"random-{idx}", code + stop, par, 1))
    q.extend([
        ("underrun", [imm(2, res=0)] + stop, [1], 2),
        ("leftover", stop, [1, 2], 2),
        ("read-oob", [5 | (1 << 4) | (0xE00 << 14)] + stop, [1], 2),
        ("fallthrough", [nop], [1], 2),
        ("empty-code", [], [1], 0),
        ("bad-branch", [branch(0, -1)] + stop, [1], 0),
        ("bad-delay", [branch(0, 2), branch(0, 1)] + stop, [1], 0),
        ("missing-delay", [imm(end=True)], [1], 0),
        ("bad-op", [6] + stop, [1], 0),
        ("bad-alu", [alu(4, 1, 1, 1)] + stop, [1], 0),
    ])
    return q


def build(root, tmp, zig, oak):
    inc = tmp / "inc"
    for path in [inc / "common", inc / "video_core/engines"]:
        path.mkdir(parents=True)
    hdr = (root / "src/video_core/macro.h").read_text(encoding="utf-8")
    ops = hdr[hdr.index("namespace Macro {"):hdr.index("} // namespace Macro") + 1]
    types = hdr[hdr.index("struct MacroInterpreterImpl final {"):hdr.index("using AnyCachedMacro")]
    (inc / "video_core/macro.h").write_text(
        '#pragma once\n#include <array>\n#include <optional>\n#include <span>\n#include <vector>\n'
        '#include "common/common_types.h"\n#include "common/bit_field.h"\n'
        'namespace Core { class System {}; }\nnamespace Tegra {\n'
        'namespace Engines { class Maxwell3D; }\n' + ops + types + '\n}\n', encoding="utf-8")
    (inc / "common/logging.h").write_text(
        '#pragma once\n#include <cstdio>\n#define LOG_INFO(...) ((void)0)\n#define LOG_DEBUG(...) ((void)0)\n'
        '#define LOG_WARNING(cat, fmt, msg) std::fprintf(stderr, "%s\\n", msg)\n', encoding="utf-8")
    (inc / "video_core/engines/maxwell_3d.h").write_text(r'''
#pragma once
#include <utility>
#include <vector>
#include "common/common_types.h"
namespace Core { class System; }
namespace Tegra::Engines {
class Maxwell3D {
public:
    struct Regs {
        static constexpr u32 NUM_REGS = 0xE00;
        u32 reg_array[NUM_REGS]{};
    } regs;
    std::vector<std::pair<u32, u32>> sends;
    void CallMethod(Core::System&, u32 mth, u32 val, bool) {
        sends.emplace_back(mth, val);
        if (mth < Regs::NUM_REGS) regs.reg_array[mth] = val;
    }
    u32 GetRegisterValue(u32 mth) const { return regs.reg_array[mth]; }
};
}
''', encoding="utf-8")
    src = (root / "src/video_core/macro.cpp").read_text(encoding="utf-8")
    ref = src[src.index("void MacroInterpreterImpl::Execute("):src.index("#ifdef ARCHITECTURE_x86_64\nnamespace {\nconstexpr Xbyak")]
    cpp = r'''
#include <array>
#include <atomic>
#include <cassert>
#include <cstring>
#include <fstream>
#include <memory>
#include <optional>
#include <span>
#include <stdexcept>
#include <utility>
#include <vector>
#include <oaknut/oaknut.hpp>
#include "video_core/macro.h"
#include "video_core/engines/maxwell_3d.h"
#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#else
#include <sys/mman.h>
#include <unistd.h>
#endif
#define ARCHITECTURE_arm64
#define private public
#include "video_core/macro/macro_jit_arm64.cpp"
#undef private
#define ASSERT(x) do { if (!(x)) throw std::runtime_error("assertion: " #x); } while (0)
#define ASSERT_MSG(x, ...) ASSERT(x)
#define UNIMPLEMENTED_MSG(...) throw std::runtime_error("unimplemented")
#define UNREACHABLE() throw std::runtime_error("unreachable")
namespace Tegra {
REF
}
using namespace Tegra;
static u32* src;
static bool Snapshot(JITState*, const u32* par, const u32* end) {
    const u32 val = *src;
    *src ^= 0xFFFFFFFF;
    return par != src && end == par + 1 && *par == val;
}
template <class T> void Put(std::ofstream& out, const T& val) {
    out.write(reinterpret_cast<const char*>(&val), sizeof(val));
}
static u32 Get(std::ifstream& in) {
    u32 val{};
    in.read(reinterpret_cast<char*>(&val), 4);
    return val;
}
int main(int argc, char** argv) {
    ASSERT(argc == 3);
    static_assert(!std::is_copy_constructible_v<CodeMem>);
    static_assert(std::is_nothrow_move_constructible_v<CodeMem>);
    {
        CodeMem a;
        ASSERT(a.Alloc(4));
        const auto ptr = a.rx;
        CodeMem b{std::move(a)};
        ASSERT(!a.rx && !a.sz && b.rx == ptr);
        const u32 op = 0xD65F03C0;
        const size_t sz = b.sz;
        b.sz = 0;
        ASSERT(!b.ProtectAndFlush(&op, 4));
        b.sz = sz;
        ASSERT(b.ProtectAndFlush(&op, 4));
#ifdef _WIN32
        MEMORY_BASIC_INFORMATION mbi{};
        ASSERT(VirtualQuery(b.rx, &mbi, sizeof(mbi)));
        ASSERT(mbi.Protect == PAGE_EXECUTE_READ);
#endif
    }
    Core::System sys;
    {
        const std::array<u32, 2> code{0x91, 0x11};
        auto jit = CompileMacroArm64(sys, code);
        ASSERT(jit);
        auto& obj = static_cast<MacroJITArm64Impl&>(*jit);
        obj.fn = Snapshot;
        u32 par = 0x12345678;
        src = &par;
        Engines::Maxwell3D m3d;
        const auto hits = MmeJitHits();
        obj.Execute(sys, m3d, {&par, 1}, 0);
        ASSERT(MmeJitHits() == hits + 1 && !obj.faults);
        obj.Execute(sys, m3d, {}, 0);
        ASSERT(obj.faults == 1);
    }
    {
        const std::array<u32, 6> code{0xA11, 0x301, 0xFFFFD211, 0xFFFF9037, 0x91, 0x11};
        auto jit = CompileMacroArm64(sys, code);
        ASSERT(jit);
        auto& obj = static_cast<MacroJITArm64Impl&>(*jit);
        std::vector<u32> par(5000, 7);
        par[0] = 4999;
        Engines::Maxwell3D m3d;
        obj.Execute(sys, m3d, par, 0);
        ASSERT(obj.large == 1 && obj.interp.next_parameter_index == par.size());
    }
    std::ifstream in(argv[1], std::ios::binary);
    std::ofstream out(argv[2], std::ios::binary);
    Put(out, static_cast<u64>(reinterpret_cast<uintptr_t>(&MmeSendHelper)));
    Put(out, static_cast<u32>(sizeof(JITState)));
    Put(out, static_cast<u32>(offsetof(JITState, regs)));
    Put(out, static_cast<u32>(offsetof(JITState, m3d)));
    Put(out, static_cast<u32>(offsetof(JITState, sys)));
    Put(out, static_cast<u32>(sizeof(Engines::Maxwell3D)));
    Put(out, static_cast<u32>(offsetof(Engines::Maxwell3D, regs)));
    const u32 count = Get(in);
    for (u32 i = 0; i < count; ++i) {
        const u32 nc = Get(in), np = Get(in), mode = Get(in);
        std::vector<u32> code(nc), par(np);
        in.read(reinterpret_cast<char*>(code.data()), nc * 4);
        in.read(reinterpret_cast<char*>(par.data()), np * 4);
        auto jit = CompileMacroArm64(sys, code);
        Put(out, static_cast<u32>(jit != nullptr));
        if (!jit) continue;
        auto& obj = static_cast<MacroJITArm64Impl&>(*jit);
        Put(out, static_cast<u32>(obj.mem.sz));
        out.write(static_cast<const char*>(obj.mem.rx), obj.mem.sz);
        Engines::Maxwell3D m3d;
        for (u32 j = 0; j < Engines::Maxwell3D::Regs::NUM_REGS; ++j)
            m3d.regs.reg_array[j] = j * 17;
        MacroInterpreterImpl interp{code};
        if (mode == 1) interp.Execute(sys, m3d, par, 0);
        Put(out, static_cast<u32>(m3d.sends.size()));
        for (auto [mth, val] : m3d.sends) { Put(out, mth); Put(out, val); }
        out.write(reinterpret_cast<const char*>(interp.registers.data()), 32);
    }
    ASSERT(in && out);
}
'''.replace("REF", ref)
    (tmp / "test.cpp").write_text(cpp, encoding="utf-8")
    exe = tmp / "mme-test.exe"
    cmd = [str(zig), "c++", "-std=c++20", "-O1", "-I" + str(inc),
           "-I" + str(root / "src"), "-I" + str(oak), str(tmp / "test.cpp"), "-o", str(exe)]
    subprocess.run(cmd, check=True, timeout=300)
    return exe


def run(q, data):
    pos = 0

    def get(fmt):
        nonlocal pos
        val = struct.unpack_from(fmt, data, pos)
        pos += struct.calcsize(fmt)
        return val

    helper, st_sz, regs_off, m3d_off, sys_off, gpu_sz, gpu_off = get("<Q6I")
    for name, code, par, mode in q:
        ok, = get("<I")
        assert bool(ok) == (mode != 0), (name, "compile result", ok)
        if not ok:
            continue
        sz, = get("<I")
        blob = data[pos:pos + sz]
        pos += sz
        count, = get("<I")
        expected = [get("<II") for _ in range(count)]
        final = get("<8I")
        uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for addr, size in [(0x100000, sz), (0x200000, 0x10000), (0x300000, st_sz),
                           (0x400000, gpu_sz), (0x500000, len(par) * 4), (0x600000, 4096),
                           (helper & ~4095, 4096)]:
            uc.mem_map(addr, (size + 4095) & ~4095)
        uc.mem_write(0x100000, blob)
        uc.mem_write(0x300000 + m3d_off, struct.pack("<Q", 0x400000))
        uc.mem_write(0x300000 + sys_off, struct.pack("<Q", 0x700000))
        uc.mem_write(0x400000 + gpu_off, struct.pack("<3584I", *(i * 17 for i in range(0xE00))))
        uc.mem_write(0x500000, struct.pack("<" + "I" * len(par), *par))
        uc.reg_write(reg.UC_ARM64_REG_X0, 0x300000)
        uc.reg_write(reg.UC_ARM64_REG_X1, 0x500000)
        uc.reg_write(reg.UC_ARM64_REG_X2, 0x500000 + len(par) * 4)
        uc.reg_write(reg.UC_ARM64_REG_SP, 0x210000)
        uc.reg_write(reg.UC_ARM64_REG_LR, 0x600000)
        saved = {}
        for idx in range(19, 30):
            rid = getattr(reg, f"UC_ARM64_REG_X{idx}")
            saved[rid] = 0xA000 + idx
            uc.reg_write(rid, saved[rid])
        actual = []

        def send(cpu, pc, size, ctx):
            if pc != helper:
                return
            assert cpu.reg_read(reg.UC_ARM64_REG_X0) == 0x700000, name
            assert cpu.reg_read(reg.UC_ARM64_REG_X1) == 0x400000, name
            mth = cpu.reg_read(reg.UC_ARM64_REG_W2) & 0xFFF
            val = cpu.reg_read(reg.UC_ARM64_REG_W3)
            actual.append((mth, val))
            if mth < 0xE00:
                cpu.mem_write(0x400000 + gpu_off + mth * 4, struct.pack("<I", val))
            for idx in range(18):
                cpu.reg_write(getattr(reg, f"UC_ARM64_REG_X{idx}"), 0xBAD000 + idx)
            cpu.reg_write(reg.UC_ARM64_REG_NZCV, 0xF0000000)
            cpu.reg_write(reg.UC_ARM64_REG_PC, cpu.reg_read(reg.UC_ARM64_REG_LR))

        uc.hook_add(UC_HOOK_CODE, send, begin=helper, end=helper)
        uc.emu_start(0x100000, 0x600000, count=1000000)
        assert uc.reg_read(reg.UC_ARM64_REG_PC) == 0x600000, (name, "did not return")
        assert uc.reg_read(reg.UC_ARM64_REG_W0) == (1 if mode == 1 else 0), (name, "status")
        assert uc.reg_read(reg.UC_ARM64_REG_SP) == 0x210000, (name, "stack")
        for rid, val in saved.items():
            assert uc.reg_read(rid) == val, (name, "callee-saved", rid)
        if mode == 1:
            regs = struct.unpack("<8I", uc.mem_read(0x300000 + regs_off, 32))
            assert actual == expected, (name, "sends", actual, expected)
            assert regs == final, (name, "registers", regs, final)
    assert pos == len(data), (pos, len(data))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zig", required=True, type=pathlib.Path)
    ap.add_argument("--oaknut", type=pathlib.Path)
    args = ap.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    oak = args.oaknut or next((root / ".cache/cpm/oaknut").glob("*/include"))
    q = cases()
    with tempfile.TemporaryDirectory(prefix="mme-check-") as path:
        tmp = pathlib.Path(path)
        exe = build(root, tmp, args.zig, oak)
        buf = bytearray(struct.pack("<I", len(q)))
        for name, code, par, mode in q:
            buf.extend(struct.pack("<III", len(code), len(par), mode))
            buf.extend(struct.pack("<" + "I" * (len(code) + len(par)), *code, *par))
        (tmp / "in.bin").write_bytes(buf)
        subprocess.run([str(exe), str(tmp / "in.bin"), str(tmp / "out.bin")], check=True, timeout=60)
        run(q, (tmp / "out.bin").read_bytes())
    print(f"PASS: {len(q)} MME cases; native reference versus emulated ARM64, ownership, W^X and snapshot checks")
    print("No Android device, driver, SELinux policy or performance validation was performed.")


if __name__ == "__main__":
    main()

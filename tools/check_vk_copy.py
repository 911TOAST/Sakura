import argparse
import pathlib
import subprocess
import tempfile


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zig", required=True, type=pathlib.Path)
    args = ap.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    src = (root / "src/video_core/renderer_vulkan/vk_buffer_cache.cpp").read_text(encoding="utf-8")
    conv = src[src.index("VkBufferCopy MakeBufferCopy("):src.index("VkIndexType IndexTypeFromNumElements(")]
    fn = src[src.index("void BufferCacheRuntime::CopyBuffer("):src.index("void BufferCacheRuntime::PreCopyBarrier()")]
    cpp = r'''
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <new>
#include <span>
#include <type_traits>
using VkBuffer = uint64_t;
constexpr VkBuffer VK_NULL_HANDLE = 0;
constexpr int VK_STRUCTURE_TYPE_MEMORY_BARRIER = 1;
constexpr int VK_ACCESS_MEMORY_WRITE_BIT = 1;
constexpr int VK_ACCESS_TRANSFER_READ_BIT = 2;
constexpr int VK_ACCESS_TRANSFER_WRITE_BIT = 4;
constexpr int VK_ACCESS_MEMORY_READ_BIT = 8;
constexpr int VK_PIPELINE_STAGE_TRANSFER_BIT = 16;
constexpr int VK_PIPELINE_STAGE_ALL_COMMANDS_BIT = 32;
struct VkMemoryBarrier { int sType; const void* pNext; int srcAccessMask, dstAccessMask; };
struct VkBufferCopy { uint64_t srcOffset, dstOffset, size; };
namespace VideoCommon {
struct BufferCopy { uint64_t src_offset, dst_offset, size; };
}
struct Event {
    int type{};
    bool upload{};
    VkBuffer src{}, dst{};
    size_t n{};
    std::array<VkBufferCopy, 8> q{};
};
struct Trace {
    std::array<Event, 128> events{};
    size_t n{};
};
namespace vk {
constexpr int PIPELINE_STAGE_GRAPHICS_COMPUTE_TRANSFER = 64;
struct CommandBuffer {
    Trace* trace;
    bool upload;
    void CopyBuffer(VkBuffer src, VkBuffer dst, std::span<const VkBufferCopy> q) {
        auto& ev = trace->events.at(trace->n++);
        ev.type = 0;
        ev.upload = upload;
        ev.src = src;
        ev.dst = dst;
        ev.n = q.size();
        assert(q.size() <= 8);
        std::ranges::copy(q, ev.q.begin());
    }
    void PipelineBarrier(int src, int dst, int dep, VkMemoryBarrier bar) {
        assert(!upload && dep == 0);
        auto& ev = trace->events.at(trace->n++);
        if (bar.srcAccessMask == VK_ACCESS_MEMORY_WRITE_BIT) {
            assert(src == PIPELINE_STAGE_GRAPHICS_COMPUTE_TRANSFER);
            assert(dst == VK_PIPELINE_STAGE_TRANSFER_BIT);
            assert(bar.dstAccessMask == (VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT));
            ev.type = 1;
        } else {
            assert(src == VK_PIPELINE_STAGE_TRANSFER_BIT);
            assert(dst == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            assert(bar.srcAccessMask == VK_ACCESS_TRANSFER_WRITE_BIT);
            assert(bar.dstAccessMask == (VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT));
            ev.type = 2;
        }
    }
};
}
struct Scheduler {
    struct Job {
        alignas(16) std::array<std::byte, 256> buf;
        void (*run)(std::byte*, vk::CommandBuffer, vk::CommandBuffer){};
    };
    std::array<Job, 64> jobs;
    size_t n{}, contexts{}, uploads{};
    void RequestOutsideRenderPassOperationContext() { ++contexts; }
    template <typename F> void Add(F fn) {
        static_assert(std::is_trivially_copyable_v<F> && sizeof(F) <= 256 && alignof(F) <= 16);
        auto& job = jobs.at(n++);
        ::new (job.buf.data()) F{fn};
        job.run = [](std::byte* ptr, vk::CommandBuffer a, vk::CommandBuffer b) {
            (*reinterpret_cast<F*>(ptr))(a, b);
        };
    }
    template <typename F> void Record(F fn) {
        Add([fn](vk::CommandBuffer a, vk::CommandBuffer) { fn(a); });
    }
    template <typename F> void RecordUploadBuffer(VkBuffer dst, F fn) {
        assert(dst == 11);
        ++uploads;
        Add(fn);
    }
    void Flush(Trace& trace) {
        for (size_t i = 0; i < n; ++i)
            jobs[i].run(jobs[i].buf.data(), {&trace, false}, {&trace, true});
    }
};
struct Pool { VkBuffer StreamBuf() const { return 5; } };
struct BufferCacheRuntime {
    Scheduler scheduler;
    Pool staging_pool;
    bool pending_copy_barrier{};
    std::atomic<uint64_t> copy_regions{}, copy_batches{};
    void CopyBuffer(VkBuffer dst_buffer, VkBuffer src_buffer,
                    std::span<const VideoCommon::BufferCopy> copies, bool barrier,
                    bool can_reorder_upload);
};
CONV
FN
int main() {
    for (size_t n : {0, 1, 7, 8, 9, 15, 16, 17, 65, 257}) {
        for (unsigned flags = 0; flags < 32; ++flags) {
            const bool bar = flags & 1, stream = flags & 2, reorder = flags & 4;
            const bool null_src = flags & 8, null_dst = flags & 16;
            const VkBuffer src = null_src ? 0 : (stream ? 5 : 9);
            const VkBuffer dst = null_dst ? 0 : 11;
            BufferCacheRuntime rt;
            std::array<VideoCommon::BufferCopy, 257> copies;
            for (size_t i = 0; i < n; ++i) copies[i] = {i * 64, i * 96, i + 1};
            rt.CopyBuffer(dst, src, std::span{copies}.first(n), bar, reorder);
            copies.fill({});
            Trace trace;
            rt.scheduler.Flush(trace);
            if (!n || !src || !dst) {
                assert(!trace.n && !rt.copy_regions && !rt.copy_batches);
                assert(!rt.scheduler.contexts && !rt.pending_copy_barrier);
                continue;
            }
            const bool upload = stream && reorder;
            assert(rt.copy_regions == n && rt.copy_batches == (n + 7) / 8);
            assert(rt.scheduler.contexts == (upload ? 0 : 1));
            assert(rt.scheduler.uploads == (upload ? (n + 7) / 8 : 0));
            assert(rt.pending_copy_barrier == (!upload && !bar));
            size_t count = 0, reads = 0, writes = 0;
            for (size_t i = 0; i < trace.n; ++i) {
                const auto& ev = trace.events[i];
                if (ev.type == 1) { ++reads; assert(i == 0); continue; }
                if (ev.type == 2) { ++writes; assert(i + 1 == trace.n); continue; }
                assert(ev.upload == upload && ev.src == src && ev.dst == dst);
                for (size_t j = 0; j < ev.n; ++j, ++count) {
                    assert(ev.q[j].srcOffset == count * 64);
                    assert(ev.q[j].dstOffset == count * 96);
                    assert(ev.q[j].size == count + 1);
                }
            }
            assert(count == n);
            assert(reads == (!upload && bar ? 1 : 0) && writes == reads);
        }
    }
}
'''.replace("CONV", conv).replace("FN", fn)
    with tempfile.TemporaryDirectory(prefix="vk-copy-check-") as path:
        tmp = pathlib.Path(path)
        (tmp / "test.cpp").write_text(cpp, encoding="utf-8")
        exe = tmp / "test.exe"
        subprocess.run([str(args.zig), "c++", "-std=c++20", "-O1", str(tmp / "test.cpp"),
                        "-o", str(exe)], check=True, timeout=300)
        subprocess.run([str(exe)], check=True, timeout=60)
    print("PASS: 320 copy cases; delayed capture lifetime, ordering, routing, counters and outer barriers")
    print("No Vulkan driver execution or performance validation was performed.")


if __name__ == "__main__":
    main()

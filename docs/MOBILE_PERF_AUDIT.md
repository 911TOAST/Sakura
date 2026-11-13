# Mobile performance audit — the Android emulator checklist, item by item

This page exists to stop work being spent twice. A long "switch emulator on
Android" optimisation checklist circulates that mixes ideas already implemented
here, ideas that do not apply to this codebase at all, and ideas that are still
open. Each item below is marked with what this tree actually does, where the
relevant code lives, and what is missing.

No FPS number on this page is measured. The tree was not run on a device while
this page was written; every "expected" statement is a mechanism, not a
benchmark. `PERFORMANCE_REPORT.md` records the same rule.

Status legend: **present**, **partial**, **absent**, **not applicable**.

## 1. ARM64 CPU, memory and scheduling

| Item | Status | Where |
|---|---|---|
| `MRS TPIDRRO_EL0` SIGILL trapping | **not applicable** | `externals/dynarmic`, `src/core/arm` |
| `memfd_create` + shared mapping for guest RAM | **present** | `src/common/host_memory.cpp:520-593` |
| Batched I-cache maintenance for SMC | **present (dynarmic)** | `externals/dynarmic` |
| big.LITTLE core pinning | **present, extended here** | `src/common/thread.cpp:165`, `src/video_core/gpu_thread.cpp:36`, `src/core/cpu_manager.cpp:175` |
| JNI-free ROM streaming / input | **present** | `FileUtil.kt:101` (`detachFd`), `native_input.cpp` |

**TPIDRRO_EL0 is not a trap here.** suyu does not run guest ARM code natively.
Guest instructions go through Dynarmic, an ARM64-to-host JIT, and
`TPIDRRO_EL0` is a first-class IR opcode there
(`externals/dynarmic/src/dynarmic/ir/opcodes.inc:79`,
`frontend/A64/translate/impl/system.cpp:154`). There is no `SIGILL` handler, no
per-instruction kernel round trip, and therefore nothing to binary-patch. The
"reserve a GPR and patch `MRS` into `MOV`" trick belongs to NCE designs that map
guest pages executable; this tree never does that. Patching Dynarmic's IR
`A64GetTPIDRRO` to read a host register is possible in principle, but the IR
result is already a register read, so it would move work around rather than
remove a trap.

**Guest RAM aliasing is already hardware-aliased.** On POSIX the whole
guest-mapped arena is backed by one `memfd_create` object and each guest view is
`mmap(..., MAP_SHARED | MAP_FIXED, fd, host_offset)` over it, so two virtual
addresses that alias the same guest page are the same physical page in the
kernel's page tables. There is no dirty-page copy path to replace.

**Core pinning** already selected the fastest capacity tier. It had two gaps,
both fixed in this change: the mask could collapse onto a single prime core on
1+3+4 and 1+4+3 topologies (leaving the GPU and CPU threads fighting over one
core), and only the GPU thread was pinned. The mask is now the four fastest
cores, widened to the next capacity tier when the fastest tier holds one core,
and every guest CPU thread applies it too.

## 2. TBDR Vulkan (Adreno GMEM / Mali tile memory)

| Item | Status | Where |
|---|---|---|
| `LOAD_OP_DONT_CARE` / `STORE_OP_DONT_CARE` pruning | **present** | `src/video_core/renderer_vulkan/vk_scheduler.cpp:515` (`renderer_store_elision`), `settings.h` (`renderer_clear_loadop`) |
| Avoid `VK_IMAGE_LAYOUT_GENERAL` so UBWC/AFBC stays on | **present, opt-in** | `settings.h` (`renderer_afbc_layouts`, `renderer_optimal_attachments`, `renderer_feedback_loop_layout`) |
| Zero-copy present path | **present** | `ZERO_COPY_OVERDRIVE.md`, `vk_present_manager.cpp` |
| `AHardwareBuffer` / `VK_EXT_external_memory_host` for guest RAM | **absent** | — |
| Native ASTC passthrough | **present** | `vk_texture_cache.cpp:139` (`UsesNativeAstc`) |
| Direct Turnip (`libvulkan_freedreno.so`) loading | **present** | `src/android/app/src/main/jni/native_freedreno.cpp` |
| Narrow pass barriers | **present, opt-in** | `settings.h` (`renderer_narrow_pass_barriers`) |

Transient attachments are *not* used, and deliberately so: a transient
attachment cannot be sampled and cannot have its contents reused, and the guest
render-target cache reuses colour and depth surfaces across passes constantly.
`renderer_store_elision` covers the same traffic (a store that the next pass
clears) without the lifetime restriction.

`AHardwareBuffer` zero-copy for guest RAM is the one real gap in this section.
Mapping a `memfd` through `VK_EXT_external_memory_host` is possible on some
drivers but is not a portable win: the extension is optional, its memory type
must be host-visible *and* device-local, and a missed alignment/granularity
rule corrupts textures rather than failing loudly. It needs a device and a
driver matrix to land, so it is not in this change.

## 3. Shaders, macros and pipelines

| Item | Status | Where |
|---|---|---|
| `RelaxedPrecision` (FP16-permission) injection | **added here, opt-in** | `emit_spirv_floating_point.cpp:20`, `profile.h`, `vk_pipeline_cache.cpp:580` |
| Maxwell Macro Engine native ARM64 JIT | **absent** | `src/video_core/macro/macro_jit_x64.cpp` is x86-64 only |
| Bindless descriptor heap mirroring TIC/TSC | **absent** | — |
| Zstd-compressed `VkPipelineCache` + background warm-up | **partial** | `SHADER_WARMUP_AND_CACHE.md`, `vk_pipeline_cache.cpp` |
| Async shader compilation | **present** | `use_asynchronous_shaders`, `VkPipelineBuilder` workers |

The new switch is **Settings → Graphics → Relaxed fragment precision**
(`renderer_relaxed_precision`, off by default). When on, every FP32 arithmetic
result emitted for a fragment shader gets the `RelaxedPrecision` decoration, so
a driver that supports 16-bit float execution may run that chain at reduced
precision. Vertex math, depth, integer ops and comparisons are untouched.
Because the decoration changes the emitted SPIR-V, the flag is folded into the
on-disk shader cache salt, so flipping it regenerates shaders instead of
reusing stale ones. What is **not** verified: whether Mali or Adreno actually
select FP16 for these chains, and whether any title shows precision artefacts.
Both need a device A/B; the switch is off by default for that reason.

MME JIT: the ARM64 emitter does not exist, and writing one is a
multi-thousand-line project (the x86-64 one is the model). Interpreting MME
bytecode is measurable on macro-heavy titles but it is not a small patch.

## 4. Thermal, power and frame pacing

| Item | Status | Where |
|---|---|---|
| ADPF / `APerformanceHintManager` | **absent** | — |
| Thermal headroom → dynamic resolution | **absent** | — |
| `VK_GOOGLE_display_timing` scanout pacing | **absent** | — |
| Frame pacing / speed limit | **present** | `frame_pacing_mode`, `use_speed_limit` |
| Present thread / async presentation | **present** | `vk_present_manager.cpp` |

These are the real remaining levers on a phone, and none of them are a
drop-in. ADPF needs a `PerformanceHintManager` session per render thread, a
frame-work-duration feed from the Vulkan thread and an API-31 runtime guard;
thermal scaling needs a policy for when to step resolution and how to make the
guest not observe it mid-frame. Both are separate changes with on-device
tuning, not something to bolt onto a shader toggle.

## 5. Audio and host caches

Audio is mixed on its own `AudioManager` thread and handed to the cubeb sink in
fixed-size buffers, so the mixer is not on the frame path and does not gate
frame time on the devices this fork targets. Vectorising the voice mixer with
NEON is possible, but it optimises a thread that is not the bottleneck on a
title running at 22 fps because of GPU and TLS-style CPU work.

## What this change set actually alters

1. `renderer_relaxed_precision` — opt-in `RelaxedPrecision` on fragment FP32
   arithmetic, cache-salt aware.
2. Core pinning — four-core fast mask, widening on single-prime topologies, and
   the guest CPU threads join the GPU thread on that mask.

Everything else in the checklist is either already in the tree (and listed
above with a file) or does not apply to a Dynarmic-based emulator.

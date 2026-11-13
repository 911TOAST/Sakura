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
| `MRS TPIDRRO_EL0` SIGILL trapping | **present (NCE)** / **not applicable (Dynarmic)** | `src/core/arm/nce/patcher.cpp:117`, `WriteMrsHandler` (same file), `src/common/settings.cpp` (`IsNceEnabled`) |
| `memfd_create` + shared mapping for guest RAM | **present** | `src/common/host_memory.cpp:520-593` |
| Batched I-cache maintenance for SMC | **present (dynarmic)** | `externals/dynarmic` |
| big.LITTLE core pinning | **present, extended here** | `src/common/thread.cpp:165`, `src/video_core/gpu_thread.cpp:36`, `src/core/cpu_manager.cpp:175` |
| JNI-free ROM streaming / input | **present** | `FileUtil.kt:101` (`detachFd`), `native_input.cpp` |

**TPIDRRO_EL0 is a trap path in the NCE backend and a register read in Dynarmic.**
Correction to an earlier revision of this document, which claimed native execution
does not exist here: it does. `src/core/arm/nce/` (arm_nce.cpp, patcher.cpp,
arm_nce.s) is present and is the default `cpu_backend` on Android
(`Settings::IsNceEnabled()`, `src/common/settings.cpp:302`; `cpu_backend`
defaults to `CpuBackend::Nce` under `HAS_NCE` in `src/common/settings.h`).
NCE maps guest pages executable, so a guest `MRS Xn, TPIDRRO_EL0` would read the
host's own TLS register and return the wrong value. The patcher already solves
this the cheap way: `Patcher::Patch` (patcher.cpp:117-130) recognises
`MRS Xn, TPIDR_EL0` / `TPIDRRO_EL0` and `WriteMrsHandler` (patcher.cpp, ~line 562)
rewrites each one into

```
MRS  Xn, TPIDR_EL0          // host TLS base of the NCE thread
LDR  Xn, Xn, #offsetof(NativeExecutionParameters, tpidrro_el0 | tpidr_el0)
B    <instruction after the guest MRS>
```

so the guest read becomes two loads and a branch, never a SIGILL round trip. No
further work is needed on this item.

The second CPU backend in the tree, Dynarmic, needs no patching either: guest
instructions go through its IR, and `TPIDRRO_EL0` is a first-class IR opcode
(`externals/dynarmic/src/dynarmic/ir/opcodes.inc:79`,
`frontend/A64/translate/impl/system.cpp:154` → `ir.GetTPIDRRO()`). On the x64 host
backend `A64EmitX64` loads the value from the `tpidrro_el0` pointer stored in the
JitState/config (`backend/x64/a64_emit_x64.cpp:570`, `interface/A64/config.h`
bit 192) — a plain memory load, not a host call. Note that the concrete host
configuration only matters on x86-64 hosts; Android ships the arm64 backend,
which is the one to read when tuning.

**Guest RAM aliasing is already hardware-aliased.** On POSIX the whole
guest-mapped arena is backed by one `memfd_create` object and each guest view is
`mmap(..., MAP_SHARED | MAP_FIXED, fd, host_offset)` over it, so two virtual
addresses that alias the same guest page are the same physical page in the
kernel's page tables. There is no dirty-page copy path to replace.

**Core pinning** already selected the fastest capacity tier. It had three gaps,
all fixed here: the mask could collapse onto a single prime core on 1+3+4 and
1+4+3 topologies (leaving the GPU and CPU threads fighting over one core); only
the GPU thread was pinned; and a first revision pinned all four guest cores,
which put four guest threads plus the record thread into a four-core set while
SurfaceFlinger, audio and the kernel workers competed for the same cores.
`PinCurrentThreadToFastCores` (`src/common/thread.cpp:165`) now takes the four
fastest cores and widens to the next capacity tier when the fastest tier holds a
single core, and `CpuManager::RunThread` (`src/core/cpu_manager.cpp:175`) pins
guest cores 0-2 plus the GPU thread but leaves guest core 3 — the HOS system and
idle thread — unpinned so it can float to a smaller core instead of contending
for the prime core.

Note for hardware testing: on GKI 5.15/6.1+ kernels a hard affinity mask and the
vendor frequency governor can disagree. Affinity is only a placement hint; the
clock boost still has to come from ADPF (section 4), and the two settings should
be evaluated together.

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
| `RelaxedPrecision` (FP16-permission) injection | **added here, opt-in** | `spirv_emit_context.h` (`EmitContext::Relax`), `emit_spirv_floating_point.cpp`, `emit_spirv_context_get_set.cpp` (`EmitGetCbufF32`), `emit_spirv_image.cpp`, `profile.h`, `vk_pipeline_cache.cpp:580` |
| Maxwell Macro Engine native ARM64 JIT | **absent, scoped** | `src/video_core/macro/macro_jit_x64.cpp` is x86-64 only; see below |
| Bindless descriptor heap mirroring TIC/TSC | **absent, scoped** | `src/video_core/renderer_vulkan/`; see below |
| Zstd-compressed `VkPipelineCache` + background warm-up | **partial** | `SHADER_WARMUP_AND_CACHE.md`, `vk_pipeline_cache.cpp` |
| Async shader compilation | **present** | `use_asynchronous_shaders`, `VkPipelineBuilder` workers |

The new switch is **Settings → Graphics → Relaxed fragment precision**
(`renderer_relaxed_precision`, off by default). When on, FP32 values emitted for
a fragment shader get the `RelaxedPrecision` decoration, so a driver that
supports 16-bit float execution may run that chain at reduced precision. Vertex
math, integer ops and comparisons are untouched.

A second pass hardened the decoration to avoid the two ways the first revision
could have backfired on Adreno (qglc) and Mali:

- **Conversion spam.** Decorating arithmetic alone leaves its operands FP32, and
  the driver then has to emit `cvt.f16.f32` / `cvt.f32.f16` around every
  decorated instruction — slower than plain FP32. The pass now decorates the
  chain sources too: `EmitGetCbufF32` in `emit_spirv_context_get_set.cpp` (both
  the descriptor-aliasing load and the bitcast path) and the non-integer results
  of `EmitImageSampleImplicitLod` / `EmitImageSampleExplicitLod` in
  `emit_spirv_image.cpp`. Counters, indices, bitcast-through-U32 composite ops
  and `OpPhi` results are deliberately *not* decorated: composite and phi values
  are built from U32 bitcasts here, so decorating them would either be rejected
  or force the driver to insert conversions, which is the failure mode this is
  meant to avoid.
- **Depth and HDR.** A shader that writes `gl_FragDepth`
  (`program.info.stores_frag_depth`) is reconstructing or biasing depth itself,
  and reduced precision there is z-fighting; `EmitSPIRV` in `emit_spirv.cpp` now
  copies the profile and clears `relax_fp32_precision` for the whole shader. HDR
  values above 65504.0 still clip to infinity under FP16; that is a property of
  the arithmetic, not of the decoration, and it is the reason this is opt-in
  rather than default. A title that blooms past the FP16 range will show it, and
  a magnitude guard on the constant pool could be added once there is a title to
  test against.

Decoration also no longer depends on a local helper: `EmitContext::Relax` and
`EmitContext::CanRelaxF32` (`spirv_emit_context.h`) hold the single stage check,
and `EmitSPIRV` legitimately rewrites the profile before the context is built, so
the flag stays consistent across the emitter files. Because the decoration
changes the emitted SPIR-V, it is folded into the on-disk shader cache salt, so
flipping it regenerates shaders instead of reusing stale ones. What is **not**
verified: whether Mali or Adreno actually select FP16 for these chains, and
whether any title shows precision artefacts. Both need a device A/B; the switch
is off by default for that reason.

Offline verification without a phone: dump the SPIR-V for a handful of shaders
from the pipeline cache (`renderer_relaxed_precision` on and off), then feed the
binaries to `malioc --core <gpu> --format csv` or Mesa's `ir3_compiler` and
compare instruction counts and the share of `f16` vs `cvt` instructions. A
successful run shows fewer instructions at the same ALU occupancy; a *higher*
count means conversion spam and the switch should stay off on that driver.

**MME JIT (scoped, not in this change).** `src/video_core/macro/` has a C++
interpreter (`macro_interpreter.cpp`) and an x86-64 JIT
(`macro_jit_x64.cpp`). An ARM64 emitter would be a new
`macro_jit_arm64.cpp` following the same table-driven shape: `MacroJIT` interface
from `macro.h`, one register map over `MacroEngine` state, a prologue that loads
the register file base once, and a fallback that bails out to the interpreter on
an opcode the emitter does not implement rather than silently mis-executing.
Correctness is the risk: macro bytecode uploads constant buffers and dispatches
multidraws, so a bad emitter corrupts rendering far away from the bug. A smaller
first step with real but bounded value is a hit-rate counter that logs how often
the interpreter runs and which opcodes dominate; only after that data exists is
an emitter worth writing.

**Bindless descriptor heap (scoped, not in this change).**
`VK_EXT_descriptor_indexing` with `UPDATE_AFTER_BIND` + `PARTIALLY_BOUND` would
let TIC/TSC descriptor tables live in one big descriptor array addressed by
`texture_index` in the shader, removing the per-draw descriptor set
allocate/update/hash on the record thread. It is not shipped because it touches
all of `vk_descriptor_pool.cpp`, `vk_texture_cache.cpp`,
`vk_sampler_cache.cpp` and the pipeline cache policy at once, requires
`shaderSampledImageArrayNonUniformIndexing` (not universal on older Mali
drivers), and a wrong descriptor stride or a descriptor set freed while a
command buffer still references it is a GPU hang, not a visual glitch. The
prerequisite is a capability probe that records the exact feature bits, which
belongs in `vk_device.cpp`.

## 4. Thermal, power and frame pacing

| Item | Status | Where |
|---|---|---|
| ADPF / `APerformanceHintManager` | **absent, scoped** | `native.cpp`, `cpu_manager.cpp` (see below) |
| Thermal headroom → dynamic resolution | **absent, scoped** | `renderer_resolution_scale` (see below) |
| `VK_GOOGLE_display_timing` scanout pacing | **absent, scoped** | `vk_swapchain.cpp`, `vk_present_manager.cpp` |
| Frame pacing / speed limit | **present** | `frame_pacing_mode`, `use_speed_limit` |
| Present thread / async presentation | **present** | `vk_present_manager.cpp` |

These are the real remaining levers on a phone, and none of them are a
drop-in. ADPF needs a `PerformanceHintManager` session per render thread, a
frame-work-duration feed from the Vulkan thread and an API-31 runtime guard;
thermal scaling needs a policy for when to step resolution and how to make the
guest not observe it mid-frame. Both are separate changes with on-device
tuning, not something to bolt onto a shader toggle.

**ADPF and pacing (scoped, not in this change).** The integration point is in
the Android layer, not the renderer: `NativeLibrary.kt` / `native.cpp` already
own the emulation thread and the surface, and the JNI boundary is where a
`JavaVM`-owned `APerformanceHintManager` session should live, so the core never
links against API-31 symbols directly. The shape is one session per process,
`updateTargetWorkDuration(target_ns)` from the frame-pacing setting, and
`reportActualWorkDuration(measured_ns)` once per completed frame from the
present thread; the TID list is the three pinned guest cores plus the record
thread (`cpu_manager.cpp`, `gpu_thread.cpp`). Hard affinity and ADPF are
complementary: affinity decides placement, ADPF asks the vendor governor for
clocks on the cores that were chosen. `VK_GOOGLE_display_timing` is the same
story on the present side — it needs a swapchain-level `presentTime`/
`actualPresentTime` pair, which is `vk_swapchain.cpp` and
`vk_present_manager.cpp` work and only pays off once the pacing policy is
tested under throttling.

**Thermal-driven resolution (scoped).** The hook exists conceptually in
`renderer_resolution_scale` / the dynamic-resolution path, but stepping it on
temperature needs a reliable signal. `PowerManager.getThermalStatus()` (API 29+)
and `getCurrentThermalStatus()` are coarse, and reading `/sys/class/thermal`
is vendor-specific; the honest design is to read a status callback on the Kotlin
side, forward it over JNI, and step the scale by one notch with a cooldown so the
guest does not see a resolution flip every frame. Until that loop is measured,
shipping a guess would violate the no-invented-numbers rule.

## 5. Audio and host caches

Audio is mixed on its own `AudioManager` thread and handed to the cubeb sink in
fixed-size buffers, so the mixer is not on the frame path and does not gate
frame time on the devices this fork targets. Vectorising the voice mixer with
NEON is possible, but it optimises a thread that is not the bottleneck on a
title running at 22 fps because of GPU and TLS-style CPU work.

## What this change set actually alters

1. `renderer_relaxed_precision` — opt-in `RelaxedPrecision` on fragment FP32
   values, chain sources included, cache-salt aware, disabled wholesale for
   shaders that write `gl_FragDepth`.
2. Core pinning — four-core fast mask, widening on single-prime topologies, the
   guest CPU threads 0-2 and the GPU thread on that mask, guest core 3 left
   unpinned.

Everything else in the checklist is either already present in the tree (and
listed above with a file), scoped above with a concrete integration point and a
reason it is not in this change, or does not apply to a Dynarmic/NCE-based
emulator.

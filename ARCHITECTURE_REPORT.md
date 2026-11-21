# Sakura shader recompilation and Mali review

Date: 2026-11-19

## Investigation summary

Maxwell guest programs enter through `src/shader_recompiler/frontend/maxwell`, become shader IR,
run through `ir_opt`, and are emitted as SPIR-V by the Sirit backend. The Vulkan pipeline cache
deduplicates graphics and compute pipelines and can build cached pipelines on worker threads.
This tree already contains driver pipeline-cache persistence, async shader compilation, ASTC
format handling, Mali AFRC/AFBC paths, and direct presentation work. The changes here add IR
cleanup and runtime cache counters; they do not claim measured gains.

## IR changes

- `AlgebraicSimplificationPass` folds integer identities, same-operand comparisons, shifts by zero,
  min/max with equal inputs, floating negation pairs, logical identities, and equal select arms.
- `GvnPass` reuses eligible pure scalar results across blocks only when the representative
  dominates the use. Loads, image operations, mutable flag reads, pseudo operations, and undefined
  values are excluded.
- `CopyPropagationPass` collapses trivial phis and selects with equivalent arms.
- `CsePass` removes repeated global, local, storage, and shared loads within a block. Its key
  includes both address and descriptor arguments; writes, atomics, and barriers clear the table.
- `ControlFlowSimplificationPass` updates constant `If` successor edges when the merge has no
  leading phi. `DeadCodeEliminationPass` now iterates to a fixpoint.
- Passes are registered in `passes.h`/CMake and run after SSA and constant propagation in
  `TranslateProgram`; the existing renderer-debug verification remains in place.

A follow-up audit found and fixed two optimizer correctness bugs after a black screen was reported:
equal-arm Select simplification now compares its two value arms, and GVN orders register,
predicate, attribute, and patch operands by payload. Previously these cases could incorrectly
replace shader results.

## Vulkan and Mali findings

- Runtime cache counters report lookup hits, misses, build requests, and draws skipped while an
  asynchronous pipeline is still building. Skipped large draws remain a deliberate current policy;
  introducing a fallback pipeline or waiting can alter correctness or frame pacing and needs device
  testing.
- Graphics Pipeline Library support is present but controlled by `use_graphics_pipeline_library`,
  which defaults off. It remains off by default pending Mali measurements.
- Compute pipelines remain opt-in and the setting is described as Intel Vulkan only. No evidence in
  this tree establishes that enabling them is safe or faster on Mali.
- The existing AFRC path only requests compression on eligible single-sample 2D color targets of
  at least 256x256 pixels, skips storage images, and requests supported fixed rates. Existing
  Mali/experimental gates are preserved; no G615-specific rate or threshold is asserted here.
- `vk_graphics_pipeline.cpp` and `vk_compute_pipeline.cpp` pass `nullptr` for guest shader
  specialization info. Specializing Maxwell constant-bank values would multiply pipeline variants
  and cache keys. No demonstrated per-program invariant benefit was found, so specialization was
  not added.

## Build and validation

The Android ARM64 CMake/Ninja build is configured under
`src/android/app/.cxx/Release/k5x5l622/arm64-v8a`. The `shader_recompiler` and `video_core` targets
compiled successfully after correcting build errors in GVN and load CSE. The full `yuzu-android`
target also linked successfully after the follow-up optimizer fixes. The signed APK wrapper could
not start Gradle tasks: Gradle failed
with `Unable to establish loopback connection`, including on a retry with `--no-daemon`. Therefore,
the APK packaging/signing step and a `renderer_debug` run remain unverified. No Mali device build or
benchmark was available in this environment.

For a build with renderer debugging enabled, run a representative shader-heavy workload and confirm
that `VerificationPass` completes without an IR verification error. This is a validation procedure,
not a result claimed by this report.

## Device benchmark procedure

Use the same APK, game build, save state, graphics settings, thermal state, and repeatable scene for
each before/after run on the Motorola Edge 60 Fusion. Warm shader caches separately from cold-cache
runs. Record at least three runs and report median frame time; do not compare runs with different
thermal throttling or cache state.

Suggested collection:

1. `adb shell am force-stop <package>` before each launch and time launch-to-scene-ready.
2. Capture `adb shell dumpsys gfxinfo <package>` after the fixed scene interval.
3. Capture `adb shell dumpsys meminfo <package>` (or the project's `show_app_ram_usage` helper) at
   the same point.
4. Save emulator logs containing `[PipelineCache]` and shader warmup summaries.

| Metric | Before | After | Notes |
|---|---:|---:|---|
| Launch to scene ready (s) | — | — | Median of 3+ runs |
| Frame time / jank (gfxinfo) | — | — | Same scene interval |
| Process RAM (MiB) | — | — | Same point in scene |
| Pipeline cache hits / misses / builds / dropped draws | — | — | Emulator log |

No Mali hardware benchmark numbers are available for this change.

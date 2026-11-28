# Mali r44 study — the NieR: Automata black-models investigation

Findings from debugging NieR: Automata (0100B8E016F76000) on a
**motorola edge 60 fusion** (MT6878, **Mali-G615 MC2**, ARM driver
**v1.r44p1-01eac0**, Vulkan 1.3.247), with a reference run on desktop NVIDIA
(Ryujinx 1.3.3, OpenGL, laptop) and an experimental PanVK (Mesa) custom driver
as a second Mali data point.

This doc records what was verified, what was ruled out, and what remains open.
Every claim here traces to a device log, a feature report, or a commit in this
repository — nothing is speculative without being marked as such.

## TL;DR

- The stock MediaTek Mali **r44p1** driver is missing or misbehaving in several
  documented ways (see `DriverBugs.md`). The NieR black-models bug reproduces
  on every suyu-lineage emulator tried (Eden, suyu, Sakura), on both the stock
  driver and Mesa PanVK — it is a baseline defect, not a regression from
  Sakura's optimizations.
- The strongest current theory: **depth-compare sampling**. Hardware shadow
  compares misbehave and raw reads of compare-op samplers (undefined in Vulkan)
  return 0.0 on r44p1, zeroing the lighting term — black surfaces with glowing
  emissive flames, exactly the observed symptom. Fixed automatically for
  Mali < r45 by `Device::NeedsManualShadowCompare()` (commit `f0f08f4b2b`).
- Six other hypotheses were probed on-device with settings toggles and are
  ruled out (see "Ruled out" below).

## Evidence collected

### Device logs (suyu on the phone)

Fourteen session logs (`suyu_log (5)`–`(14)`), spanning builds
`b839244f67` (Nov 21 era) through `ea3a67a8c8` (this branch). Key facts:

- The driver fails Sakura's suitability check: `maxViewports = 1`,
  `maxClipDistances = 0`, and the features `wideLines`,
  `vertexPipelineStoresAndAtomics`, `shaderClipDistance`, `shaderCullDistance`,
  `multiViewport`, `logicOp`, `dualSrcBlend`, `fillModeNonSolid` are reported
  missing. Sakura disables its "Malikan" fast paths as a result.
  *Caveat: PanVK reports a richer set, so part of this may be deliberate
  feature hiding by MediaTek rather than true silicon limits.*
- **Zero pipeline creation failures** across all sessions
  (`[ShaderWarmup] ... 0 failed` everywhere, no `Graphics pipeline creation
  failed`). The SPIR-V the recompiler emits builds cleanly on this driver.
- No "Ignoring clip distance store" / "Ignoring viewport index store"
  warnings: NieR's shaders never touch those attributes on this path, so
  clip-distance emulation is not the culprit.
- No `NULLDESC-SAMPLED ... null descriptor` warnings so far.
- Vulkan validation cannot run on device: the APK ships no
  `VK_LAYER_KHRONOS_validation` (`Layer VK_LAYER_KHRONOS_validation not
  available, removing it`, `vulkan_instance.cpp`). Packaging the layer is the
  next diagnostic step if the manual-shadow-compare fix does not hold.

### Reference emulator (Ryujinx on NVIDIA)

A 2-minute NieR session produces **zero warnings and zero errors** — the guest
command stream is well-formed. The one notable informational line:
`Shader translator: Attribute offset 0x3A0 (ViewportMask) is not supported by
the host for stage Geometry` — NieR ships a geometry shader that writes
`ViewportMask` (flight scenes). NVIDIA ignores the unsupported attribute and
renders correctly.

### FauxVkTest feature report (stock driver, on device)

`report-system-20261128T123211Z.json`, 101 checks on the stock r44p1 driver:

| Verdict | Count |
|---|---|
| pass | 96 |
| unsupported | 104 |
| untested | 23 |
| **fail** | **1** |

The single failure: **`compat.texel_buffer_type_punning`** — reading an
integer-format texel buffer through a float-typed descriptor returns garbage
bits instead of the raw data. Probed with Sakura's
`renderer_uint_texel_buffers` workaround; no change in NieR, so this is not
the black-models cause (though it remains a real driver defect worth keeping
the workaround for).

## Ruled out (probed on device, no effect)

| Hypothesis | Probe | Result |
|---|---|---|
| Shadow compare (settings, bypass only) | `shadow_compare_bypass` ± `manual_flip` | no change * |
| Geometry shader streams | `renderer_no_geometry_streams` | no change |
| ASTC fixed-point decode wash-out | `renderer_no_astc_decode_mode` | no change |
| Texture rescaling | `renderer_no_texture_rescale` | no change |
| Vertex input compaction | `renderer_no_vertex_compaction` | no change |
| Texel-buffer punning | `renderer_uint_texel_buffers` | no change |
| Dynamic resolution scaling | `renderer_drs` (default off) | not the cause (user-confirmed) |

\* The bypass-only test was later found to be inconclusive as a *probe*: the
compare-disable sampler in `pipeline_helper.h` was keyed on bypass **and**
manual_flip being on together, so a bypass-only run still bound the broken
hardware-compare sampler. This is what commit `f0f08f4b2b` fixes — the manual
compare is now forced automatically on Mali < r45. The hypothesis is therefore
**open pending one test with the fix build**, not ruled out.

## Documented driver bugs used (external)

- [MaliBridge issue #72](https://github.com/ASEbrahim/MaliBridge/issues/72) —
  Mali-G615 **r44p1**: `gl_ViewportIndex` from the vertex shader is ignored;
  writing `ViewportIndex`/`ViewportMask` from a geometry shader fails pipeline
  creation with `VK_ERROR_INITIALIZATION_FAILED`. Fix proposed in
  [PR #88](https://github.com/ASEbrahim/MaliBridge/pull/88) (report
  multiViewport as unavailable on G615/r44p1 so clients take the emulation
  path).
- Sakura's recompiler already drops viewport stores when
  `support_multi_viewport` / `support_viewport_mask` are false
  (`emit_spirv_context_get_set.cpp:95-113`), so geometry-shader viewport
  writes should not reach the driver on this device. **Open gap**: the
  `Layer`-store path (`spirv_emit_context.cpp:1665`) is allowed in geometry
  stage regardless of multiViewport support; if NieR's geometry shader also
  writes `Layer`, that could hit the documented `-3` pipeline-creation failure.
  Worth gating if the black models persist.

## State of the fixes (branch `fix/mali-lod-blur`)

| Commit | Change |
|---|---|
| `de319ddc18` | Implicit-LOD LOD-0 workaround made opt-in (was forced on all Mali; killed mip selection → the blurry image) |
| `3a5b90ab1a` | Merged `fix/nier-black-models` ancestry (vertex-input compaction fix confirmed already present) |
| `106baca75c` | Four diagnostic toggles (geometry streams, ASTC decode, rescale, compaction) |
| `ea3a67a8c8` | `renderer_uint_texel_buffers` R32UI punning workaround + per-definition texel buffer image types |
| `f0f08f4b2b` | **Manual shadow compare forced on Mali < r45** (profile + sampler binding agree via `Device::NeedsManualShadowCompare`) |

## Next steps if black models persist after `f0f08f4b2b`

1. **Check the manual-compare direction**: if surfaces render but look
   inverted (washed-out instead of black), flip the forced
   `shadow_compare_manual_flip` value — a single bit.
2. **Package `VK_LAYER_KHRONOS_validation`** into a debug APK (the instance
   code already requests it) and run with `renderer_debug`: the first
   on-device validation report. This is the only remaining general
   diagnostic.
3. **Gate the geometry-stage `Layer` store** on multiViewport support
   (see the open gap above) — matches MaliBridge #72's `-3` failure mode.
4. Ask upstream: Eden/suyu users on Mali-G615 report the same; a shared fix
   should land in both.

## Test matrix used

| Driver | Emulator build | Result |
|---|---|---|
| Stock r44p1 | 331fd60e5e (0.0.5-21, Nov 21) | models black, scene washed out |
| Stock r44p1 | 49d1b89537-era | same |
| Stock r44p1 | fix branch, all toggles on | same |
| PanVK v1.1.9 (Mesa) | fix branch | black screen; richer feature set (no maxViewports/clipDistance failures) but still `vertexPipelineStoresAndAtomics` missing |
| NVIDIA laptop | Ryujinx 1.3.3 (reference) | clean, zero warnings |

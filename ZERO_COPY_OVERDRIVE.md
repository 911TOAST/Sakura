# Project Zero-Copy — direct-to-swapchain presentation

Date: 2026-10-26
Device target: Motorola Edge 60 Fusion (MediaTek MT6878, Mali-G615 MC2)
Build: Sakura Optimized, arm64-v8a, `armv8.2-a+dotprod+fp16+lse`

## The short version

This is not a settings pack. The Mali/Malikan display path no longer creates an
intermediate full-screen image and copies it into the swapchain. The window
composition render pass now writes **directly into the image acquired from
Android's Vulkan WSI**, ends in `VK_IMAGE_LAYOUT_PRESENT_SRC_KHR`, and hands
that same image to the compositor.

The old path still exists, unchanged, for every driver and every renderer mode
that is not Mali + Malikan/native-GPU.

Put differently: for the Mali display path, the copy command has been deleted
from the source, not made faster. The frame Android displays is the render
target the GPU just finished writing.

## What was removed from every presented frame

| Work item | Before | After (Mali + Malikan) |
|---|---|---|
| Intermediate frame image | one full-screen RGBA image per swapchain buffer (Mali typically reports 4-6; the old code capped at 7) | none |
| Frame image view + framebuffer | one per intermediate image | one view + framebuffer per **swapchain** image (no extra memory) |
| Full-screen copy/blit | `vkCmdCopyImage` (or `vkCmdBlitImage` when scaling) | none |
| Pre/post copy barriers | 2 `vkCmdPipelineBarrier` calls | none |
| Present thread | dedicated `VulkanPresent` thread + queue + fences | no thread; direct WSI call |
| Extra queue submission | render submission + separate copy submission | one submission |
| Present semaphore round trip | render -> copy submission -> present | render submission signals the WSI semaphore directly |
| Composition attachment layout | `GENERAL` (intermediate image) | `COLOR_ATTACHMENT_OPTIMAL` (swapchain image) |

The boilerplate `VkAttachmentReference` in the original present render pass used
`VK_IMAGE_LAYOUT_GENERAL` for the colour attachment. On a tile-based Mali GPU
that layout can disable framebuffer compression and transaction elimination.
The direct path uses `VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL` for the subpass
and still finishes in `PRESENT_SRC_KHR`, so the compression-friendly layout and
the zero-copy hand-off coexist.

The display frame is now:

1. `vkAcquireNextImageKHR` hands out a swapchain image and its acquire
   semaphore.
2. The composition render pass renders into a view of that image,
   `UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL` for the subpass and
   `-> PRESENT_SRC_KHR` at the end.
3. The render submission waits on the acquire semaphore and signals the frame's
   own binary semaphore.
4. `vkQueuePresentKHR` waits on that semaphore. No intermediate allocation, no
   copy, no extra barrier pair, no second submit.

## Why this is a real architectural change

A yuzu-family emulator normally composites the Switch layers into an
intermediate image because the swapchain image format/usage and the presentation
queue are treated as a separate concern. That costs, per frame:

- a full-screen colour write during composition,
- a full-screen read plus a full-screen write for the copy,
- two layout barriers,
- a hand-off between the render submission and the present submission,
- a context switch to the present thread.

Zero-copy removes the copy and the hand-off. The composition still has to write
the screen once; it simply writes it into the buffer Android will display.

### VRAM removed at 1220 x 2712 (Edge 60 Fusion panel)

- One RGBA8 frame: 13,234,560 bytes (12.62 MiB)
- Six intermediate frames: 79,407,360 bytes (**75.72 MiB**)
- Seven intermediate frames (desktop / larger swapchains): 92,641,920 bytes
  (88.35 MiB)

At 1920 x 1080 the same six images are 47.46 MiB.

That memory is not merely "allocated less". Those images were render targets,
so their allocation and their per-frame storage traffic disappear from the
working set. On a phone that is already sharing RAM with the game, 75 MiB of
avoided GPU pressure is a concrete win even before the copy is counted.

### Copy bandwidth removed

At 1220 x 2712, one copy reads and writes 26,469,120 bytes (25.24 MiB) per
frame:

- at 33.3 FPS: 881 MB/s of memory traffic removed
- at 60 FPS: 1.59 GB/s of memory traffic removed

At 1920 x 1080 the copy is 16.59 MB per frame (552 MB/s at 33.3 FPS, 995 MB/s
at 60 FPS).

This is why the change can matter even when the GPU is already saturated: the
copy consumes memory bandwidth and fixed-function copy time without producing a
single new pixel.

### Display-path render-target traffic: 3 screen passes -> 1

Consider only the pixels of the final display image:

- old path: composition writes the intermediate image (1 write), the copy reads
  it (1 read) and writes the swapchain image (1 write) = **3 full-screen
  passes**,
- direct path: composition writes the swapchain image = **1 full-screen pass**.

At 1220 x 2712 that is 39.7 MB of render-target/copy traffic per frame reduced
to 13.2 MB. At 60 FPS the display path alone stops moving roughly **1.59 GB/s**
that used to exist only to duplicate a finished image. Caches can absorb part of
that in the old path, so this is an upper-bound estimate of the DRAM saving, but
the command and barrier work is removed unconditionally.


## Correctness gates and fallback

Direct presentation is enabled only when **all** of these are true:

- the driver is ARM Mali (`VK_DRIVER_ID_ARM_PROPRIETARY` / vendor `0x13B5`),
- the renderer is in Malikan mode (Malikan backend or native GPU mode),
- the swapchain either uses the same format for images and views, or was
  created with `VK_KHR_swapchain_mutable_format`.

If any condition fails, `PresentManager` constructs the original intermediate
image + copy path, including the original present thread logic. The fallback
path was not rewritten.

The direct path also guards:

- acquire/recreate loops for `VK_SUBOPTIMAL_KHR`, `VK_ERROR_OUT_OF_DATE_KHR`,
  and `VK_ERROR_SURFACE_LOST_KHR`,
- full GPU idle before destroying per-image views/framebuffers on swapchain
  recreation,
- per-swapchain-image semaphores, so an image is never presented while its
  render semaphore is still owned by a previous presentation,
- the present render pass is selected per frame, so applet/capture frames keep
  the ordinary `GENERAL` render pass and its download barrier remains valid.

## Code map

- `src/video_core/renderer_vulkan/vk_present_manager.{h,cpp}` — direct frame
  acquisition, per-image views/framebuffers, direct present, swapchain
  recreation, semaphore hand-off.
- `src/video_core/renderer_vulkan/present/window_adapt_pass.{h,cpp}` — present
  render pass ending in `PRESENT_SRC_KHR`, `COLOR_ATTACHMENT_OPTIMAL` subpass,
  and present-compatible pipeline variants.
- `src/video_core/renderer_vulkan/present/util.{h,cpp}` — render pass final
  layout and subpass layout parameters.
- `src/video_core/renderer_vulkan/vk_blit_screen.cpp` — selects the present
  render pass only for direct frames.
- `src/video_core/renderer_vulkan/renderer_vulkan.cpp` — passes the WSI acquire
  semaphore into the scheduler flush.
- `src/video_core/renderer_vulkan/vk_swapchain.{h,cpp}` — per-image acquire
  semaphores, mutable swapchain format, 1:1 copy fast path.
- `CMakeLists.txt` — `armv8.2` preset.
- `src/android/app/build.gradle.kts` — optimized-flavor codegen flags.

## CPU codegen in the same build

The optimized flavor now compiles with:

- `-march=armv8.2-a+dotprod+fp16+lse` — LSE atomics remove LL/SC helper calls
  and runtime dispatch on the Cortex-A78/A55 cluster; dot-product and FP16 are
  available to the compiler.
- `-mtune=cortex-a78`
- `-O3`, ThinLTO, `-fomit-frame-pointer`, `-fno-math-errno`,
  `-fno-stack-protector`, `-funroll-loops`, vectorizer flags.

This preset raises the minimum CPU to ARMv8.2. It is intentional for MT6878
and other A78/A55-class devices. It is not for ARMv8.0-only hardware; the
`mainline` and `legacy` flavors keep their original baseline.

## Build verification

Verified on 2026-10-26 on the build machine:

- Native build: `ninja -j 8` in the configured `.cxx` directory completed
  **1,358/1,358 targets**, exit code 0, in 41m 58s
  (`build/ninja-zerocopy.log`).
- Packaging: `:app:assembleOptimizedRelease` — **BUILD SUCCESSFUL in 20m 1s**,
  exit code 0 (`build/gradle-package-zerocopy.log`).
- All modified translation units compiled and linked into the optimized
  `arm64-v8a` library:
  `present/util.cpp`, `present/window_adapt_pass.cpp`, `vk_blit_screen.cpp`,
  `vk_present_manager.cpp`, `renderer_vulkan.cpp`, `vk_swapchain.cpp`.
- Compile flags confirmed in the generated `build.ninja` for the optimized
  target: `-march=armv8.2-a+dotprod+fp16+lse -mtune=cortex-a78`,
  `-fno-stack-protector`, `-O3`, ThinLTO.
- APK: `artifacts/Sakura-optimized-zerocopy-overdrive.apk`
  - package `org.suyu.suyu_emu.optimized`, versionCode `34139150`,
    versionName `0.0`
  - size `22,709,096` bytes
  - SHA-256 `6E0A70EEFCA388E37F8C00899D81AC8854A3DEB770A92CD8E3CBBA2DFB1FED6A`
  - `zipalign -c 4`: pass
  - `zipalign -c -P 16 4`: pass; ELF `LOAD` segments are aligned to `0x4000`
    (16 KB), so the library is ready for 16 KB page-size devices
  - `apksigner verify`: pass (v2 scheme; signer `CN=suyu Mali`, certificate
    SHA-256 `8DF1F82D23AB79FE8287ADD52270A15E0BF93DA1F18EE5117D65FB282E44C91C`)
- Native library inside the APK: `lib/arm64-v8a/libyuzu-android.so`
  - size `27,798,856` bytes (AGP-stripped)
  - SHA-256 `F2F9D9C0F0F5400F9EB65C9DDC93EFB1327EC7E0A46A878F4ADE6AFB8B410168`
  - GNU build ID `f1b7f043bffd07a61ad5c79f2a54a41a198afcff`, identical to the
    freshly linked unstripped library
    (SHA-256 `B8A00D5D381813D560A17957B98BDF06D6FAAA1A9715BC9899D39A269335289F`),
    proving the packaged binary is the one produced by this build.
  - contains the literal string
    `[Present] Zero-copy direct presentation enabled: {} swapchain images, no intermediate frame images, no swapchain copy`,
    proving the direct path is present in the final stripped binary, not just in
    a stale object file.
- `Settings`/log check: the binary also contains `[Swapchain] format=`, which
  reports whether mutable-format support allowed direct presentation.

No Android device is attached to this machine, so this is a build and binary
verification, not an on-device performance measurement.

### Signing note

The APK is signed with the same `CN=suyu Mali` release key
(`secrets/suyu-release.keystore`) as `installed.apk` and
`artifacts/Sakura-optimized-release.apk`, so it upgrades those in place with
`adb install -r`. The `performance-overhaul`, `smoothness-overhaul`, and
`spirv-cache` artifacts are signed with the Android Debug certificate
(SHA-256 `9D09B2001BE0DDE93EA0444C536B72270FAB3EB1A2FCCFD56E1A3C46CC0D7AF1`).
If one of those is currently installed, uninstall
`org.suyu.suyu_emu.optimized` once before installing this APK.

## What this does NOT claim

- No Android device is attached to this machine (`adb devices` is empty), so
  there is **no measured FPS, frametime, or thermal number** in this report.
  Claiming a specific percentage would be fabrication.
- The deterministic savings are architectural: the copy, its barriers, the
  intermediate images, the second queue submission, and the present-thread hop
  are gone. The FPS effect depends on how much of the frame was
  copy/composition/present-bound. A game that is purely shader-bound will show
  little change; a game that is bandwidth- or present-limited will show more.
- Direct presentation assumes the WSI image can be used as a colour attachment.
  That is guaranteed by the swapchain usage flags and checked by the format
  gate; if a driver violates it, the app will fail at swapchain creation rather
  than silently corrupt frames. Switching off Malikan/native GPU restores the
  old path.
- The `armv8.2` preset will crash with SIGILL on pre-ARMv8.2 CPUs. Use the
  mainline/legacy flavor on those devices.

## How to measure on the device

1. Install:
   `adb install -r artifacts/Sakura-optimized-zerocopy-overdrive.apk`
   If the installed build came from the debug-signed
   `performance-overhaul`/`smoothness-overhaul`/`spirv-cache` artifacts, first
   run `adb uninstall org.suyu.suyu_emu.optimized`. Builds signed with the
   `CN=suyu Mali` key (`installed.apk`, `Sakura-optimized-release.apk`) upgrade
   with `-r` directly.
2. Enable Malikan/native GPU mode and use the same save, route, renderer
   settings, and resolution as the baseline.
3. Record at least 60 seconds with the same scene; capture average FPS, 1% low
   frametime, GPU busy, and package power/thermal.
4. Compare against `artifacts/Sakura-optimized-performance-overhaul.apk` or the
   previous build. Those are debug-signed, so switching between them and this
   APK requires an uninstall/reinstall.
5. Optional: Perfetto/simpleperf trace. Look for the disappearance of
   `PresentManager::CopyToSwapchainImpl`, `vkCmdCopyImage`, and the
   `VulkanPresent` thread from the steady-state trace.
6. Check `adb logcat` for
   `[Present] Zero-copy direct presentation enabled` and
   `[Swapchain] format=... mutable=...`.

## Rollback

Restore the files in `build/backup-zerocopy/`, or install the previous APK:
`artifacts/Sakura-optimized-performance-overhaul.apk`.

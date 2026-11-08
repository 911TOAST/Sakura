# Sakura optimized build — performance reports

Date: 2026-10-24 (CPU pass), updated 2026-11-07 (shader warmup + device-wide pipeline cache)

## 2026-11-07 update — shader warmup and cross-title GPU pipeline cache

Files: `src/video_core/renderer_vulkan/vk_pipeline_cache.{h,cpp}`,
`src/video_core/vulkan_common/vulkan_wrapper.{h,cpp}`.
Full description: `SHADER_WARMUP_AND_CACHE.md`.

- The Vulkan driver pipeline cache (the driver's compiled device code) is now a
  **device-wide** cache, `vulkan_pipelines_global_<salt>.bin`, loaded at boot and
  merged with the per-title `vulkan_pipelines.bin` through
  `vkMergePipelineCaches`. A pipeline compiled for one game is reused by the
  next game on the same device/driver. The salt prevents sharing blobs between
  different drivers. The global file is capped at 128 MiB.
- Boot-time warmup now reports what it actually did:
  `[ShaderWarmup] N pipelines ready in T ms: X from disk, Y compiled, Z failed`.
  The `Y compiled` counter is the number to drive to zero per title.
- Duplicate pipeline keys in the append-only cache file are skipped before they
  reach a worker, so a pipeline is never created twice in one boot.
- Fixed a malformed format string in the driver-cache load log (`"{}"` missing),
  which could throw from inside cache loading with fmt's checked strings.

Deterministic effect: fewer `vkCreateGraphicsPipelines` calls at boot and across
titles. No device is attached, so no FPS/frametime claim is made; the warmup
log line is the measurement hook to use on hardware.

## 2026-10-27 update — block-linear segment copies (latest build)

- APK: `artifacts/Sakura-optimized-deswizzle-segments.apk`
- Package: `org.suyu.suyu_emu.optimized` (arm64-v8a only)
- Version: `versionCode 34150824`, `versionName 0.0`
- Size: `22,711,780` bytes
- SHA-256: `90EB419CCC5B8D2F938533FC3D9F6444DE2F2244BCC571585FA84BA4A8305AED`
- Signer: `CN=suyu Mali, OU=emulator, O=suyu, ST=none, C=US` (release key,
  APK Signature Scheme v2)
- Native library: `lib/arm64-v8a/libyuzu-android.so`, `27,805,384` bytes,
  SHA-256 `3A7897EB5AA17C9E6736C45009C7217FFAA0FE3086BB3CDA00AF0DB2C41CDB9B`
- Build: `:app:assembleOptimizedRelease` **BUILD SUCCESSFUL in 4m 12s**,
  exit code 0; `zipalign -c 4` pass; `apksigner verify` pass

### What changed

Only `src/video_core/textures/decoders.cpp` changed. The CPU block-linear
swizzler now moves whole 16-byte segments instead of one pixel at a time.

The block-linear swizzle keeps the low four bits of the byte offset inside a
GOB contiguous: `SWIZZLE_X_BITS = 0b100101111` starts with `0b1111`. A 16-byte
aligned chunk is therefore always a contiguous 16-byte run in the tiled image.
Inside one GOB row the four segments land at the fixed offsets 0, 32, 256 and
288; `offset_x`, `offset_y` and `offset_z` select the GOB, row and slice.

`SwizzleImpl` now:

- hoists `slice * pitch * height + line * pitch` and
  `offset_z + offset_y + swizzled_y` out of the column loop;
- handles a full 64-byte GOB with four fixed 16-byte copies and one shifted GOB
  base;
- handles partial trailing GOBs with `SegmentSwizzledX` (bits 4 and 5 of `x`);
- leaves at most 15 bytes of each row, and the 3/6/12-byte block formats that
  cannot be split into whole 16-byte pixels, on the previous scalar path.

`SwizzleSubrectImpl` uses the same scheme. It first advances to the next
16-byte boundary, copies full segments, then handles the tail. The linear side
of a subrect is tightly packed, so it stays indexed by `column * bytes_per_pixel`
rather than by the tiled `origin_x + column`.

This is a technique transfer from nvn/nxnative's texture-copy work, not a code
port. nxnative is an iOS/Metal native-execution project and does not contain an
emulator; its `nv-objects.md` describes the 16-byte segment observation and the
associated deswizzle speedup. Suyu performs the same block-linear layout on the
CPU for the non-accelerated display fallback (`Layer::UpdateRawImage`), texture
uploads, Maxwell DMA and the software blitter.

### Expected effect

The fallback display path deswizzles an entire RGBA8 frame on the CPU every
present (8.3 MB at 1920 x 1080). `UnswizzleTexture` pre-merges pixels up to
16 bytes, so the old loop already moved 16 bytes per iteration, but paid the
`pdep`/`incrpdep` and offset arithmetic for every segment. The new GOB loop is
four `ldr q0`/`str q0` pairs plus a handful of address adds per 64 bytes; the
per-16-byte instruction count is roughly 2x to 3x lower (arithmetic claim, not
a benchmark). Subrect callers (Maxwell DMA, software blitter, engine upload)
do not get the 16-byte pixel merge, so their copy count drops 4x for RGBA8 and
8x for 16-bit-per-pixel formats.

### Verification

- Differential model against the previous implementation: **783,860 cases,
  0 mismatches**. Coverage: bytes-per-pixel 1/2/3/4/6/8/12/16, width 1-400,
  height 1-120, depth 1-6, block height 0-5, block depth 0-5, stride
  alignments log2 0-4, structured and random subrect origins/extents, both
  directions.
- Full Android `arm64-v8a` Ninja build: success. Disassembly of the linked
  `libyuzu-android.so` shows the new `ldr q0`/`str q0` segment loop inside
  `Tegra::Texture::UnswizzleTexture`.
- APK: `zipalign -c 4` pass, `apksigner verify` pass (v2), packaged native
  library hash matches the build output.

**No Android device is attached**, so there is no measured FPS, frametime,
power or thermal result. The effect above is derived from the instruction
stream and the amount of copied data, not from a device benchmark.

### Signing note

This APK is signed with the release key (`CN=suyu Mali`), like
`artifacts/Sakura-optimized-release.apk`, and unlike the debug-signed
`Sakura-optimized-zerocopy-overdrive.apk`. If a debug-signed optimized build is
installed, uninstall `org.suyu.suyu_emu.optimized` once before installing this
one; upgrades within the release-signed family work normally. The zero-copy
display changes from 2026-10-26 are still included.

## 2026-10-26 update — zero-copy display path

- APK: `artifacts/Sakura-optimized-zerocopy-overdrive.apk`
- Package: `org.suyu.suyu_emu.optimized` (arm64-v8a only)
- Version: `versionCode 34138908`, `versionName c0dd2ff554`
- Size: `22,709,100` bytes
- SHA-256: `C88CC98ADB5B16C06C34A64123814A6D4ED34E0F6A13E6F1EE42B37825DFF1F4`
- Signer: `C=US, O=Android, CN=Android Debug`
- Native library: `lib/arm64-v8a/libyuzu-android.so`, SHA-256
  `F2F9D9C0F0F5400F9EB65C9DDC93EFB1327EC7E0A46A878F4ADE6AFB8B410168`
- Build: `:app:assembleOptimizedRelease` **BUILD SUCCESSFUL in 40m 36s**,
  exit code 0; `zipalign -c 4` pass; `apksigner verify` pass
- The final stripped library contains
  `[Present] Zero-copy direct presentation enabled`, so the new display path is
  in the shipped binary.

### What changed in the display path

On ARM Mali with Malikan/native GPU, `PresentManager` now renders the window
composition pass directly into the image acquired from Android's Vulkan WSI.
The pass ends in `VK_IMAGE_LAYOUT_PRESENT_SRC_KHR`, and that same image is
handed to `vkQueuePresentKHR`. The previous intermediate full-screen image,
the full-screen `vkCmdCopyImage`/blit, its two barriers, the separate copy
submission, and the present-thread hop are not executed on this path.

Deterministic savings at 1220 x 2712 (reference panel):

- `75.72 MiB` of intermediate frame images (six swapchain buffers) no longer
  allocated;
- `25.24 MiB` of copy traffic removed per presented frame
  (`1.59 GB/s` at 60 FPS);
- display-path render-target traffic drops from 3 full-screen passes
  (composition write + copy read + copy write) to 1.

The fallback path for non-Mali drivers and non-Malikan renderer modes is
unchanged. Full architecture, gating, code map, and rollback:
`ZERO_COPY_OVERDRIVE.md`.

**No device is attached to this machine**, so there is still no measured FPS,
frametime, power, or thermal result. The numbers above are arithmetic on the
removed work, not benchmark claims.

### Signing / upgrade note

This APK uses the same debug certificate as the `performance-overhaul` and
`smoothness-overhaul` artifacts. It is **not** the same key as
`artifacts/Sakura-optimized-release.apk` (`CN=suyu Mali`). If that build is
installed, uninstall `org.suyu.suyu_emu.optimized` once before installing this
one; otherwise `adb install -r` upgrades work normally within the debug-signed
family.

## CPU-side pass (2026-10-24 artifact)

### What was built

> Historical record: the path below was later overwritten by a subsequent build.
> The current `artifacts/Sakura-optimized-release.apk` is `versionCode 34133218`,
> signed by `CN=suyu Mali`. The size/hash/version in this section describe the
> 2026-10-24 capture, not the file currently at that path.

- APK: `artifacts/Sakura-optimized-release.apk`
- Package: `org.suyu.suyu_emu.optimized` (arm64-v8a only)
- Version: `versionCode 34124794`, `versionName c0dd2ff554`
- Size: `23,105,440` bytes
- SHA-256: `887CAE375B78A326009AE526662E0E7A747F5C305DAF662B67086FD969749D2A`
- Signer: `CN=suyu Mali, OU=emulator, O=suyu, ST=none, C=US`
- Native library inside the APK matches the freshly stripped build:
  `lib/arm64-v8a/libyuzu-android.so`, SHA-256
  `EAEE4101DDC00EA75756CD1B291F43EC000D391E043C5CBC5EF2CC680CD420EC`

A ready-to-install comparison build from 2026-10-22 is also present:

- `artifacts/Sakura-optimized-release-baseline-2026-10-22.apk`
- `versionCode 34107362`, SHA-256
  `0BB524AEA02D9B6A489DA731B9817F77B88ADBF2922475B966B05C2574A0221E`

## CPU-side changes in this pass

### 1. Native `svcGetSystemTick` fast path (NCE)

Files: `src/core/arm/nce/patcher.cpp`

When the guest executes `SVC #0x1E` and all of the following are true:

- NCE is active (39-bit process with fastmem),
- multicore is enabled,
- fast CPU time is `Off`,
- `sync_core_speed` is disabled,

the patcher now emits the existing `MRS CNTVCT_EL0` + fixed-point scaling
handler instead of the full SVC trampoline. This avoids:

- the context lock,
- saving/restoring all 31 GPRs and 32 128-bit vector registers,
- returning to the host and re-entering the guest.

In this timing configuration `CoreTiming::GetClockTicks()` is exactly the
scaled host counter, so the value is identical to `svcGetSystemTick`.

If any of the conditions change, the normal trampoline is used. The decision is
made when the module is patched; restart the game after changing CPU timing
settings.

### 2. Inline `svcGetSystemTick` in Dynarmic (A64 fallback)

Files:

- `src/dynarmic/src/dynarmic/interface/A64/config.h`
- `src/dynarmic/src/dynarmic/frontend/A64/translate/a64_translate.h`
- `src/dynarmic/src/dynarmic/frontend/A64/translate/impl/a64_exception_generating.cpp`
- `src/dynarmic/src/dynarmic/backend/arm64/a64_address_space.cpp`
- `src/core/arm/dynarmic/arm_dynarmic_64.cpp`

Added a `fast_svc_get_system_tick` translation option. Under the same
multicore/default-timing conditions, the A64 translator emits
`GetCNTPCT()` into guest `X0` instead of `CallSupervisor(0x1E)`. This removes a
JIT exit and supervisor-call round trip for every tick poll.

The option is disabled by default and is only enabled by the suyu frontend when
`GetCNTPCT()` is known to match the guest's `svcGetSystemTick`. The x64
Dynarmic backend is unchanged.

### 3. Barrier-free host counter reads

File: `src/common/cpu_features.cpp`

`WallClock::GetUptime()` no longer executes `DSB ISH` before and after
`MRS CNTVCT_EL0`. The counter is globally coherent and the barriers only
serialized the host pipeline. This path is used by every guest tick read,
context switch, and host-time query.

### 4. `CoreTiming::GetClockTicks()` default-timing fast path

File: `src/core/core_timing.cpp`

When fast CPU time is `Off` and `sync_core_speed` is disabled, the function now
returns the counter directly. The floating-point overclock/speed-limit path is
only entered when one of those settings is active. A 100% speed limit is also
treated as the identity instead of performing a float divide.

### 5. Scheduler priority queue bitmask

File: `src/core/hle/kernel/k_priority_queue.h`

Replaced the per-core `std::bitset<64>` scans with a `u64` bitmask and
`std::countr_zero`. `GetFront()` and `GetNextSet()` now find the highest-priority
ready thread with a single instruction instead of a loop over bits.

### 6. Removed the unused fiber rewind stack

Files: `src/common/fiber.cpp`, `src/common/fiber.h`

`Common::Fiber` allocated a second 2 MiB stack (`rewind_stack`) plus
`rewind_context`, `rewind_point`, and `rewind_stack_limit`. `SetRewindPoint()`
had no callers. Removing these members halves the per-fiber stack allocation and
zero-initialization cost. Guest threads use fibers, so this reduces memory
pressure and page-fault work on memory-constrained Android devices.

### 7. Cached address-space bound in `Memory::Impl`

File: `src/core/memory.cpp`

`SetCurrentPageTable()` now caches `1ULL << page_table.GetAddressSpaceBits()`.
`GetPointerImpl()` and the region operations use the cached bound instead of
recomputing the shift on every call. This is on the HLE read/write and
rasterizer fast paths.

## Build verification

- Full Android `arm64-v8a` Ninja build: **success**, `ninja: no work to do` after
  the last object was linked.
- Gradle `:app:assembleOptimizedRelease`: **BUILD SUCCESSFUL**.
- APK signature verification with `apksigner`: **success**.
- APK native library hash matches the freshly stripped build output.
- No Android device is connected (`adb devices` is empty), so no FPS,
  frametime, thermal, or regression measurements were possible.

## How to measure on a Mali device

1. Install the new build:
   `adb install -r artifacts/Sakura-optimized-release.apk`
2. Confirm the default CPU timing settings:
   multicore on, fast CPU time `Off`, sync core speed off, CPU accuracy
   `Unsafe` (the performance profile sets the last one).
3. Use a repeatable scene (same save, same route, same renderer settings) and
   record average FPS and 1% low frametime for at least 60 seconds.
4. Compare against the baseline APK. The baseline has a lower version code, so
   install it with `adb install -r -d <baseline.apk>` or uninstall the new build
   first.
5. If possible, capture a Perfetto/simpleperf trace and compare time in:
   - `CoreTiming::GetClockTicks`
   - the NCE SVC trampoline
   - `KPriorityQueue::GetFront` / `GetNext`
   - `Memory::Impl::GetPointerImpl`
6. Watch `adb logcat` for crashes or SVC/timing warnings.

## Important limitations

- The 30% target cannot be guaranteed or verified without the target device and
  game. The actual gain depends on how much time the game spends in tick
  polling, SVC entry/exit, scheduler queue scans, and HLE memory access. A
  GPU-bound scene will see little benefit from CPU-side changes.
- The NCE fast path requires NCE (39-bit + fastmem), multicore, and default
  timing. The Dynarmic fast path requires Dynarmic (48-bit or no fastmem),
  multicore, and default timing. Otherwise the previous paths are used.
- CPU timing changes are applied at process/module load. Restart the game after
  changing `fast_cpu_time`, `sync_core_speed`, or `use_multi_core`.
- The Dynarmic changes are in `src/dynarmic`, which is the copy used by the
  build. The unused `externals/dynarmic` submodule was left untouched.

## Rollback

Use `artifacts/Sakura-optimized-release-baseline-2026-10-22.apk`, or revert the
files listed above. The working tree is not committed; the repository remains at
`c0dd2ff554` with the performance work applied on top.

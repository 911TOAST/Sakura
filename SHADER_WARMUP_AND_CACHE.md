# Shader warmup and GPU pipeline cache

Date: 2026-11-07
Renderer: Vulkan (Malikan / native GPU path)
Files touched: `src/video_core/renderer_vulkan/vk_pipeline_cache.{h,cpp}`,
`src/video_core/vulkan_common/vulkan_wrapper.{h,cpp}`

## What was already there

Suyu already had two layers of shader caching, and both are used by this build:

| Layer | File | Contents |
|---|---|---|
| Guest shader cache | `<shader_dir>/<title id>/vulkan.bin` | Guest Maxwell shader hash + pipeline state key + SPIR-V + serialized reflection (`Shader::Info`) + FNV checksum |
| Driver pipeline cache | `<shader_dir>/<title id>/vulkan_pipelines.bin` | Opaque `VkPipelineCache` blob produced by the GPU driver |

Both are loaded at boot by `PipelineCache::LoadDiskResources()` and the cached
pipelines are created on the pipeline worker threads while the game is still on
the loading screen. That is the warmup: on Mali/Adreno,
`vkCreateGraphicsPipelines()` is where cached SPIR-V is turned into device code,
so doing it here removes the first-use compile stutter from the frame loop.

The driver cache existed, but it had three problems:

1. it was **per title**, so a pipeline compiled in one game was recompiled in
   the next game even on the same device and driver;
2. it was only written at the end of the loading screen and in the destructor,
   and its size / effectiveness was never reported;
3. `LoadDiskResources()` re-built **duplicate entries** in the append-only cache
   file, which is pure wasted driver time at boot.

## What this change adds

### 1. Device-wide driver pipeline cache (cross-title reuse)

The driver pipeline cache is a property of the device/driver, not of the game.
`LoadDiskResources()` now:

1. creates the `VkPipelineCache` from
   `<shader_dir>/vulkan_pipelines_global_<salt>.bin` (the device-wide cache),
2. creates a second, temporary `VkPipelineCache` from the per-title
   `vulkan_pipelines.bin`,
3. merges the per-title entries into the device-wide cache with
   `vkMergePipelineCaches`.

The merged cache is what every pipeline in the session is created with, so a
pipeline compiled while playing title A is available to title B on the next
boot. It is written back to both files after the loading screen and again when
the renderer is shut down.

`salt` is the existing `shader_cache_salt`: a hash of the GPU model name, driver
version, Vulkan API version, `sizeof(Shader::Info)` and the reflection
serialization version. Two different drivers (for example stock vs. a custom
adrenotools driver) therefore never share a cache file, which keeps the blob
inside the vendor/device constraints of `VkPipelineCache`.

The global file is capped at 128 MiB. If the merged cache grows past that, the
global file is left untouched (not truncated) and a warning is logged; the
per-title file is still written normally.

### 2. Warmup accounting and logging

`LoadDiskResources()` times the whole warmup pass and logs one line:

```
[ShaderWarmup] 312 pipelines ready in 8412 ms: 296 from disk, 16 compiled, 0 failed
```

- `from disk` — a cached SPIR-V/reflection payload was valid and only
  `vkCreateShaderModule` + `vkCreateGraphicsPipelines` ran for it;
- `compiled` — a cache entry was missing or invalid and the guest shader was
  translated and compiled again (this is the number to drive to zero);
- `failed` — the pipeline could not be created at all.

A cold boot logs `[ShaderWarmup] Cold start: no cached pipelines for this
title`. Use these numbers to measure whether a cache actually helps on a given
device instead of guessing.

The driver cache writer now also logs the number of bytes it wrote:

```
Vulkan driver pipelines cached at: .../vulkan_pipelines_global_<salt>.bin (1834212 bytes)
```

### 3. Duplicate-entry skip at boot

The pipeline cache file is append-only, so the same pipeline key can appear
more than once (interrupted build, cache version bump, a pipeline that was
serialized twice). Loading used to create that pipeline once per entry. The
loader now keeps `ankerl::unordered_dense::set` of compute and graphics keys and
skips a duplicate before it is handed to a worker. Nothing is created twice, and
the progress total reflects unique pipelines only.

### 4. Bug fix in the cache load path

`LoadVulkanPipelineCache()` logged the file path with a format string that had
no `{}` placeholder:

```cpp
LOG_INFO(Render_Vulkan, "Loaded Vulkan driver pipeline cache: ",
         Common::FS::PathToUTF8String(filename));
```

With fmt's checked format strings this is at best a dropped argument and at
worst a thrown `format_error` from inside cache loading. It is now a proper
`"{}"` format.

## Warmup flow after this change

```
boot
 └─ RendererVulkan created, VkDevice ready
     └─ LoadDiskResources(title_id)                 [loading screen]
         ├─ load global driver cache  ─────────────┐
         ├─ load per-title driver cache            │
         ├─ vkMergePipelineCaches(global, title) ──┘
         ├─ read vulkan.bin (SPIR-V + reflection)
         ├─ for each unique pipeline key:
         │    ├─ hit  -> vkCreateShaderModule + vkCreate*Pipelines  (GPU compile)
         │    └─ miss -> translate guest shader -> SPIR-V -> vkCreate*Pipelines
         ├─ log [ShaderWarmup] counters + elapsed time
         └─ write vulkan_pipelines.bin + vulkan_pipelines_global_<salt>.bin
     └─ first frame: every cached pipeline is already built
```

## What this does and does not guarantee

- The removed work is deterministic: duplicate pipeline creation at boot is
  gone, and driver cache entries are shared across titles instead of being
  discarded. Both reduce `vkCreate*Pipelines` calls.
- Whether a specific pipeline is actually *compiled* at creation time is up to
  the driver. Vulkan has no portable "compile this pipeline now" call; Mali and
  Adreno compile during `vkCreateGraphicsPipelines`, which is why the warmup is
  done there. A driver that defers compilation to first draw will still compile
  on first draw.
- The global cache is only valid for the exact device/driver that wrote it.
  The salt enforces that at the filename level; the existing `VULKAN_CACHE_MAGIC_NUMBER`
  and `CACHE_VERSION` check rejects stale emulator builds.
- The driver cache is flushed at the end of the loading screen and at renderer
  shutdown. If Android kills the process without running destructors (force
  stop, low-memory kill), compiles done since the last flush are lost for that
  session. The guest-shader cache (`vulkan.bin`) is written per pipeline and is
  not affected.
- No device is attached to the build machine, so this document describes the
  deterministic work removed, not a measured FPS improvement.

## How to verify on a device

```sh
# warmup hit ratio and timing, once per boot
adb logcat -s yuzu:V | grep -E "ShaderWarmup|Vulkan driver pipelines cached"

# cross-title cache file exists and grows across games
adb shell ls -l /storage/emulated/0/Android/data/org.suyu.suyu_emu/files/shader/

# second boot should have from disk > 0 and compiled close to 0 for the same title
```

Build check performed on this machine:

- `vk_pipeline_cache.cpp` and `vulkan_wrapper.cpp` compile cleanly in the
  `arm64-v8a` Release Ninja build.
- A full `:app:assembleOptimizedRelease` and the resulting APK hash still need
  to be produced and recorded in `PERFORMANCE_REPORT.md`.

## Rollback

Revert the four files above (`git checkout -- <files>` once the repo is under
version control) and delete `vulkan_pipelines_global_*.bin` from the shader
directory. Deleting the cache files alone is always safe: they are regenerated
on the next boot.

# FLUSH_GOVERNOR.md — learning-based texture flush bandwidth saver

Date: 2026-11-25. Target: Mali/MediaTek (MT83xx, Mali-G615 class), all devices.
Status: **mechanism shipped, NOT measured** — no on-device A/B exists yet. See
"Verification" at the bottom before quoting any expected saving.

## What it is

An opt-in settings toggle (`renderer_flush_governor`, "Adaptive flush governor
(experimental)" under Graphics → Advanced) that teaches the texture cache the
CPU readback pattern of every image it holds, and uses what it learned to
remove GPU → CPU download bandwidth that provably buys the game nothing new.

The default is off; when off, `TextureCache::DownloadMemory` executes exactly
the legacy path, bit for bit.

## The firehose it attacks

`TextureCache::DownloadMemory` (`src/video_core/texture_cache/texture_cache.h`)
is the GPU → CPU flush path. A CPU read into a page tracked by any image pulls
in **entire images**, and pulls them in **again** on the next poll, even when:

- the poll only covers a tiny corner of a much larger image, and
- the image contents have not changed since the last download (the poller is
  re-reading a range whose GPU state was already transferred).

On a tiler, each download is a tile writeback plus a host-side unswizzle; for
games that poll GPU-written memory every frame (streaming surfaces, DMA
targets, polled engine headers living next to surfaces) this is usually the
largest single bandwidth sink left in the renderer.

## The learning pattern

Per-image state lives on `ImageBase::synced_spans`
(`src/video_core/texture_cache/image_base.{h,cpp}`): small `{begin, end,
modification_tick, hits}` records meaning "**these exact guest bytes** were
downloaded from this image **at this modification tick**".

The rule set is a single loop with no magic constants to tune per game:

1. A flush request fully inside a span whose tick is still current is
   **answered from guest memory** — no staging buffer, no `CopyImageToBuffer`,
   no tile writeback, no `SwizzleImage`.
2. A request that **escapes** a span **fuses into it** (`RecordSyncedSpan`
   takes the union), so the span grows to wherever the CPU actually reads.
   The rate at which spans grow is the learned "how much does this game
   really read per poll" curve; the hit counters ride on each span.
3. Any GPU write calls `MarkModification`, which bumps `modification_tick` and
   clears `GpuPartialSync` — every learned span expires automatically. There
   is no staleness window to configure.

Why this is a *learning* pattern rather than a static heuristic: nothing about
span placement is known in advance. It is derived entirely from observing
which byte ranges the game polls, and it can only ever grow to image bounds
and only ever expire on a real GPU write.

## Partial (row-group) downloads

Pitch-linear, single-plane, single-layer, single-sample colour images get a
second reduction: when the flush request covers a minority of the image, the
governor issues one `CopyImageToBuffer` for the requested **row group** with
`buffer_offset = 0` and `buffer_row_length = full row`, and lets the existing
`SwizzlePitchLinearImage` read it back into the guest rows.

Correctness of the addressing is anchored on the Vulkan spec's copy
addressing formula:

```
texelOffset = bufferOffset + ⌊(y − imageOffset.y)/blockHeight⌋ × rowExtent + …
```

Rows are addressed **relative to `imageOffset`**, exactly as
`SwizzlePitchLinearImage` reads (`memory.data() + line * info.pitch`), so the
GPU-written row lands precisely where the swizzle pass reads it. Column
partiality is deliberately not used (only whole rows from column 0), to avoid
any dependence on `imageOffset.x` addressing subtleties.

Everything else — block-linear render targets, converted images, multi
samples, depth/stencil (`SurfaceType::ColorTexture` gate), rescaled images,
sparse images, requests clipped at region boundaries, requests covering ≥ 7/8
of the image — keeps the legacy full-image download, which also clears
`GpuModified` exactly as before.

## Safety model (bit-exactness)

Every skip must have a receipt:

| Reduction | Receipt |
|---|---|
| Skip a repeat poll | A span records those bytes were downloaded at the *current* tick. `MarkModification` bumps the tick on every GPU write, so a stale span can only exist for bytes the guest can already read. |
| Skip an eviction download in the garbage collector | Only when `GpuPartialSync` is set **and** a learned span covers the whole image at the current tick (`IsFullySyncedAt`). Partial coverage never changes eviction behaviour. |
| Row-partial download | Copies exactly the requested rows; bytes written into guest memory are a subset of what the legacy full download wrote. `GpuModified` stays set, so nothing downstream treats the image as fully drained. |

`IsSafeDownload()` still gates everything, so `CpuModified` images are never
touched. Preemptive/async flushes (`GetFlushArea`, `CommitAsyncFlushes`,
`PopAsyncFlushes`) are untouched. The tick used for a stamp is captured at the
same point where the legacy path cleared `GpuModified`, so the race envelope
against concurrent GPU-thread modifications is identical to the legacy one.

`SynchronizeAliases` re-tags an image's tick from an alias chain after copying
foreign contents in, so it now also drops that image's learned spans and
`GpuPartialSync` (tick inheritance alone cannot distinguish "same contents"
from "new contents").

## Expected effect (reasoning, not measurement)

Games that poll GPU-written memory once per frame but many times per frame
(e.g. several passes each polling the same surface, or CPU polling of a DMA
stream between GPU bursts): every poll after the first after a GPU write is
free; the first after a GPU write is a row-group copy instead of a full image
copy when the poll is small. Worst case (every poll follows a fresh GPU write
covering the whole image) the behaviour is the legacy one plus a handful of
counter increments and a small vector.

Block-linear render targets get no partial-download reduction in this
pass — they keep full-image downloads and simply never learn spans. That is
the main candidate for a follow-up.

## Verification

Static only, same standard as PERFORMANCE_REPORT's 2026-11-25 pass: no device,
no build, no frame-time or bandwidth number exists for this pass. On-device
A/B should watch:

- the existing `[TileFlow]` counters (`TileFlowBreak::ImageDownload` fire count
  per flush event),
- `malioc`/Streamline L2/DRAM read counters around the flush path,
- the `SyncedSpan::hits` counters (reachable under a debugger) as a proxy for
  how often the learning engages in a given title.

Report numbers from those before raising the default or removing the toggle.

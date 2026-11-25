# Draw Burst — the Mali lightweight draw path

A fast-path layer between the emulated GPU and Vulkan that folds many guest draws into
one recorded submission command. It is not a new renderer backend and not a new GPU API:
Android apps can only reach the Mali driver through Vulkan or GLES, and Draw Burst stays
on the Vulkan path. What it removes is the *per-draw recording overhead* of the CPU-side
command stream — the part of the Vulkan pipeline that is pure software and can be made
arbitrarily thin.

## How it works

In the steady state (`renderer_draw_state_cache`), a guest draw resolves to: one already
bound pipeline, already allocated/bound descriptor sets, already emitted dynamic state.
The only work left per draw was building and recording a command object that ends in a
single `vkCmdDraw` / `vkCmdDrawIndexed`.

Draw Burst replaces that with a small packet:

- `RasterizerVulkan::Draw` pushes just the bare draw parameters (5 integers + indexed
  flag) into `Scheduler::RecordDraw`.
- The scheduler coalesces consecutive parameters into one burst and records **one**
  command object that emits all of the draws in order.
- The burst flushes — and therefore keeps exact guest draw order — whenever anything else
  enters the command stream: any other `Scheduler::Record` call, a render-pass begin/end,
  a query command, an upload, a barrier, or a chunk handover to the worker thread
  (`DispatchWork`). Order equality is by construction, not by tuning.

Invariants:

1. A burst only forms from steady-state draws (identical pipeline, descriptors, dynamic
   state), so merging them cannot change what the GPU executes.
2. The packet never survives a state change or a submission, so nothing can overtake the
   pending draws.
3. The layer is purely a recording-path optimization; behaviour depends on no driver
   feature and degrades to the previous path when disabled.

## Interaction with existing fast paths

- **`renderer_draw_state_cache`** — Draw Burst extends the steady-state path; it does not
  need it, but they are aimed at the same window. With the draw-state cache off, a burst
  still forms whenever two draws resolve to identical fresh bindings, just less often.
- **Queries** — query commands go through `Scheduler::Record`, which flushes the packet
  first; attribution is unchanged.
- **Conditional rendering** — host conditional rendering begin/end records flush the
  packet.
- **Static frame skip and draw counters** — count in `PrepareDraw` before the burst, so
  they are unaffected.

## Settings

- `renderer_draw_burst` (`Category::RendererDebug`) — enables the path. Off by default;
  the first-launch Malikan profile enables it (`Settings::ApplyPerformanceProfile`);
  per-game overrides and the Tweaks sheet toggle work as for the other renderer
  toggles.
- Counters: `draw_burst[<groups> / <draws>]` in the periodic `[TileFlow]` log line
  (enable `renderer_tileflow_counters`) — groups emitted vs. draws folded.

## Status

Implemented in `vk_scheduler.h/.cpp` and `vk_rasterizer.cpp`. **Not yet benchmarked**:
no on-device measurement exists yet; `PERFORMANCE_REPORT.md` intentionally does not
claim numbers for it. If it misbehaves on a specific game, the layer degrades cleanly:
turn the toggle off and the previous per-draw recording path runs again.

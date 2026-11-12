// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.dashboard

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.utils.Log
import org.yuzu.yuzu_emu.utils.NativeConfig
import java.util.Locale
import kotlin.math.max

/**
 * Iterative performance auto-tuner.
 *
 * Runs a fixed 10-second benchmark on the live game, then walks a small set of runtime-modifiable
 * graphics levers (internal resolution, GPU accuracy, emulated GPU clock) with a coordinate-descent
 * search: each lever is swept while the others hold their best-so-far value, and every candidate is
 * re-benchmarked for another 10 seconds. The candidate with the best score that still clears the
 * chosen target's stability floor wins, is confirmed with one final run, and is applied + saved.
 *
 * The whole session runs with the speed limit unlocked so throughput differences are visible even
 * in games that normally cap at 30/60 FPS; the original limit and speed mode are restored afterwards.
 * Cancelling, pausing or stopping the game mid-session restores the original settings.
 */

/** Length of one benchmark measurement window. */
private const val WINDOW_MS = 10_000L

/** Time given to the renderer to reallocate targets / warm pipelines after a settings change. */
private const val SETTLE_MS = 2_500L

/** Frame counters are cheap; poll four times a second like the dashboard does. */
private const val POLL_MS = 250L

/** Rolling frametime ring exposed by the core (PerfStats::RecentWindow). */
private const val HISTORY_SAMPLES = 240

// Settings::ResolutionSetup ordinals. The ladder stays at or below native resolution: on Mali the
// fill rate is the dominant cost, so supersampling only ever removes frames.
private const val RES_0_5X = 1
private const val RES_0_75X = 2
private const val RES_1X = 3
private val RESOLUTION_LADDER = intArrayOf(RES_1X, RES_0_75X, RES_0_5X)

// Settings::GpuAccuracy ordinals (this fork exposes Low / High only).
private const val ACCURACY_LOW = 0
private const val ACCURACY_HIGH = 1
private val ACCURACY_LADDER = intArrayOf(ACCURACY_LOW, ACCURACY_HIGH)

// Settings::GpuOverclock ordinals. Extreme (2048 divisor) is excluded: it can break guest timing.
private const val CLOCK_NORMAL = 0
private const val CLOCK_HIGH = 2
private const val CLOCK_VERY_HIGH = 3
private val CLOCK_LADDER = intArrayOf(CLOCK_NORMAL, CLOCK_HIGH, CLOCK_VERY_HIGH)

/**
 * What the player wants to optimise for. Each target sets a hard viability floor (minimum emulation
 * speed and minimum frame-time smoothness) and a scoring weight between raw throughput and smoothness.
 * Smoothness is p50/p95: 1.0 means perfectly even frame times, lower means spiky.
 */
enum class TuneTarget(val minSpeed: Double, val minSmoothness: Double, val speedWeight: Double) {
    MAX_FPS(0.85, 0.34, 0.85),
    BALANCED(0.97, 0.50, 0.60),
    STABLE(1.00, 0.63, 0.30)
}

/** A full point in the search space. All three levers apply live while the game runs. */
data class TuneConfig(val resolution: Int, val accuracy: Int, val clock: Int)

data class BenchmarkResult(
    val config: TuneConfig,
    val meanFps: Double,
    val meanSpeed: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val shadersBuilding: Int,
    val valid: Boolean,
    val viable: Boolean,
    val score: Double
) {
    val smoothness: Double
        get() = if (p95Ms > 0.0) (p50Ms / p95Ms).coerceIn(0.0, 1.0) else 0.0
}

enum class TuneLever { RESOLUTION, ACCURACY, CLOCK }

data class LeverChange(val lever: TuneLever, val before: Int, val after: Int)

data class TuneReport(
    val target: TuneTarget,
    val baseline: BenchmarkResult,
    val winner: BenchmarkResult,
    val changes: List<LeverChange>,
    val keptBaseline: Boolean
)

enum class AutoTunePhase { SETUP, RUNNING, FINISHED, FAILED, RESTORED }

enum class AutoTuneFailure { NOT_RUNNING, STOPPED, PAUSED, UNEXPECTED }

data class AutoTuneUiState(
    val phase: AutoTunePhase = AutoTunePhase.SETUP,
    val target: TuneTarget = TuneTarget.BALANCED,
    val runIndex: Int = 0,
    val totalRuns: Int = 0,
    val runLabel: String = "",
    val settling: Boolean = false,
    val windowProgress: Float = 0f,
    val liveFps: Double = 0.0,
    val liveSpeed: Double = 0.0,
    val results: List<BenchmarkResult> = emptyList(),
    val report: TuneReport? = null,
    val failure: AutoTuneFailure? = null
)

/** Thrown when emulation stops or pauses mid-session; caught to restore settings and report why. */
private class AutoTuneAbort(val reason: AutoTuneFailure) : Exception()

private data class SettingsSnapshot(
    val resolution: Int,
    val accuracy: Int,
    val clock: Int,
    val useSpeedLimit: Boolean,
    val wasTurbo: Boolean,
    val wasSlow: Boolean,
    val globalFlags: Map<String, Boolean>
)

class AutoTuneEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AutoTuneUiState())
    val state: StateFlow<AutoTuneUiState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var snapshot: SettingsSnapshot? = null

    /** Updates the optimization target while still on the setup screen. */
    fun setTarget(target: TuneTarget) {
        if (runJob?.isActive == true) {
            return
        }
        _state.update { it.copy(target = target) }
    }

    /** Begins a tuning session. No-op if one is already running. */
    fun start(target: TuneTarget) {
        if (runJob?.isActive == true) {
            return
        }
        if (!NativeLibrary.isRunning() || NativeLibrary.isPaused()) {
            _state.update { it.copy(phase = AutoTunePhase.FAILED, failure = AutoTuneFailure.NOT_RUNNING) }
            return
        }
        snapshot = takeSnapshot()
        runJob = scope.launch { runSession(target) }
    }

    /** Cancels a running session; the run loop restores the original settings as it unwinds. */
    fun cancel() {
        runJob?.cancel()
    }

    /** Reverts an applied result back to the pre-benchmark settings. Offered from the results screen. */
    fun restoreOriginal() {
        if (runJob?.isActive == true) {
            return
        }
        restoreSnapshot()
        _state.update { it.copy(phase = AutoTunePhase.RESTORED) }
    }

    /** Releases the engine once the sheet is gone for good. */
    fun release() {
        cancel()
        scope.cancel()
    }

    private suspend fun runSession(target: TuneTarget) {
        val snap = snapshot ?: return
        val baselineConfig = TuneConfig(snap.resolution, snap.accuracy, snap.clock)
        val totalRuns = estimateRunCount(baselineConfig)
        _state.update {
            it.copy(
                phase = AutoTunePhase.RUNNING,
                target = target,
                runIndex = 0,
                totalRuns = totalRuns,
                results = emptyList(),
                report = null,
                failure = null
            )
        }

        val results = mutableListOf<BenchmarkResult>()
        var runIndex = 0

        suspend fun run(config: TuneConfig, label: String): BenchmarkResult {
            runIndex++
            _state.update {
                it.copy(runIndex = runIndex, runLabel = label, settling = true, windowProgress = 0f)
            }
            val result = measure(config, target)
            results += result
            Log.info(
                String.format(
                    Locale.US,
                    "[AutoTune] run %d (%s): fps=%.1f speed=%.2f p50=%.2f p95=%.2f smooth=%.2f viable=%b score=%.3f",
                    runIndex, label, result.meanFps, result.meanSpeed, result.p50Ms, result.p95Ms,
                    result.smoothness, result.viable, result.score
                )
            )
            _state.update {
                it.copy(results = results.toList(), liveFps = result.meanFps, liveSpeed = result.meanSpeed)
            }
            return result
        }

        try {
            // Normalise the clock: standard mode (no turbo/slow CPU scaling) with the limiter off, so
            // every candidate is measured at flat-out throughput. Restored in finally / on the way out.
            NativeLibrary.setStandardSpeedLimit(true)
            BooleanSetting.RENDERER_USE_SPEED_LIMIT.setBoolean(false)

            val baseline = run(baselineConfig, "baseline")
            var best = baseline

            // Coordinate descent: sweep one lever at a time around the best-so-far configuration.
            for (res in RESOLUTION_LADDER) {
                if (res == best.config.resolution) continue
                val r = run(best.config.copy(resolution = res), "resolution")
                if (r.viable && preferred(r, best)) best = r
            }
            for (acc in ACCURACY_LADDER) {
                if (acc == best.config.accuracy) continue
                val r = run(best.config.copy(accuracy = acc), "accuracy")
                if (r.viable && preferred(r, best)) best = r
            }
            for (clock in CLOCK_LADDER) {
                if (clock == best.config.clock) continue
                val r = run(best.config.copy(clock = clock), "clock")
                if (r.viable && preferred(r, best)) best = r
            }

            // Confirm the winner once more to guard against thermal drift / noise during the search.
            val confirmation = run(best.config, "confirm")
            val finalists = results.filter { it.config != best.config } + confirmation
            val winner = finalists.filter { it.viable }.maxByOrNull { it.score } ?: baseline

            // Restore the speed controls before saving so the unlocked state is never persisted.
            restoreSpeedControls()
            applyConfig(winner.config)
            saveConfig()

            val report = TuneReport(
                target = target,
                baseline = baseline,
                winner = winner,
                changes = diff(baselineConfig, winner.config),
                keptBaseline = winner.config == baselineConfig
            )
            Log.info(
                String.format(
                    Locale.US,
                    "[AutoTune] done: baseline fps=%.1f -> winner fps=%.1f (kept baseline=%b)",
                    baseline.meanFps, winner.meanFps, report.keptBaseline
                )
            )
            _state.update { it.copy(phase = AutoTunePhase.FINISHED, report = report, windowProgress = 1f) }
        } catch (abort: AutoTuneAbort) {
            restoreSnapshot()
            _state.update { it.copy(phase = AutoTunePhase.FAILED, failure = abort.reason) }
        } catch (cancelled: CancellationException) {
            restoreSnapshot()
            // Cancel is a supported exit: report the restored state so the sheet can leave RUNNING
            // instead of freezing on a progress bar that will never advance.
            _state.update {
                it.copy(
                    phase = AutoTunePhase.RESTORED,
                    settling = false,
                    windowProgress = 0f,
                    report = null,
                    failure = null
                )
            }
            throw cancelled
        } catch (error: Exception) {
            Log.error("[AutoTune] session failed: ${error.message}")
            restoreSnapshot()
            _state.update { it.copy(phase = AutoTunePhase.FAILED, failure = AutoTuneFailure.UNEXPECTED) }
        } finally {
            restoreSpeedControls()
        }
    }

    /**
     * Applies a candidate, lets the renderer settle, then samples for [WINDOW_MS].
     *
     * FPS and frame-time percentiles come from the rolling frametime ring: the core's mean frametime
     * is cumulative since boot and would dilute a 10-second window. Emulation speed comes from
     * getPerfStats(), whose counters reset on read, so each poll reports the interval just elapsed.
     */
    private suspend fun measure(config: TuneConfig, target: TuneTarget): BenchmarkResult {
        applyConfig(config)

        val settleStart = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - settleStart < SETTLE_MS) {
            ensureEmulationAlive()
            val fraction = (SystemClock.elapsedRealtime() - settleStart).toFloat() / SETTLE_MS
            _state.update { it.copy(windowProgress = fraction.coerceIn(0f, 1f)) }
            delay(POLL_MS)
        }
        _state.update { it.copy(settling = false, windowProgress = 0f) }

        // Prime the resetting counters so the first measured poll covers a clean poll interval rather
        // than everything since boot (or since the overlay last read them).
        NativeLibrary.getPerfStats()

        val frametimes = ArrayList<Double>(48 * HISTORY_SAMPLES)
        var speedSum = 0.0
        var speedPolls = 0
        var maxShaders = 0
        val windowStart = SystemClock.elapsedRealtime()
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - windowStart
            if (elapsed >= WINDOW_MS) break
            ensureEmulationAlive()

            val stats = NativeLibrary.getPerfStats()
            val speed = stats.getOrElse(3) { 0.0 }
            if (speed > 0.0) {
                speedSum += speed
                speedPolls++
            }
            val history = NativeLibrary.getFrametimeHistory(HISTORY_SAMPLES)
            for (ms in history) {
                if (ms > 0.0) frametimes.add(ms)
            }
            maxShaders = max(maxShaders, NativeLibrary.getShadersBuilding())

            val ringMean = if (history.isNotEmpty()) history.average() else 0.0
            _state.update {
                it.copy(
                    windowProgress = (elapsed.toFloat() / WINDOW_MS).coerceIn(0f, 1f),
                    liveFps = if (ringMean > 0.0) 1000.0 / ringMean else 0.0,
                    liveSpeed = speed
                )
            }
            delay(POLL_MS)
        }

        val sorted = frametimes.toDoubleArray().sortedArray()
        val meanMs = if (sorted.isNotEmpty()) sorted.average() else 0.0
        val p50 = percentile(sorted, 0.5)
        val p95 = percentile(sorted, 0.95)
        val meanSpeed = if (speedPolls > 0) speedSum / speedPolls else 0.0
        val smoothness = if (p95 > 0.0) (p50 / p95).coerceIn(0.0, 1.0) else 0.0

        val valid = speedPolls >= 8 && sorted.size >= 40 && meanMs > 0.0 && meanSpeed > 0.05
        val viable = valid && meanSpeed >= target.minSpeed && smoothness >= target.minSmoothness
        val score = if (viable) {
            meanSpeed * (target.speedWeight + (1.0 - target.speedWeight) * smoothness)
        } else {
            0.0
        }
        return BenchmarkResult(
            config = config,
            meanFps = if (meanMs > 0.0) 1000.0 / meanMs else 0.0,
            meanSpeed = meanSpeed,
            p50Ms = p50,
            p95Ms = p95,
            shadersBuilding = maxShaders,
            valid = valid,
            viable = viable,
            score = score
        )
    }

    /** A candidate replaces the incumbent on a clear score win, or on a near-tie that keeps more quality. */
    private fun preferred(candidate: BenchmarkResult, incumbent: BenchmarkResult): Boolean {
        if (candidate.score > incumbent.score * 1.02) {
            return true
        }
        if (candidate.score >= incumbent.score * 0.98) {
            return qualityRank(candidate.config) > qualityRank(incumbent.config)
        }
        return false
    }

    /** Higher internal resolution and higher GPU accuracy both preserve more image quality. */
    private fun qualityRank(config: TuneConfig): Int = config.resolution * 2 + config.accuracy

    private fun diff(before: TuneConfig, after: TuneConfig): List<LeverChange> = buildList {
        if (before.resolution != after.resolution) {
            add(LeverChange(TuneLever.RESOLUTION, before.resolution, after.resolution))
        }
        if (before.accuracy != after.accuracy) {
            add(LeverChange(TuneLever.ACCURACY, before.accuracy, after.accuracy))
        }
        if (before.clock != after.clock) {
            add(LeverChange(TuneLever.CLOCK, before.clock, after.clock))
        }
    }

    /** Upper bound on runs, used only to drive the overall progress bar. */
    private fun estimateRunCount(base: TuneConfig): Int {
        var count = 2 // baseline + confirmation
        count += RESOLUTION_LADDER.count { it != base.resolution }
        count += ACCURACY_LADDER.count { it != base.accuracy }
        count += CLOCK_LADDER.count { it != base.clock }
        return count
    }

    private fun ensureEmulationAlive() {
        if (!NativeLibrary.isRunning()) {
            throw AutoTuneAbort(AutoTuneFailure.STOPPED)
        }
        if (NativeLibrary.isPaused()) {
            throw AutoTuneAbort(AutoTuneFailure.PAUSED)
        }
    }

    private fun applyConfig(config: TuneConfig) {
        IntSetting.RENDERER_RESOLUTION.setInt(config.resolution)
        IntSetting.RENDERER_ACCURACY.setInt(config.accuracy)
        IntSetting.FAST_GPU_TIME.setInt(config.clock)
    }

    private fun saveConfig() {
        if (NativeConfig.isPerGameConfigLoaded()) {
            NativeConfig.savePerGameConfig()
        } else {
            NativeConfig.saveGlobalConfig()
        }
    }

    private fun takeSnapshot(): SettingsSnapshot {
        val perGame = NativeConfig.isPerGameConfigLoaded()
        val keys = listOf(
            IntSetting.RENDERER_RESOLUTION.key,
            IntSetting.RENDERER_ACCURACY.key,
            IntSetting.FAST_GPU_TIME.key,
            BooleanSetting.RENDERER_USE_SPEED_LIMIT.key
        )
        return SettingsSnapshot(
            resolution = IntSetting.RENDERER_RESOLUTION.getInt(),
            accuracy = IntSetting.RENDERER_ACCURACY.getInt(),
            clock = IntSetting.FAST_GPU_TIME.getInt(),
            useSpeedLimit = BooleanSetting.RENDERER_USE_SPEED_LIMIT.getBoolean(),
            wasTurbo = NativeLibrary.isTurboMode(),
            wasSlow = NativeLibrary.isSlowMode(),
            globalFlags = if (perGame) keys.associateWith { NativeConfig.usingGlobal(it) } else emptyMap()
        )
    }

    /**
     * Restores the native turbo/slow/standard mode, the speed-limit flag and its per-game routing.
     * The native setters clobber use_speed_limit as a side effect, so the exact snapshot value and
     * routing flag are re-applied last. Idempotent: safe to call on both the success and unwind paths.
     */
    private fun restoreSpeedControls() {
        val snap = snapshot ?: return
        when {
            snap.wasTurbo -> NativeLibrary.setTurboSpeedLimit(true)
            snap.wasSlow -> NativeLibrary.setSlowSpeedLimit(true)
            else -> NativeLibrary.setStandardSpeedLimit(true)
        }
        BooleanSetting.RENDERER_USE_SPEED_LIMIT.setBoolean(snap.useSpeedLimit)
        snap.globalFlags[BooleanSetting.RENDERER_USE_SPEED_LIMIT.key]?.let {
            NativeConfig.setGlobal(BooleanSetting.RENDERER_USE_SPEED_LIMIT.key, it)
        }
    }

    private fun restoreSnapshot() {
        val snap = snapshot ?: return
        IntSetting.RENDERER_RESOLUTION.setInt(snap.resolution)
        IntSetting.RENDERER_ACCURACY.setInt(snap.accuracy)
        IntSetting.FAST_GPU_TIME.setInt(snap.clock)
        BooleanSetting.RENDERER_USE_SPEED_LIMIT.setBoolean(snap.useSpeedLimit)
        snap.globalFlags.forEach { (key, global) -> NativeConfig.setGlobal(key, global) }
        saveConfig()
    }
}

private fun percentile(sorted: DoubleArray, fraction: Double): Double {
    if (sorted.isEmpty()) {
        return 0.0
    }
    val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
    return sorted[index]
}

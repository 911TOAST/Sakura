// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.dashboard

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.ui.theme.SuyuM3Theme
import org.yuzu.yuzu_emu.utils.ArtworkColors
import org.yuzu.yuzu_emu.utils.applyPanelCloseMotion
import org.yuzu.yuzu_emu.utils.applyPanelWindowMotion
import java.util.Locale
import kotlin.math.max

private const val SAMPLES = 240
private const val TARGET_60 = 16.667
private const val TARGET_30 = 33.333

/** Frame counters are cheap, so they refresh four times a second. */
private const val FAST_INTERVAL_MS = 250L

/** Every fourth fast tick the expensive probes (thermals, memory) run, i.e. once a second. */
private const val SLOW_TICK_EVERY = 4

data class FrameStats(
    val running: Boolean,
    val meanFrametimeMs: Double,
    val speedRatio: Double,
    val shadersBuilding: Int,
    val frametimes: DoubleArray,
    val p50: Double,
    val p95: Double,
    val worst: Double,
)

data class SystemStats(
    val thermalHeadroom: Float,
    val nativeHeapMb: Long,
    val processPssMb: Long,
    val deviceFreeMb: Long,
    val deviceTotalMb: Long,
)

private fun percentile(sorted: DoubleArray, fraction: Double): Double {
    if (sorted.isEmpty()) {
        return 0.0
    }
    val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
    return sorted[index]
}

/**
 * Reads the emulator frame counters. Runs off the main thread: the percentile pass touches the
 * whole 240 sample window, which is far too much work to do inside a composition.
 */
private fun sampleFrames(): FrameStats {
    if (!NativeLibrary.isRunning()) {
        return FrameStats(false, 0.0, 0.0, 0, DoubleArray(0), 0.0, 0.0, 0.0)
    }

    val peek = NativeLibrary.peekPerfStats()
    val history = NativeLibrary.getFrametimeHistory(SAMPLES)
    val sorted = history.sortedArray()

    return FrameStats(
        running = true,
        meanFrametimeMs = peek.getOrElse(0) { 0.0 },
        speedRatio = peek.getOrElse(1) { 0.0 },
        shadersBuilding = peek.getOrElse(2) { 0.0 }.toInt(),
        frametimes = history,
        p50 = percentile(sorted, 0.5),
        p95 = percentile(sorted, 0.95),
        worst = percentile(sorted, 1.0),
    )
}

/** Thermals and memory move slowly and cost a binder round trip, so they sample once a second. */
private fun sampleSystem(context: Context): SystemStats {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    // The headroom getter takes a target thermal status and only exists from API 34; the float
    // overload that shipped in 30 is gone from the API 36 platform.
    val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        powerManager.getThermalHeadroom(PowerManager.THERMAL_STATUS_LIGHT)
    } else {
        -1f
    }

    val memInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(memInfo)

    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val deviceMem = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(deviceMem)

    return SystemStats(
        thermalHeadroom = headroom,
        nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L),
        // totalPss is reported in kilobytes.
        processPssMb = memInfo.totalPss.toLong() / 1024L,
        deviceFreeMb = deviceMem.availMem / (1024L * 1024L),
        deviceTotalMb = deviceMem.totalMem / (1024L * 1024L),
    )
}

class PerfDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyPanelWindowMotion()
        setContent {
            // The dashboard follows the library's cover art, the way a player colours itself
            // from the album that is on screen.
            val artworkSeed by ArtworkColors.seed.collectAsState()
            SuyuM3Theme(artworkSeed = artworkSeed) {
                DashboardScreen(onBack = { finish() })
            }
        }
    }

    /** Every exit path, including system back, leaves with the same slide. */
    override fun finish() {
        applyPanelCloseMotion()
        super.finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var frames by remember { mutableStateOf(sampleFrames()) }
    var system by remember { mutableStateOf(sampleSystem(context)) }
    val batteryTemp = rememberBatteryTemperature()

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var tick = 0
            while (true) {
                frames = withContext(Dispatchers.Default) { sampleFrames() }
                if (tick % SLOW_TICK_EVERY == 0) {
                    system = withContext(Dispatchers.Default) { sampleSystem(context) }
                }
                tick++
                delay(FAST_INTERVAL_MS)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.dashboard_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (frames.running) {
                                stringResource(R.string.dashboard_live_badge)
                            } else {
                                stringResource(R.string.dashboard_idle_badge)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_perf_arrow_back),
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { insets ->
        DashboardContent(
            insets = insets,
            frames = frames,
            system = system,
            batteryTempC = batteryTemp.value
        )
    }
}

@Composable
private fun DashboardContent(
    insets: PaddingValues,
    frames: FrameStats,
    system: SystemStats,
    batteryTempC: Float
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(insets)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HeroCard(frames)

        if (!frames.running) {
            Text(
                text = stringResource(R.string.dashboard_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_frametime),
                value = formatMs(frames.meanFrametimeMs),
                accent = frametimeAccent(frames.running, frames.meanFrametimeMs)
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_speed),
                value = formatPercent(frames.speedRatio)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_thermal_headroom),
                value = formatHeadroom(system.thermalHeadroom)
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.dashboard_battery_temp),
                value = formatCelsius(batteryTempC)
            )
        }

        FrametimePanel(frames)

        MemoryPanel(system)

        Panel(title = stringResource(R.string.dashboard_shaders_building)) {
            Text(
                text = frames.shadersBuilding.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun HeroCard(frames: FrameStats) {
    val fps = if (frames.running && frames.meanFrametimeMs > 0.0) {
        1000.0 / frames.meanFrametimeMs
    } else {
        0.0
    }
    val budget = if (frames.running && frames.meanFrametimeMs > 0.0) {
        (frames.meanFrametimeMs / TARGET_60).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedBudget by animateFloatAsState(
        targetValue = budget,
        animationSpec = tween(durationMillis = 240, easing = LinearEasing),
        label = "frameBudget"
    )
    val accent = frametimeAccent(frames.running, frames.meanFrametimeMs)
    // M3 Expressive hero block: a bold tonal surface while a game is running, quiet surface when idle.
    val heroContainer = if (frames.running) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val heroOn = if (frames.running) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = heroContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (fps > 0.0) String.format(Locale.US, "%.0f", fps) else "—",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (frames.running) heroOn else accent
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dashboard_fps),
                    style = MaterialTheme.typography.titleMedium,
                    color = heroOn.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Spacer(Modifier.weight(1f))
                StatusBadge(frames.running)
            }

            Spacer(Modifier.height(18.dp))

            ProgressBar(
                fraction = animatedBudget,
                color = if (frames.running) heroOn else accent,
                track = heroOn.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.dashboard_frame_budget),
                    style = MaterialTheme.typography.labelMedium,
                    color = heroOn.copy(alpha = 0.75f)
                )
                Text(
                    text = stringResource(R.string.dashboard_frame_budget_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = heroOn.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    // Sits on top of the tonal hero block, so the running badge needs the solid primary role
    // rather than another container tone.
    Surface(
        color = if (running) scheme.primary else scheme.surfaceContainerHighest,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = stringResource(
                if (running) R.string.dashboard_live_badge else R.string.dashboard_idle_badge
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (running) scheme.onPrimary else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    label: String,
    value: String,
    accent: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = accent
            )
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun FrametimePanel(frames: FrameStats) {
    Panel(title = stringResource(R.string.dashboard_frametime_graph)) {
        if (frames.frametimes.size < 2) {
            Text(
                text = stringResource(R.string.dashboard_idle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Panel
        }

        FrametimeGraph(
            times = frames.frametimes,
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
        )
        Spacer(Modifier.height(14.dp))
        StatsRow(stringResource(R.string.dashboard_p50), formatMs(frames.p50))
        StatsRow(stringResource(R.string.dashboard_p95), formatMs(frames.p95))
        StatsRow(stringResource(R.string.dashboard_worst), formatMs(frames.worst))
        StatsRow(
            stringResource(R.string.dashboard_sample_window),
            "${frames.frametimes.size} / $SAMPLES"
        )
    }
}

@Composable
private fun MemoryPanel(system: SystemStats) {
    val usedRatio = if (system.deviceTotalMb > 0) {
        ((system.deviceTotalMb - system.deviceFreeMb).toFloat() / system.deviceTotalMb)
            .coerceIn(0f, 1f)
    } else {
        0f
    }

    Panel(title = stringResource(R.string.dashboard_memory)) {
        StatsRow(
            stringResource(R.string.dashboard_emulator_heap),
            formatMb(system.nativeHeapMb)
        )
        StatsRow(
            stringResource(R.string.dashboard_process_pss),
            formatMb(system.processPssMb)
        )
        StatsRow(
            stringResource(R.string.dashboard_device_ram),
            "${formatMb(system.deviceFreeMb)} / ${formatMb(system.deviceTotalMb)}"
        )
        Spacer(Modifier.height(12.dp))
        ProgressBar(
            fraction = usedRatio,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        )
    }
}

@Composable
private fun ProgressBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(track)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FrametimeGraph(times: DoubleArray, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val lineColor = scheme.primary
    val gridColor = scheme.outlineVariant
    val underColor = lineColor.copy(alpha = 0.22f)

    Canvas(modifier = modifier) {
        if (times.size < 2) {
            return@Canvas
        }

        val peak = max(times.max(), TARGET_30 * 1.5)
        val width = size.width
        val height = size.height

        fun yOf(ms: Double): Float = height - (height * (ms / peak)).toFloat()

        // 60 / 30 FPS target guides behind the trace.
        for (target in doubleArrayOf(TARGET_60, TARGET_30)) {
            val y = yOf(target)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1f
            )
        }

        val stepX = width / (times.size - 1)
        val line = Path()
        val area = Path()
        line.moveTo(0f, yOf(times[0]))
        area.moveTo(0f, height)
        area.lineTo(0f, yOf(times[0]))
        for (i in 1 until times.size) {
            val x = i * stepX
            val y = yOf(times[i])
            line.lineTo(x, y)
            area.lineTo(x, y)
        }
        area.lineTo(width, height)
        area.close()

        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                colors = listOf(underColor, Color.Transparent),
                startY = 0f,
                endY = height
            )
        )
        drawPath(
            path = line,
            color = lineColor,
            style = Stroke(
                width = 2.5f * density,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

/**
 * Battery temperature comes from a single sticky broadcast instead of a polling receiver, so the
 * dashboard never pays a binder round trip per frame while it is on screen.
 */
@Composable
private fun rememberBatteryTemperature(): State<Float> {
    val context = LocalContext.current
    val temperature = remember { mutableStateOf(-1f) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                temperature.value = readBatteryCelsius(intent)
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        temperature.value = readBatteryCelsius(sticky)

        onDispose { context.unregisterReceiver(receiver) }
    }

    return temperature
}

private fun readBatteryCelsius(intent: Intent?): Float {
    val milliC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    return if (milliC < 0) -1f else milliC / 10f
}

@Composable
private fun frametimeAccent(running: Boolean, meanFrametimeMs: Double): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        !running || meanFrametimeMs <= 0.0 -> scheme.onSurfaceVariant
        meanFrametimeMs <= TARGET_60 * 1.15 -> scheme.primary
        meanFrametimeMs <= TARGET_30 -> scheme.tertiary
        else -> scheme.error
    }
}

private fun formatMs(ms: Double): String =
    if (ms <= 0.0) "—" else String.format(Locale.US, "%.2f ms", ms)

private fun formatPercent(ratio: Double): String =
    if (ratio <= 0.0) "—" else String.format(Locale.US, "%.0f%%", ratio * 100.0)

private fun formatHeadroom(headroom: Float): String =
    if (headroom < 0f) "—" else String.format(Locale.US, "%.0f%%", headroom * 100f)

private fun formatCelsius(celsius: Float): String =
    if (celsius < 0f) "—" else String.format(Locale.US, "%.1f °C", celsius)

private fun formatMb(mb: Long): String = "$mb MB"

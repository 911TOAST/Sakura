// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.dashboard

import android.app.ActivityManager
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.ui.theme.SuyuM3Theme
import java.util.Locale
import kotlin.math.max

data class Snapshot(
    val running: Boolean,
    val meanFrametimeMs: Double,
    val speedRatio: Double,
    val shadersBuilding: Int,
    val frametimes: DoubleArray,
    val thermalHeadroom: Float,
    val batteryMilliC: Int,
    val nativeHeapMb: Long,
    val processPssMb: Long,
    val deviceFreeMb: Long,
    val deviceTotalMb: Long,
)

private const val SAMPLES = 240
private const val TARGET_60 = 16.667
private const val TARGET_30 = 33.333

private fun readSnapshot(ctx: Context): Snapshot {
    val running = NativeLibrary.isRunning()
    val peek = if (running) NativeLibrary.peekPerfStats() else DoubleArray(3)
    val times = if (running) NativeLibrary.getFrametimeHistory(SAMPLES) else DoubleArray(0)

    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    // The headroom getter takes a target thermal status and only exists from API 34; the float
    // overload that shipped in 30 is gone from the API 36 platform.
    val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        pm.getThermalHeadroom(PowerManager.THERMAL_STATUS_LIGHT)
    } else {
        -1f
    }

    val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val milliC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

    val memInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(memInfo)

    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val deviceMem = ActivityManager.MemoryInfo()
    am.getMemoryInfo(deviceMem)

    return Snapshot(
        running = running,
        meanFrametimeMs = peek.getOrElse(0) { 0.0 },
        speedRatio = peek.getOrElse(1) { 0.0 },
        shadersBuilding = peek.getOrElse(2) { 0.0 }.toInt(),
        frametimes = times,
        thermalHeadroom = headroom,
        batteryMilliC = milliC,
        nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L),
        processPssMb = memInfo.totalPss.toLong(),
        deviceFreeMb = deviceMem.availMem / (1024L * 1024L),
        deviceTotalMb = deviceMem.totalMem / (1024L * 1024L),
    )
}

private fun percentile(sorted: DoubleArray, fraction: Double): Double {
    if (sorted.isEmpty()) {
        return 0.0
    }
    val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
    return sorted[index]
}

class PerfDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SuyuM3Theme {
                Dashboard()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard() {
    val ctx = LocalContext.current
    var snapshot by remember { mutableStateOf(readSnapshot(ctx)) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = readSnapshot(ctx)
            delay(120)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!snapshot.running) {
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
                    value = formatMs(snapshot.meanFrametimeMs)
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.dashboard_speed),
                    value = formatPercent(snapshot.speedRatio)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.dashboard_thermal_headroom),
                    value = formatHeadroom(snapshot.thermalHeadroom)
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.dashboard_battery_temp),
                    value = formatMilliC(snapshot.batteryMilliC)
                )
            }

            Panel(title = stringResource(R.string.dashboard_frametime_graph)) {
                FrametimeGraph(snapshot.frametimes)
                Spacer(Modifier.height(8.dp))
                val sorted = remember(snapshot.frametimes) { snapshot.frametimes.sortedArray() }
                StatsRow(stringResource(R.string.dashboard_p50), formatMs(percentile(sorted, 0.5)))
                StatsRow(stringResource(R.string.dashboard_p95), formatMs(percentile(sorted, 0.95)))
                StatsRow(stringResource(R.string.dashboard_worst), formatMs(percentile(sorted, 1.0)))
                StatsRow(
                    stringResource(R.string.dashboard_sample_window),
                    "${snapshot.frametimes.size} / $SAMPLES"
                )
            }

            Panel(title = stringResource(R.string.dashboard_memory)) {
                StatsRow(
                    stringResource(R.string.dashboard_emulator_heap),
                    formatMb(snapshot.nativeHeapMb)
                )
                StatsRow(
                    stringResource(R.string.dashboard_process_pss),
                    formatMb(snapshot.processPssMb)
                )
                StatsRow(
                    stringResource(R.string.dashboard_device_ram),
                    "${formatMb(snapshot.deviceFreeMb)} / ${formatMb(snapshot.deviceTotalMb)}"
                )
            }

            Panel(title = stringResource(R.string.dashboard_shaders_building)) {
                Text(
                    text = snapshot.shadersBuilding.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun MetricCard(modifier: Modifier, label: String, value: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
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
private fun StatsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
private fun FrametimeGraph(times: DoubleArray) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.12f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        if (times.isEmpty()) {
            return@Canvas
        }

        val peak = max(times.max(), TARGET_30 * 1.5)
        val w = size.width
        val h = size.height

        fun yOf(ms: Double): Float = h - (h * (ms / peak)).toFloat()

        for (target in doubleArrayOf(TARGET_60, TARGET_30)) {
            val y = yOf(target)
            drawLine(color = grid, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
        }

        val stepX = if (times.size > 1) w / (times.size - 1) else w
        val graph = Path()
        val area = Path()
        graph.moveTo(0f, yOf(times[0]))
        area.moveTo(0f, h)
        area.lineTo(0f, yOf(times[0]))
        for (i in 1 until times.size) {
            val x = i * stepX
            val y = yOf(times[i])
            graph.lineTo(x, y)
            area.lineTo(x, y)
        }
        area.lineTo(w, h)
        area.close()

        drawPath(area, color = fill)
        drawPath(graph, color = line, style = Stroke(width = 2f))
    }
}

private fun formatMs(ms: Double): String =
    if (ms <= 0.0) "—" else String.format(Locale.US, "%.2f ms", ms)

private fun formatPercent(ratio: Double): String =
    if (ratio <= 0.0) "—" else String.format(Locale.US, "%.0f%%", ratio * 100.0)

private fun formatHeadroom(headroom: Float): String =
    if (headroom < 0f) "—" else String.format(Locale.US, "%.0f%%", headroom * 100f)

private fun formatMilliC(milliC: Int): String =
    if (milliC < 0) "—" else String.format(Locale.US, "%.1f °C", milliC / 10.0)

private fun formatMb(mb: Long): String = "$mb MB"

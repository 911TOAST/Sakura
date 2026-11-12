// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.dashboard

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.ui.theme.SuyuM3Theme
import org.yuzu.yuzu_emu.utils.ArtworkColors
import java.util.Locale

/**
 * Hosts the auto-tuner in the activity's own content view, the same way [showTweaksSheet] does, so
 * the emulator surface keeps rendering underneath: launching a separate activity would pause the
 * game and the benchmark would measure nothing.
 */
fun showAutoTuneSheet(activity: ComponentActivity) {
    val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
    val sheet = ComposeView(activity)
    sheet.setViewTreeLifecycleOwner(activity)
    sheet.setViewTreeSavedStateRegistryOwner(activity)
    root.addView(sheet, FrameLayout.LayoutParams(-1, -1))
    sheet.setContent {
        val artworkSeed by ArtworkColors.seed.collectAsState()
        SuyuM3Theme(artworkSeed = artworkSeed) {
            AutoTuneSheet(onDismiss = { root.removeView(sheet) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoTuneSheet(onDismiss: () -> Unit) {
    val engine = remember { AutoTuneEngine() }
    val state by engine.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Every exit path (back, swipe down, Done) tears the engine down; a running session restores the
    // original settings as its coroutine unwinds.
    DisposableEffect(engine) {
        onDispose { engine.release() }
    }
    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state.phase) {
                AutoTunePhase.SETUP -> SetupContent(state, engine)
                AutoTunePhase.RUNNING -> RunningContent(state, engine)
                AutoTunePhase.FINISHED -> FinishedContent(state, engine, onDismiss)
                AutoTunePhase.FAILED -> FailedContent(state, onDismiss)
                AutoTunePhase.RESTORED -> RestoredContent(onDismiss)
            }
        }
    }
}

@Composable
private fun SetupContent(state: AutoTuneUiState, engine: AutoTuneEngine) {
    SheetTitle(stringResource(R.string.autotune_title))
    Text(
        text = stringResource(R.string.autotune_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SectionLabel(stringResource(R.string.autotune_target_header))
    TargetRow(
        title = stringResource(R.string.autotune_target_max),
        description = stringResource(R.string.autotune_target_max_desc),
        selected = state.target == TuneTarget.MAX_FPS,
        onSelect = { engine.setTarget(TuneTarget.MAX_FPS) }
    )
    TargetRow(
        title = stringResource(R.string.autotune_target_balanced),
        description = stringResource(R.string.autotune_target_balanced_desc),
        selected = state.target == TuneTarget.BALANCED,
        onSelect = { engine.setTarget(TuneTarget.BALANCED) }
    )
    TargetRow(
        title = stringResource(R.string.autotune_target_stable),
        description = stringResource(R.string.autotune_target_stable_desc),
        selected = state.target == TuneTarget.STABLE,
        onSelect = { engine.setTarget(TuneTarget.STABLE) }
    )

    TonalNote(stringResource(R.string.autotune_warning))

    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = { engine.start(state.target) }
    ) {
        Text(stringResource(R.string.autotune_start))
    }
}

@Composable
private fun RunningContent(state: AutoTuneUiState, engine: AutoTuneEngine) {
    SheetTitle(stringResource(R.string.autotune_running))
    Text(
        text = stringResource(R.string.autotune_run_of, state.runIndex, state.totalRuns),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = stageLabel(state.runLabel),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (state.liveFps > 0.0) String.format(Locale.US, "%.0f", state.liveFps) else "—",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dashboard_fps),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (state.liveSpeed > 0.0) {
                        String.format(Locale.US, "%.0f%%", state.liveSpeed * 100.0)
                    } else {
                        "—"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            ProgressBar(
                fraction = state.windowProgress,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                track = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.settling) {
                    stringResource(R.string.autotune_settling)
                } else {
                    stringResource(R.string.autotune_measuring)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
        }
    }

    if (state.results.isNotEmpty()) {
        SectionLabel(stringResource(R.string.autotune_results))
        state.results.forEach { result -> ResultRow(result) }
    }

    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = { engine.cancel() }
    ) {
        Text(stringResource(R.string.autotune_cancel_restore))
    }
}

@Composable
private fun FinishedContent(state: AutoTuneUiState, engine: AutoTuneEngine, onDismiss: () -> Unit) {
    val report = state.report ?: return
    SheetTitle(stringResource(R.string.autotune_finished))

    val gain = if (report.baseline.meanFps > 0.0) {
        (report.winner.meanFps - report.baseline.meanFps) / report.baseline.meanFps * 100.0
    } else {
        0.0
    }
    val gainText = when {
        report.keptBaseline -> stringResource(R.string.autotune_no_change)
        gain >= 0.0 -> String.format(Locale.US, "+%.0f%%", gain)
        else -> String.format(Locale.US, "%.0f%%", gain)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format(Locale.US, "%.0f", report.winner.meanFps),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dashboard_fps),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = gainText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.autotune_vs_baseline, report.baseline.meanFps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.autotune_winner_metrics,
                    report.winner.meanSpeed * 100.0,
                    report.winner.smoothness * 100.0
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
            )
        }
    }

    Text(
        text = if (report.keptBaseline) {
            stringResource(R.string.autotune_kept_baseline)
        } else {
            stringResource(R.string.autotune_applied)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SectionLabel(stringResource(R.string.autotune_changes))
    if (report.changes.isEmpty()) {
        Text(
            text = stringResource(R.string.autotune_no_changes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        report.changes.forEach { change -> ChangeRow(change) }
    }

    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onDismiss
    ) {
        Text(stringResource(R.string.autotune_done))
    }
    if (!report.keptBaseline) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { engine.restoreOriginal() }
        ) {
            Text(stringResource(R.string.autotune_restore))
        }
    }
}

@Composable
private fun FailedContent(state: AutoTuneUiState, onDismiss: () -> Unit) {
    SheetTitle(stringResource(R.string.autotune_failed))
    val message = when (state.failure) {
        AutoTuneFailure.NOT_RUNNING -> R.string.autotune_fail_not_running
        AutoTuneFailure.STOPPED -> R.string.autotune_fail_stopped
        AutoTuneFailure.PAUSED -> R.string.autotune_fail_paused
        else -> R.string.autotune_fail_unexpected
    }
    TonalNote(stringResource(message))
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onDismiss
    ) {
        Text(stringResource(R.string.autotune_close))
    }
}

@Composable
private fun RestoredContent(onDismiss: () -> Unit) {
    SheetTitle(stringResource(R.string.autotune_restored))
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onDismiss
    ) {
        Text(stringResource(R.string.autotune_close))
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun TonalNote(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun TargetRow(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultRow(result: BenchmarkResult) {
    val badge = when {
        !result.valid -> stringResource(R.string.autotune_no_data)
        result.viable -> stringResource(R.string.autotune_viable)
        else -> stringResource(R.string.autotune_below_bar)
    }
    val badgeColor = when {
        !result.valid -> MaterialTheme.colorScheme.surfaceContainerHighest
        result.viable -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val badgeOn = when {
        !result.valid -> MaterialTheme.colorScheme.onSurfaceVariant
        result.viable -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = configLabel(result.config),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = String.format(Locale.US, "%.0f FPS · %.0f%%", result.meanFps, result.meanSpeed * 100.0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(color = badgeColor, shape = RoundedCornerShape(50)) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = badgeOn,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ChangeRow(change: LeverChange) {
    val lever = when (change.lever) {
        TuneLever.RESOLUTION -> stringResource(R.string.autotune_lever_resolution)
        TuneLever.ACCURACY -> stringResource(R.string.autotune_lever_accuracy)
        TuneLever.CLOCK -> stringResource(R.string.autotune_lever_clock)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = lever,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${leverValue(change.lever, change.before)} → ${leverValue(change.lever, change.after)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ProgressBar(
    fraction: Float,
    color: androidx.compose.ui.graphics.Color,
    track: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.clip(RoundedCornerShape(50)).background(track)) {
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
private fun stageLabel(stage: String): String = when (stage) {
    "baseline" -> stringResource(R.string.autotune_baseline_label)
    "resolution" -> stringResource(R.string.autotune_testing_resolution)
    "accuracy" -> stringResource(R.string.autotune_testing_accuracy)
    "clock" -> stringResource(R.string.autotune_testing_clock)
    "confirm" -> stringResource(R.string.autotune_confirm_label)
    else -> stage
}

@Composable
private fun configLabel(config: TuneConfig): String =
    "${resolutionFactor(config.resolution)} · ${accuracyLabel(config.accuracy)} · ${clockLabel(config.clock)}"

@Composable
private fun leverValue(lever: TuneLever, value: Int): String = when (lever) {
    TuneLever.RESOLUTION -> resolutionFactor(value)
    TuneLever.ACCURACY -> accuracyLabel(value)
    TuneLever.CLOCK -> clockLabel(value)
}

private fun resolutionFactor(resolution: Int): String = when (resolution) {
    0 -> "0.25×"
    1 -> "0.5×"
    2 -> "0.75×"
    3 -> "1×"
    4 -> "1.25×"
    5 -> "1.5×"
    6 -> "2×"
    7 -> "3×"
    8 -> "4×"
    9 -> "5×"
    10 -> "6×"
    11 -> "7×"
    12 -> "8×"
    else -> "${resolution}×"
}

@Composable
private fun accuracyLabel(accuracy: Int): String = when (accuracy) {
    0 -> stringResource(R.string.autotune_accuracy_low)
    else -> stringResource(R.string.autotune_accuracy_high)
}

@Composable
private fun clockLabel(clock: Int): String = when (clock) {
    0 -> stringResource(R.string.autotune_clock_normal)
    1 -> stringResource(R.string.autotune_clock_medium)
    2 -> stringResource(R.string.autotune_clock_high)
    3 -> stringResource(R.string.autotune_clock_very_high)
    else -> stringResource(R.string.autotune_clock_extreme)
}

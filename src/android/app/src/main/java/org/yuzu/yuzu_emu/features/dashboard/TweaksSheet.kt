// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.dashboard

import android.content.Intent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.ui.theme.SuyuM3Theme
import org.yuzu.yuzu_emu.utils.ArtworkColors
import org.yuzu.yuzu_emu.utils.NativeConfig
import org.yuzu.yuzu_emu.utils.VulkanLayerHelper

private enum class SpeedMode { STANDARD, TURBO, SLOW }

// Ordinals of Settings::GpuOverclock. The switch is the coarse control: on means High, which is
// where emulated GPU work stops being the frame-rate limiter on weak SoCs.
private const val GPU_OVERCLOCK_OFF = 0
private const val GPU_OVERCLOCK_ON = 2

private data class OverlayToggle(val setting: BooleanSetting, val titleRes: Int)

private val overlayToggles = listOf(
    OverlayToggle(BooleanSetting.SHOW_PERFORMANCE_OVERLAY, R.string.tweaks_performance_overlay),
    OverlayToggle(BooleanSetting.SHOW_FPS, R.string.tweaks_show_fps),
    OverlayToggle(BooleanSetting.SHOW_FRAMETIME, R.string.tweaks_show_frametime),
    OverlayToggle(BooleanSetting.SHOW_APP_RAM_USAGE, R.string.tweaks_show_ram),
    OverlayToggle(BooleanSetting.SHOW_BAT_TEMPERATURE, R.string.tweaks_show_battery_temp),
    OverlayToggle(BooleanSetting.SHOW_SHADERS_BUILDING, R.string.tweaks_show_shaders),
)

private val graphicsToggles = listOf(
    OverlayToggle(BooleanSetting.MALI_AFRC, R.string.tweaks_afrc),
    OverlayToggle(BooleanSetting.RENDERER_CLEAR_LOADOP, R.string.tweaks_clear_loadop),
    OverlayToggle(BooleanSetting.RENDERER_OPTIMAL_ATTACHMENTS, R.string.tweaks_optimal_attachments),
    OverlayToggle(BooleanSetting.RENDERER_MAXWELL_BYPASS, R.string.tweaks_maxwell_bypass),
    OverlayToggle(BooleanSetting.RENDERER_DRAW_STATE_CACHE, R.string.tweaks_draw_state_cache),
    OverlayToggle(BooleanSetting.RENDERER_CORE_PINNING, R.string.tweaks_core_pinning),
    OverlayToggle(BooleanSetting.RENDERER_ASYNC_DIRECT_PRESENT, R.string.tweaks_async_present),
)

/**
 * Hosts the Compose sheet in the activity's own content view so the emulator's surface keeps
 * rendering underneath it.
 */
fun showTweaksSheet(activity: ComponentActivity) {
    val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
    val sheet = ComposeView(activity)
    sheet.setViewTreeLifecycleOwner(activity)
    sheet.setViewTreeSavedStateRegistryOwner(activity)
    root.addView(sheet, FrameLayout.LayoutParams(-1, -1))
    sheet.setContent {
        val artworkSeed by ArtworkColors.seed.collectAsState()
        SuyuM3Theme(artworkSeed = artworkSeed) {
            TweaksSheet(activity = activity, onDismiss = { root.removeView(sheet) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TweaksSheet(activity: ComponentActivity, onDismiss: () -> Unit) {
    val state = remember {
        val needsGlobal = NativeConfig.isPerGameConfigLoaded()
        val mode = when {
            NativeLibrary.isTurboMode() -> SpeedMode.TURBO
            NativeLibrary.isSlowMode() -> SpeedMode.SLOW
            else -> SpeedMode.STANDARD
        }
        SheetState(
            mode = mode,
            overlays = overlayToggles.associate { it.setting.key to it.setting.getBoolean(needsGlobal) },
            graphics = graphicsToggles.associate { it.setting.key to it.setting.getBoolean(needsGlobal) },
            gpuOverclock = runCatching {
                IntSetting.FAST_GPU_TIME.getInt(needsGlobal) >= GPU_OVERCLOCK_ON
            }.getOrDefault(false),
            layers = VulkanLayerHelper.getLayers()
                .associate { it.name to VulkanLayerHelper.isLayerEnabled(it.name) },
        )
    }

    var sheet by remember { mutableStateOf(state) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.tweaks_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.tweaks_apply_live),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.tweaks_speed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SpeedRow(
                label = stringResource(R.string.tweaks_standard),
                selected = sheet.mode == SpeedMode.STANDARD,
                onSelect = {
                    NativeLibrary.setStandardSpeedLimit(true)
                    sheet = sheet.copy(mode = SpeedMode.STANDARD)
                }
            )
            SpeedRow(
                label = stringResource(R.string.tweaks_turbo),
                selected = sheet.mode == SpeedMode.TURBO,
                onSelect = {
                    NativeLibrary.setTurboSpeedLimit(true)
                    sheet = sheet.copy(mode = SpeedMode.TURBO)
                }
            )
            SpeedRow(
                label = stringResource(R.string.tweaks_slow),
                selected = sheet.mode == SpeedMode.SLOW,
                onSelect = {
                    NativeLibrary.setSlowSpeedLimit(true)
                    sheet = sheet.copy(mode = SpeedMode.SLOW)
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Text(
                text = stringResource(R.string.tweaks_overlay),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            overlayToggles.forEach { toggle ->
                ToggleRow(
                    label = stringResource(toggle.titleRes),
                    checked = sheet.overlays.getValue(toggle.setting.key),
                    onChange = { value ->
                        toggle.setting.setBoolean(value)
                        NativeConfig.saveGlobalConfig()
                        sheet = sheet.copy(overlays = sheet.overlays + (toggle.setting.key to value))
                    }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Text(
                text = stringResource(R.string.tweaks_graphics),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            graphicsToggles.forEach { toggle ->
                ToggleRow(
                    label = stringResource(toggle.titleRes),
                    checked = sheet.graphics.getValue(toggle.setting.key),
                    onChange = { value ->
                        toggle.setting.setBoolean(value)
                        NativeConfig.saveGlobalConfig()
                        sheet = sheet.copy(graphics = sheet.graphics + (toggle.setting.key to value))
                    }
                )
            }
            // Emulated GPU clock as a switch, for the level most players actually toggle. The
            // five-step dropdown in App Settings still exposes Medium/Very High/Extreme.
            ToggleRow(
                label = stringResource(R.string.tweaks_gpu_overclock),
                checked = sheet.gpuOverclock,
                onChange = { value ->
                    IntSetting.FAST_GPU_TIME.setInt(
                        if (value) GPU_OVERCLOCK_ON else GPU_OVERCLOCK_OFF
                    )
                    NativeConfig.saveGlobalConfig()
                    sheet = sheet.copy(gpuOverclock = value)
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Text(
                text = stringResource(R.string.vulkan_layers_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = stringResource(R.string.vulkan_layers_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (sheet.layers.isEmpty()) {
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = stringResource(R.string.vulkan_layers_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    modifier = Modifier.padding(top = 4.dp),
                    text = stringResource(
                        R.string.vulkan_layers_reported,
                        sheet.layers.keys.joinToString(", ")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                sheet.layers.forEach { (name, enabled) ->
                    ToggleRow(
                        label = name,
                        checked = enabled,
                        onChange = { value ->
                            VulkanLayerHelper.setLayerEnabled(name, value)
                            sheet = sheet.copy(layers = sheet.layers + (name to value))
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    activity.startActivity(
                        Intent(activity, PerfDashboardActivity::class.java)
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Text(
                    text = stringResource(R.string.tweaks_dashboard),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private data class SheetState(
    val mode: SpeedMode,
    val overlays: Map<String, Boolean>,
    val graphics: Map<String, Boolean>,
    val gpuOverclock: Boolean,
    val layers: Map<String, Boolean>
)

@Composable
private fun SpeedRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

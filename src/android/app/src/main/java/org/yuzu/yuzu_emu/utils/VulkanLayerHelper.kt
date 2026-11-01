// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import org.yuzu.yuzu_emu.NativeLibrary

data class VulkanLayer(val name: String)

/** Settings for Vulkan layers visible to Android's app-scoped Vulkan loader. */
object VulkanLayerHelper {
    const val SETTING_KEY = "vulkan_instance_layers"

    fun getLayers(): List<VulkanLayer> = runCatching {
        NativeLibrary.getAvailableVulkanLayers()
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .map(::VulkanLayer)
            .toList()
    }.getOrDefault(emptyList())

    fun enabledLayerNames(): List<String> = parse(NativeConfig.getString(SETTING_KEY, true))

    fun isLayerEnabled(name: String): Boolean = enabledLayerNames().contains(name)

    fun setLayerEnabled(name: String, enabled: Boolean) {
        val current = enabledLayerNames().toMutableList()
        if (enabled && name !in current) current.add(name)
        if (!enabled) current.remove(name)
        apply(current.joinToString(","))
    }

    /** Applies the selected names to the engine for the next Vulkan instance. */
    fun apply(layers: String) {
        NativeConfig.setString(SETTING_KEY, layers)
        NativeConfig.saveGlobalConfig()
        NativeLibrary.setVulkanLayers(layers)
    }

    /** Reapplies saved names after game-specific settings have been loaded. */
    fun applyStoredLayers() {
        val layers = NativeConfig.getString(SETTING_KEY, true)
        NativeLibrary.setVulkanLayers(layers)
    }

    private fun parse(value: String?): List<String> =
        value?.split(',', ':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}

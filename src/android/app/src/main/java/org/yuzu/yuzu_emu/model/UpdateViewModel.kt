// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.model

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.yuzu.yuzu_emu.NativeLibrary

/**
 * Holds the update the background checker found so the library can show a small "Update" pill
 * instead of interrupting the launch with a dialog.
 */
class UpdateViewModel : ViewModel() {
    private val _availableUpdate = MutableStateFlow<NativeLibrary.UpdateResult?>(null)
    val availableUpdate: StateFlow<NativeLibrary.UpdateResult?> = _availableUpdate.asStateFlow()

    fun setAvailableUpdate(update: NativeLibrary.UpdateResult?) {
        _availableUpdate.value = update
    }
}

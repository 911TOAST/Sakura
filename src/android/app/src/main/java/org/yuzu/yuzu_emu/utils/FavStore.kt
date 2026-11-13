// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.YuzuApplication

object FavStore {
    private const val KEY = "FavGames"

    private val prefs
        get() = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    fun has(p: String): Boolean = all().contains(p)

    fun toggle(p: String): Boolean {
        val s = all().toMutableSet()
        val added = if (s.contains(p)) {
            s.remove(p)
            false
        } else {
            s.add(p)
            true
        }
        prefs.edit { putStringSet(KEY, s) }
        return added
    }
}
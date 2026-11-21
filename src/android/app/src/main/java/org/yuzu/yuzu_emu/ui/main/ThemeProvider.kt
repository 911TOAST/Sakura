// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.ui.main

interface ThemeProvider {
    /** Theme settings already applied when this activity's views were created. */
    var themeSignature: String
}

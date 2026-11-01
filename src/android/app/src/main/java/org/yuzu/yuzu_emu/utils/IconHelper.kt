package org.yuzu.yuzu_emu.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.preference.PreferenceManager

object IconHelper {
    const val KEY = "app_icon_alias"
    const val DEF = 0
    const val POLY = 1
    const val DARK = 2
    const val NEON = 3

    private val aliases = arrayOf(
        "org.yuzu.yuzu_emu.ui.main.MainActivity",
        "org.yuzu.yuzu_emu.ui.main.MainActivityPoly",
        "org.yuzu.yuzu_emu.ui.main.MainActivityDark",
        "org.yuzu.yuzu_emu.ui.main.MainActivityNeon"
    )

    fun getIcon(ctx: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(ctx).getInt(KEY, DEF)

    fun setIcon(ctx: Context, idx: Int) {
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val cur = sp.getInt(KEY, DEF)
        if (cur == idx) return
        sp.edit().putInt(KEY, idx).apply()
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        aliases.forEachIndexed { i, name ->
            val cn = ComponentName(pkg, name)
            val st = if (i == idx) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(cn, st, PackageManager.DONT_KILL_APP)
        }
    }
}

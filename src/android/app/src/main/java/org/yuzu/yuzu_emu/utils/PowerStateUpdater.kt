// SPDX-FileCopyrightText: Copyright 2025 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.YuzuApplication

// Event-driven: the system only delivers ACTION_BATTERY_CHANGED when a battery
// value actually changes (typically every few minutes), instead of the previous
// 1 Hz main-thread poll that ran for the whole process lifetime.
object PowerStateUpdater {

    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) {
            return
        }

        val context = YuzuApplication.appContext
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) {
                    return
                }
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val chargePercent =
                    if (level >= 0 && scale > 0) (level * 100) / scale else 0
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                NativeLibrary.updatePowerState(
                    chargePercent,
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL,
                    plugged > 0
                )
            }
        }
        context.registerReceiver(newReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        receiver = newReceiver
    }

    fun stop() {
        val current = receiver ?: return
        YuzuApplication.appContext.unregisterReceiver(current)
        receiver = null
    }
}

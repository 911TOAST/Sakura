// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.os.Build
import java.io.File
import org.json.JSONObject
import org.yuzu.yuzu_emu.NativeLibrary

object ShaderCacheShare {
    private const val MANIFEST = "suyu_cache.json"
    private const val STAGING = "suyu_cache.json.tmp"
    private const val FORMAT = 2
    private const val STOCK = "system"

    private val gpu: String by lazy {
        try {
            NativeLibrary.getGpuId()
        } catch (e: Exception) {
            "0:0"
        }
    }

    private fun drv(): String {
        val d = GpuDriverHelper.installedCustomDriverData
        val n = d.name ?: return STOCK
        return n + "|" + (d.version ?: "")
    }

    private fun dev(): String = Build.MODEL + "/" + Build.HARDWARE

    fun write(dir: File, tid: String) {
        val obj = JSONObject()
        obj.put("fmt", FORMAT)
        obj.put("tid", tid)
        obj.put("gpu", gpu)
        obj.put("drv", drv())
        obj.put("dev", dev())
        val tmp = File(dir, STAGING)
        tmp.writeText(obj.toString())
        val dst = File(dir, MANIFEST)
        if (!tmp.renameTo(dst)) {
            dst.writeText(obj.toString())
            tmp.delete()
        }
    }

    fun read(dir: File): JSONObject? {
        val f = File(dir, MANIFEST)
        if (!f.exists()) {
            return null
        }
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun compatible(obj: JSONObject?): Boolean {
        if (obj == null) {
            return false
        }
        if (obj.optInt("fmt") != FORMAT) {
            return false
        }
        if (obj.optString("gpu") != gpu) {
            return false
        }
        val cur = drv()
        if (obj.optString("drv") != cur) {
            return false
        }
        if (cur == STOCK) {
            return obj.optString("dev") == dev()
        }
        return true
    }

    fun clean(dir: File) {
        File(dir, MANIFEST).delete()
        File(dir, STAGING).delete()
    }
}
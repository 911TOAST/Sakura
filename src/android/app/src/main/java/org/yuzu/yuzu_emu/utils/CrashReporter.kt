// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import org.yuzu.yuzu_emu.getPublicFilesDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal, dependency-free crash recorder.
 *
 * Installs an uncaught exception handler that writes the full stack trace to
 * Android/data/<package>/files/last_crash.txt before delegating to the previous handler so the
 * normal system crash flow is unchanged. The next launch shows the report in-app so it can be
 * copied or screenshotted without adb.
 */
object CrashReporter {
    private const val TAG = "SuyuCrash"
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            runCatching { Log.e(TAG, "Uncaught exception on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun peek(context: Context): String? =
        runCatching { reportFile(context).takeIf { it.exists() }?.readText() }.getOrNull()

    /**
     * Shows a single-line headline of the last crash as a toast from Application.onCreate.
     * The in-app dialog cannot help if the app dies before MainActivity finishes, and this can.
     * The report is renamed afterwards so it only interrupts once.
     */
    fun showLastCrashHeadline(context: Context) {
        val report = peek(context) ?: return
        val lines = report.lineSequence().map { it.trim() }.toList()
        val headline = lines.firstOrNull {
            it.contains("Exception") || it.contains("Error") || it.startsWith("Caused by")
        } ?: "Unknown crash"
        val firstFrame = lines.firstOrNull { it.startsWith("at ") }
        val message = buildString {
            append(headline.take(160))
            if (firstFrame != null) {
                append('\n')
                append(firstFrame.take(140))
            }
        }
        runCatching {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        runCatching {
            reportFile(context).renameTo(File(context.getPublicFilesDir(), "last_crash_shown.txt"))
        }
    }

    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    private fun reportFile(context: Context): File = File(context.getPublicFilesDir(), FILE_NAME)

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val report = buildString {
            appendLine("Suyu crash report")
            appendLine(
                "Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            )
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(Log.getStackTraceString(error))
        }
        reportFile(context).writeText(report)
    }
}

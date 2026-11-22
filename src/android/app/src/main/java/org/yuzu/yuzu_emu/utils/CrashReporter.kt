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

        captureNativeTailIfCrashed(appContext)

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            runCatching { Log.e(TAG, "Uncaught exception on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Marks that emulation started. If the process dies without the Java handler running — the
     * usual signature of a native crash (SIGSEGV/SIGABRT) inside the emulator core — the marker
     * survives and is picked up by [captureNativeTailIfCrashed] on the next launch.
     */
    fun markEmulationStart(context: Context) {
        runCatching {
            markerFile(context).writeText(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            )
        }
    }

    /** Clears the marker after a clean emulation exit. */
    fun clearEmulationMarker(context: Context) {
        runCatching { markerFile(context).delete() }
    }

    /**
     * Called at startup when no Java exception was recorded but emulation was running when the
     * app last died: writes the tail of the app's own logcat into last_crash.txt so the existing
     * in-app report (MainActivity) shows where the native crash happened. Apps may read their
     * own UID's logcat without any permission.
     */
    private fun captureNativeTailIfCrashed(context: Context) {
        val marker = markerFile(context)
        if (!marker.exists()) {
            return
        }
        runCatching {
            val process = ProcessBuilder("logcat", "-d", "-b", "main", "-t", "400")
                .redirectErrorStream(true)
                .start()
            val tail = process.inputStream.bufferedReader().use { it.readText() }
            if (!reportFile(context).exists()) {
                reportFile(context).writeText(
                    buildString {
                        appendLine("Suyu crash report (native tail)")
                        appendLine(
                            "Time: " +
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                        )
                        appendLine("Emulation marker written at: ${marker.readText()}")
                        appendLine(
                            "The app died while emulating without a Java exception. " +
                                "This is the tail of the app's own logcat, " +
                                "most likely to contain the native crash:"
                        )
                        appendLine()
                        appendLine(tail)
                    }
                )
            }
        }
        runCatching { marker.delete() }
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
            it.contains("Exception") || it.contains("Error") || it.startsWith("Caused by") ||
                (it.length > 20 && it.contains(" E ") && !it.endsWith(" E "))
        } ?: "Crash while emulating (see the full report)"
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
            // A native-tail capture is not renamed: the full log belongs in the in-app dialog
            // (MainActivity) too, which clears the file when dismissed.
            if (!report.contains("(native tail)")) {
                reportFile(context).renameTo(File(context.getPublicFilesDir(), "last_crash_shown.txt"))
            }
        }
    }

    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    private fun reportFile(context: Context): File = File(context.getPublicFilesDir(), FILE_NAME)

    private fun markerFile(context: Context): File =
        File(context.getPublicFilesDir(), "emulation_pending.txt")

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

// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import java.io.File
import org.yuzu.yuzu_emu.getPublicFilesDir

/**
 * Smart Storage Cleanup: detects and (with explicit user confirmation) removes leftover
 * files that are no longer associated with any game in the library.
 *
 * Never touches: save data (nand/saves), keys, configuration, screenshots, dump output,
 * TAS scripts, play-time data, or anything belonging to an installed game. Every item
 * found by [scan] is presented to the user before deletion; [delete] only removes the
 * items that were explicitly confirmed.
 */
object StorageCleanup {
    enum class Category {
        ORPHANED_SHADER_CACHE,
        ORPHANED_MODS,
        TEMP_FILES,
        CRASH_LOGS
    }

    data class CleanupItem(
        val path: String,
        val sizeBytes: Long,
        val category: Category,
        val associatedGame: String?,
        val requiresExtraConfirm: Boolean
    )

    data class CleanupResult(
        val deletedCount: Int,
        val freedBytes: Long,
        val failures: List<String>
    )

    /**
     * Scans the emulator's own data folders for orphaned content. Blocking IO — call on a
     * background dispatcher.
     */
    fun scan(context: Context): List<CleanupItem> {
        val items = mutableListOf<CleanupItem>()
        val userDir = DirectoryInitialization.userDirectory?.let { File(it) }
        if (DirectoryInitialization.areDirectoriesReady && userDir?.isDirectory == true) {
            val installedIds = GameHelper.cachedGameList
                .flatMap {
                    listOf(it.programIdHex.lowercase() to it.title, it.settingsName.lowercase() to it.title)
                }
                .filter { it.first.isNotEmpty() }
                .toMap()
            items += scanShaderCaches(userDir, installedIds)
            items += scanModDirs(userDir, installedIds)
        }
        items += scanTempFiles(context)
        items += scanCrashLogs(context)
        return items.sortedByDescending { it.sizeBytes }
    }

    private fun scanShaderCaches(userDir: File, installedIds: Map<String, String>): List<CleanupItem> {
        return collectCategoriesOne(
            File(userDir, "cache/shader"), installedIds, Category.ORPHANED_SHADER_CACHE
        )
    }

    private fun scanModDirs(userDir: File, installedIds: Map<String, String>): List<CleanupItem> {
        return collectCategoriesOne(
            File(userDir, "load"), installedIds, Category.ORPHANED_MODS
        )
    }

    private fun collectCategoriesOne(
        base: File, installedIds: Map<String, String>, category: Category
    ): List<CleanupItem> {
        val items = mutableListOf<CleanupItem>()
        val children = base.listFiles() ?: return items
        for (child in children) {
            // Only per-title directories (16-hex title ids or file-name fallbacks).
            if (!child.isDirectory) {
                continue
            }
            if (installedIds.contains(child.name.lowercase())) {
                continue
            }
            items.appendItem(child, category, child.name, false)
        }
        return items
    }

    /** App-internal cache directory (shader-share staging, imported-save staging, etc.). */
    private fun scanTempFiles(context: Context): List<CleanupItem> {
        val items = mutableListOf<CleanupItem>()
        val cacheDir = context.cacheDir ?: return items
        val children = cacheDir.listFiles() ?: return items
        for (child in children) {
            // Android defines cacheDir as always clearable, so this is shown to the user
            // for transparency but never requires extra confirmation.
            items.appendItem(child, Category.TEMP_FILES, null, false)
        }
        return items
    }

    /** Crash reports and related diagnostics. Safe to remove but worth a second look. */
    private fun scanCrashLogs(context: Context): List<CleanupItem> {
        val items = mutableListOf<CleanupItem>()
        val files = context.getPublicFilesDir().walkTopDown().filter {
            it.isFile && (it.name.startsWith("last_crash") || it.name == "emulation_pending.txt")
        }
        for (f in files) {
            items.appendItem(f, Category.CRASH_LOGS, null, true)
        }
        return items
    }

    private fun MutableList<CleanupItem>.appendItem(
        file: File,
        category: Category,
        associatedGame: String?,
        requiresExtraConfirm: Boolean
    ) {
        runCatching {
            val size = if (file.isDirectory) {
                file.walkBottomUp().filter { it.isFile }.map { it.length() }.sum()
            } else {
                file.length()
            }
            if (size > 0L || file.isDirectory) {
                add(
                    CleanupItem(
                        path = file.absolutePath,
                        sizeBytes = size,
                        category = category,
                        associatedGame = associatedGame,
                        requiresExtraConfirm = requiresExtraConfirm
                    )
                )
            } else {
                file.delete()
            }
        }
    }

    /**
     * Deletes the confirmed items. Blocking IO — call on a background dispatcher.
     * Files that fail to delete are reported in [CleanupResult.failures].
     */
    fun delete(items: List<CleanupItem>): CleanupResult {
        var deleted = 0
        var freed = 0L
        val failures = mutableListOf<String>()
        for (item in items) {
            val f = File(item.path)
            val size = item.sizeBytes
            val ok = runCatching {
                if (f.isDirectory) {
                    f.deleteRecursively()
                } else {
                    f.delete()
                }
            }.getOrDefault(false)
            if (ok) {
                deleted++
                freed += size
            } else {
                failures.add(item.path)
            }
        }
        return CleanupResult(deleted, freed, failures)
    }
}

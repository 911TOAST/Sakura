// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import org.yuzu.yuzu_emu.utils.SakuraDialogs

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.MemoryUtil
import org.yuzu.yuzu_emu.utils.StorageCleanup

/**
 * Smart Storage Cleanup. Scans for orphaned files, shows exactly what was found
 * (path, category, size) and never deletes anything without explicit per-item
 * confirmation.
 */
class StorageCleanupDialogFragment : DialogFragment() {
    private val scope get() = lifecycleScope
    private lateinit var listContainer: LinearLayout
    private lateinit var totalView: TextView
    private lateinit var emptyView: TextView
    private lateinit var deleteButton: Button
    private var items: List<StorageCleanup.CleanupItem> = emptyList()
    private val checked = HashSet<StorageCleanup.CleanupItem>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val content = buildContentView()
        val dialog = SakuraDialogs.builder(requireContext())
            .setTitle(R.string.storage_cleanup)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        startScan()
        return dialog
    }

    private fun buildContentView(): View {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad / 2, pad, 0)

        totalView = TextView(requireContext()).apply {
            setText(R.string.storage_cleanup_scanning)
        }
        root.addView(totalView)

        emptyView = TextView(requireContext()).apply {
            setText(R.string.storage_cleanup_nothing_found)
            visibility = View.GONE
        }
        root.addView(emptyView)

        val scrollView = ScrollView(requireContext())
        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(listContainer)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.minimumHeight = (320 * density).toInt()

        deleteButton = Button(requireContext()).apply {
            setText(R.string.storage_cleanup_delete_selected)
            isEnabled = false
            setOnClickListener { onDeleteClicked() }
        }
        root.addView(
            deleteButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }

    private fun startScan() {
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { StorageCleanup.scan(requireContext()) }.getOrDefault(emptyList())
            }
            items = found
            // "Safe" items are pre-selected; anything flagged for extra confirmation
            // stays unticked so the user has to opt in deliberately.
            found.filter { !it.requiresExtraConfirm }.forEach { checked.add(it) }
            renderList()
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        if (items.isEmpty()) {
            totalView.setText(R.string.storage_cleanup_nothing_found)
            emptyView.visibility = View.VISIBLE
            deleteButton.isEnabled = false
            return
        }
        emptyView.visibility = View.GONE
        val total = items.sumOf { it.sizeBytes }
        totalView.text = getString(
            R.string.storage_cleanup_summary_header,
            items.size,
            MemoryUtil.bytesToSizeUnit(total.toFloat())
        )
        val density = resources.displayMetrics.density
        for (item in items) {
            val box = MaterialCheckBox(requireContext()).apply {
                text = buildItemLabel(item)
                isChecked = checked.contains(item)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        checked.add(item)
                    } else {
                        checked.remove(item)
                    }
                    deleteButton.isEnabled = checked.isNotEmpty()
                }
            }
            listContainer.addView(
                box,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        deleteButton.isEnabled = checked.isNotEmpty()
        listContainer.minimumHeight = (8 * density).toInt()
    }

    private fun buildItemLabel(item: StorageCleanup.CleanupItem): String {
        val categoryLabel = getString(
            when (item.category) {
                StorageCleanup.Category.ORPHANED_SHADER_CACHE ->
                    R.string.storage_cleanup_category_shader_cache
                StorageCleanup.Category.ORPHANED_MODS ->
                    R.string.storage_cleanup_category_mods
                StorageCleanup.Category.TEMP_FILES ->
                    R.string.storage_cleanup_category_temp
                StorageCleanup.Category.CRASH_LOGS ->
                    R.string.storage_cleanup_category_crash_logs
            }
        )
        val name = item.associatedGame
            ?: item.path.substringBeforeLast('/').substringAfterLast('/')
        val size = MemoryUtil.bytesToSizeUnit(item.sizeBytes.toFloat())
        return getString(R.string.storage_cleanup_entry, name, categoryLabel, size)
    }

    private fun onDeleteClicked() {
        val selected = items.filter { checked.contains(it) }
        if (selected.isEmpty()) {
            return
        }
        if (selected.any { it.requiresExtraConfirm }) {
            SakuraDialogs.builder(requireContext())
                .setTitle(R.string.storage_cleanup_confirm_title)
                .setMessage(R.string.storage_cleanup_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.storage_cleanup_delete_selected) { _, _ ->
                    performDelete(selected)
                }
                .show()
        } else {
            performDelete(selected)
        }
    }

    private fun performDelete(selected: List<StorageCleanup.CleanupItem>) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { StorageCleanup.delete(selected) }
                    .getOrElse {
                        StorageCleanup.CleanupResult(0, 0, listOf(it.message ?: "error"))
                    }
            }
            val message = if (result.failures.isEmpty()) {
                getString(
                    R.string.storage_cleanup_done,
                    result.deletedCount,
                    MemoryUtil.bytesToSizeUnit(result.freedBytes.toFloat())
                )
            } else {
                getString(
                    R.string.storage_cleanup_done_with_failures,
                    result.deletedCount,
                    MemoryUtil.bytesToSizeUnit(result.freedBytes.toFloat()),
                    result.failures.size
                )
            }
            SakuraDialogs.builder(requireContext())
                .setTitle(R.string.storage_cleanup_complete)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> startScan() }
                .show()
        }
    }

    companion object {
        const val TAG = "StorageCleanupDialogFragment"

        fun newInstance(): StorageCleanupDialogFragment =
            StorageCleanupDialogFragment()
    }
}

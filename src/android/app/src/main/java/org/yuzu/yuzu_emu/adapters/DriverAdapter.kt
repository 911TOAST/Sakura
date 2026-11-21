// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.CardDriverOptionBinding
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.model.Driver
import org.yuzu.yuzu_emu.model.DriverViewModel
import org.yuzu.yuzu_emu.utils.UiLayout
import org.yuzu.yuzu_emu.utils.ViewUtils.setVisible
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder
import kotlin.math.roundToInt

class DriverAdapter(private val driverViewModel: DriverViewModel) :
    AbstractSingleSelectionList<Driver, DriverAdapter.DriverViewHolder>(
        driverViewModel.driverList.value
    ) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DriverViewHolder {
        CardDriverOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return DriverViewHolder(it) }
    }

    inner class DriverViewHolder(val binding: CardDriverOptionBinding) :
        AbstractViewHolder<Driver>(binding) {
        override fun bind(model: Driver) {
            with(binding) {
                val context = root.context
                val isSystemDriver = model.title == context.getString(R.string.system_gpu_driver)

                // Selected rows get a tinted container and a primary outline; the rest stay quiet.
                val layout = UiLayout.current(context)
                root.isChecked = model.selected
                root.radius = layout.cornerRadiusPx(context)
                root.setCardBackgroundColor(
                    ColorStateList.valueOf(layout.cardFill(context, model.selected))
                )
                root.strokeColor = layout.cardStroke(context, model.selected)
                root.strokeWidth = context.resources.displayMetrics.density.roundToInt()
                textBadge.setVisible(model.selected)
                driverIcon.setImageResource(
                    if (isSystemDriver) R.drawable.ic_m3_settings else R.drawable.ic_m3_speed
                )

                title.text = model.title
                version.text = model.version
                description.text = model.description
                description.setVisible(model.description.isNotBlank())

                val metaText = buildMetaText(context, model)
                meta.text = metaText
                meta.setVisible(metaText.isNotEmpty())

                buttonDelete.setVisible(!isSystemDriver)
                buttonDelete.contentDescription = context.getString(R.string.delete)

                root.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) {
                        return@setOnClickListener
                    }
                    selectItem(position) {
                        driverViewModel.onDriverSelected(it)
                        driverViewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
                    }
                }
                buttonDelete.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        confirmDelete(model)
                    }
                }
            }
        }

        /**
         * Removing a driver deletes its file when the manager closes, so ask first instead of
         * letting a misplaced tap next to the selection area destroy a package.
         */
        private fun confirmDelete(model: Driver) {
            val context = binding.root.context
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.driver_delete_title)
                .setMessage(context.getString(R.string.driver_delete_message, model.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    // The list can change while the dialog is open, so resolve the row again.
                    val position = currentList.indexOf(model)
                    if (position == RecyclerView.NO_POSITION) {
                        return@setPositiveButton
                    }
                    removeSelectableItem(position) { removedPosition, selectedPosition ->
                        driverViewModel.onDriverRemoved(removedPosition, selectedPosition)
                        driverViewModel.showClearButton(!StringSetting.DRIVER_PATH.global)
                    }
                }
                .show()
        }
    }

    private fun buildMetaText(context: Context, model: Driver): String {
        val parts = mutableListOf<String>()
        if (model.vendor.isNotBlank()) {
            parts.add(model.vendor)
        }
        if (model.minApi > 0) {
            parts.add(context.getString(R.string.driver_min_api, model.minApi))
        }
        if (model.author.isNotBlank()) {
            parts.add(context.getString(R.string.driver_by_author, model.author))
        }
        return parts.joinToString(" • ")
    }
}

// SPDX-FileCopyrightText: Copyright 2025 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.yuzu.yuzu_emu.databinding.CardHomeOptionBinding
import org.yuzu.yuzu_emu.fragments.MessageDialogFragment
import org.yuzu.yuzu_emu.model.HomeSetting
import org.yuzu.yuzu_emu.utils.ViewUtils.marquee
import org.yuzu.yuzu_emu.utils.ViewUtils.setVisible
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder

class HomeSettingAdapter(
    private val activity: AppCompatActivity,
    private val viewLifecycle: LifecycleOwner,
    options: List<HomeSetting>
) : AbstractListAdapter<HomeSetting, HomeSettingAdapter.HomeOptionViewHolder>(options) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HomeOptionViewHolder {
        CardHomeOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return HomeOptionViewHolder(it) }
    }

    inner class HomeOptionViewHolder(val binding: CardHomeOptionBinding) :
        AbstractViewHolder<HomeSetting>(binding) {
        /** One collector per holder; a rebind cancels the one it replaces. */
        private var detailsJob: Job? = null

        override fun bind(model: HomeSetting) {
            detailsJob?.cancel()
            updateOptionDetails(binding.optionDetail, "", delay = 3000)

            binding.optionTitle.text = activity.resources.getString(model.titleId)
            binding.optionDescription.text = activity.resources.getString(model.descriptionId)
            binding.optionIcon.setImageDrawable(
                ResourcesCompat.getDrawable(
                    activity.resources,
                    model.iconId,
                    activity.theme
                )
            )

            val enabled = model.isEnabled.invoke()
            val enabledAlpha = if (enabled) 1f else 0.5f
            binding.optionTitle.alpha = enabledAlpha
            binding.optionDescription.alpha = enabledAlpha
            binding.optionIcon.alpha = enabledAlpha

            // collect() suspends on an empty flow, so a setting without details collects nothing.
            detailsJob = viewLifecycle.lifecycleScope.launch {
                viewLifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                    model.details.collect { updateOptionDetails(binding.optionDetail, it) }
                }
            }

            binding.root.setOnClickListener { onClick(model) }
        }

        fun recycle() {
            detailsJob?.cancel()
            detailsJob = null
            binding.root.setOnClickListener(null)
        }

        private fun onClick(model: HomeSetting) {
            if (model.isEnabled.invoke()) {
                model.onClick.invoke()
            } else {
                MessageDialogFragment.newInstance(
                    activity,
                    titleId = model.disabledTitleId,
                    descriptionId = model.disabledMessageId
                ).show(activity.supportFragmentManager, MessageDialogFragment.TAG)
            }
        }

        /**
         * The driver title or folder path belongs to the row it describes. Clearing it when empty
         * is what stops a recycled card from showing another row's detail line, and the marquee
         * only runs while there is something to show.
         */
        private fun updateOptionDetails(view: TextView, detailString: String, delay: Long = 0L) {
            view.text = detailString
            view.setVisible(detailString.isNotEmpty())
            if (detailString.isNotEmpty()) {
                view.marquee(delay)
            }
        }
    }

    override fun onViewRecycled(holder: HomeOptionViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }
}

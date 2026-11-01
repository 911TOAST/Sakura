// SPDX-FileCopyrightText: Copyright 2025 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleOwner
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.CardHomeOptionBinding
import org.yuzu.yuzu_emu.fragments.MessageDialogFragment
import org.yuzu.yuzu_emu.model.HomeSetting
import org.yuzu.yuzu_emu.utils.SuyuMotion
import org.yuzu.yuzu_emu.utils.ViewUtils.marquee
import org.yuzu.yuzu_emu.utils.ViewUtils.setVisible
import org.yuzu.yuzu_emu.utils.collect
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

    private var entranceRemaining = 16

    override fun onBindViewHolder(holder: HomeOptionViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        // Staggered hub entrance on the emphasized curve.
        if (entranceRemaining > 0) {
            entranceRemaining--
            val itemView = holder.itemView
            val context = itemView.context
            itemView.alpha = 0f
            itemView.translationY =
                itemView.resources.getDimensionPixelSize(R.dimen.spacing_medlarge).toFloat()
            itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(position.coerceAtMost(16) * 32L)
                .setDuration(SuyuMotion.durationMedium2(context))
                .setInterpolator(SuyuMotion.emphasizedDecelerate(context))
                .start()
        }
    }

    inner class HomeOptionViewHolder(val binding: CardHomeOptionBinding) :
        AbstractViewHolder<HomeSetting>(binding) {
        override fun bind(model: HomeSetting) {
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

            model.details.collect(viewLifecycle) { updateOptionDetails(it) }
            binding.optionDetail.marquee()

            binding.root.setOnClickListener { onClick(model) }
            binding.root.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        SuyuMotion.pressDown(view)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> SuyuMotion.pressUp(view)
                }
                false
            }
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

        private fun updateOptionDetails(detailString: String) {
            if (detailString.isNotEmpty()) {
                binding.optionDetail.text = detailString
                binding.optionDetail.setVisible(true)
            }
        }
    }
}

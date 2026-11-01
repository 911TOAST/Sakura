// SPDX-FileCopyrightText: Copyright 2025 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.text.Html
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import org.yuzu.yuzu_emu.databinding.PageSetupBinding
import org.yuzu.yuzu_emu.model.PageState
import org.yuzu.yuzu_emu.model.SetupCallback
import org.yuzu.yuzu_emu.model.SetupPage
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder
import android.content.res.ColorStateList
import android.view.View
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.model.ButtonState
import org.yuzu.yuzu_emu.utils.SuyuMotion

class SetupAdapter(val activity: AppCompatActivity, pages: List<SetupPage>) :
    AbstractListAdapter<SetupPage, SetupAdapter.SetupPageViewHolder>(pages) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SetupPageViewHolder {
        PageSetupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            .also { return SetupPageViewHolder(it) }
    }

    inner class SetupPageViewHolder(val binding: PageSetupBinding) :
        AbstractViewHolder<SetupPage>(binding), SetupCallback {
        override fun bind(model: SetupPage) {
            if (model.pageSteps.invoke() == PageState.COMPLETE) {
                onStepCompleted(0, pageFullyCompleted = true)
            }

            if (model.pageButtons != null && model.pageSteps.invoke() != PageState.COMPLETE) {
                model.pageButtons.forEachIndexed { index, pageButton ->
                    val pageButtonView = LayoutInflater.from(activity)
                        .inflate(
                            R.layout.page_button,
                            binding.pageButtonContainer,
                            false
                        ) as MaterialButton

                    pageButtonView.apply {
                        id = pageButton.titleId
                        icon = ResourcesCompat.getDrawable(
                            activity.resources,
                            pageButton.iconId,
                            activity.theme
                        )
                        text = activity.resources.getString(pageButton.titleId)
                    }

                    pageButtonView.setOnClickListener {
                        pageButton.buttonAction.invoke(this@SetupPageViewHolder)
                    }

                    binding.pageButtonContainer.addView(pageButtonView)

                    // Disable buton add if its already completed
                    if (pageButton.buttonState.invoke() == ButtonState.BUTTON_ACTION_COMPLETE) {
                        onStepCompleted(pageButton.titleId, pageFullyCompleted = false)
                    } else {
                        // Raw M3 cascade: page actions settle in one after another.
                        pageButtonView.alpha = 0f
                        pageButtonView.translationY =
                            pageButtonView.resources
                                .getDimensionPixelSize(R.dimen.spacing_med).toFloat()
                        pageButtonView.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setStartDelay(index * 50L)
                            .setDuration(SuyuMotion.durationMedium2(activity))
                            .setInterpolator(SuyuMotion.emphasizedDecelerate(activity))
                            .start()
                    }
                }
            }

            binding.icon.setImageDrawable(
                ResourcesCompat.getDrawable(
                    activity.resources,
                    model.iconId,
                    activity.theme
                )
            )
            binding.textTitle.text = activity.resources.getString(model.titleId)
            binding.textDescription.text =
                Html.fromHtml(activity.resources.getString(model.descriptionId), 0)
        }

        override fun onStepCompleted(pageButtonId: Int, pageFullyCompleted: Boolean) {
            val button = binding.pageButtonContainer.findViewById<MaterialButton>(pageButtonId)

            if (pageFullyCompleted) {
                val actions = binding.pageButtonContainer
                // Raw M3 exit: emphasize accelerate, then hide.
                actions.animate()
                    .alpha(0f)
                    .setDuration(SuyuMotion.durationShort4(activity))
                    .setInterpolator(SuyuMotion.emphasizedAccelerate(activity))
                    .withEndAction { actions.visibility = View.INVISIBLE }
                    .start()
                binding.textConfirmation.apply {
                    visibility = View.VISIBLE
                    alpha = 0f
                    scaleX = 0.92f
                    scaleY = 0.92f
                    animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(SuyuMotion.durationMedium2(activity))
                        .setInterpolator(SuyuMotion.emphasizedDecelerate(activity))
                        .start()
                }
            }

            if (button != null) {
                button.isEnabled = false
                button.animate()
                    .alpha(0.38f)
                    .setDuration(SuyuMotion.durationShort4(activity))
                    .setInterpolator(SuyuMotion.emphasizedAccelerate(activity))
                    .start()
                button.setTextColor(button.context.getColor(com.google.android.material.R.color.material_on_surface_disabled))
                button.iconTint =
                    ColorStateList.valueOf(button.context.getColor(com.google.android.material.R.color.material_on_surface_disabled))
            }
        }
    }
}

// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentWallpaperEditorBinding
import org.yuzu.yuzu_emu.features.settings.ui.WallpaperDraft
import org.yuzu.yuzu_emu.features.settings.ui.WallpaperEditorState
import org.yuzu.yuzu_emu.features.settings.ui.WallpaperViewModel
import org.yuzu.yuzu_emu.features.settings.ui.applyWallpaperBarInsets
import org.yuzu.yuzu_emu.features.settings.ui.message
import org.yuzu.yuzu_emu.features.settings.ui.openWallpaperSource
import org.yuzu.yuzu_emu.utils.background.WallpaperRenderer
import org.yuzu.yuzu_emu.utils.background.WallpaperTuning

class WallpaperEditorFragment : Fragment() {
    private var _binding: FragmentWallpaperEditorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WallpaperViewModel by navGraphViewModels(R.id.wallpaper_graph)
    private var renderer: WallpaperRenderer? = null
    private var dragStart = 0f
    private var dragStartY = 0f
    private var leaving = false
    private var syncingControls = false

    private val discardCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = requestExit()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentWallpaperEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applyWallpaperBarInsets(
            binding.wallpaperToolbar, binding.wallpaperControlsScroll, binding.wallpaperActions
        )
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, discardCallback)
        binding.wallpaperToolbar.setNavigationOnClickListener { requestExit() }
        binding.wallpaperCancel.setOnClickListener { requestExit() }
        binding.wallpaperApply.setOnClickListener { viewModel.apply() }
        binding.wallpaperReset.setOnClickListener { viewModel.resetAdjustments() }
        binding.wallpaperResetPosition.setOnClickListener {
            viewModel.tune((viewModel.editor.value.draft?.tuning ?: WallpaperTuning()).copy(x = 50, y = 50))
        }
        renderer = WallpaperRenderer(
            binding.wallpaperPreviewCard.libraryBackground,
            binding.wallpaperPreviewCard.libraryBackgroundScrim
        )

        val unsupportedBlur = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        binding.wallpaperBlur.isEnabled = !unsupportedBlur
        binding.wallpaperBlurHelp.isVisible = unsupportedBlur

        binding.wallpaperFitGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingControls) return@addOnButtonCheckedListener
            val fit = when (checkedId) {
                R.id.wallpaper_fit_fit -> WallpaperTuning.FIT
                R.id.wallpaper_fit_stretch -> WallpaperTuning.STRETCH
                else -> WallpaperTuning.FILL
            }
            viewModel.tune(currentTuning().copy(fit = fit, x = 50, y = 50))
        }
        binding.wallpaperDim.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.tune(currentTuning().copy(dim = value.toInt()))
        }
        binding.wallpaperBlur.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !unsupportedBlur) viewModel.tune(currentTuning().copy(blur = value.toInt()))
        }
        binding.wallpaperZoom.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.tune(currentTuning().copy(zoom = value.toInt()))
        }
        binding.wallpaperPreviewFrame.setOnTouchListener { _, event -> handleReposition(event) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.editor.collect { render(it) } }
            }
        }
        if (viewModel.editor.value.draft == null) viewModel.beginEdit()
    }

    private fun currentTuning(): WallpaperTuning =
        viewModel.editor.value.draft?.tuning ?: WallpaperTuning()

    private fun handleReposition(event: MotionEvent): Boolean {
        val draft = viewModel.editor.value.draft ?: return false
        if (draft.tuning.fit != WallpaperTuning.FILL || viewModel.editor.value.image == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStart = event.x
                dragStartY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                val active = renderer ?: return false
                val updated = active.reposition(draft.tuning, event.x - dragStart, event.y - dragStartY)
                if (updated != draft.tuning) {
                    dragStart = event.x
                    dragStartY = event.y
                    viewModel.tune(updated)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Unit
        }
        return true
    }

    private fun render(state: WallpaperEditorState) {
        val draft = state.draft
        val tuning = draft?.tuning ?: WallpaperTuning()
        renderer?.render(state.image?.bitmap, tuning)
        binding.wallpaperPreviewProgress.isVisible = state.loading
        binding.wallpaperPreviewStatus.isVisible = state.loading || state.applying
        binding.wallpaperPreviewStatus.text = when {
            state.applying -> getString(R.string.wallpaper_saving)
            state.loading -> getString(R.string.wallpaper_loading_image)
            else -> ""
        }
        binding.wallpaperPreviewError.isVisible = state.error != null
        binding.wallpaperPreviewError.text = state.error?.message(requireContext())
        binding.wallpaperApply.isEnabled = state.canApply
        binding.wallpaperApply.setText(if (state.applying) R.string.wallpaper_saving else R.string.wallpaper_apply)
        binding.wallpaperControlsScroll.isEnabled = !state.applying
        binding.wallpaperCancel.isEnabled = !state.applying

        if (binding.wallpaperDim.value != tuning.dim.toFloat()) binding.wallpaperDim.value = tuning.dim.toFloat()
        if (binding.wallpaperBlur.value != tuning.blur.toFloat()) binding.wallpaperBlur.value = tuning.blur.toFloat()
        if (binding.wallpaperZoom.value != tuning.zoom.toFloat()) binding.wallpaperZoom.value = tuning.zoom.toFloat()
        binding.wallpaperDimLabel.text = getString(R.string.wallpaper_dim, tuning.dim)
        binding.wallpaperBlurLabel.text = getString(R.string.wallpaper_blur, tuning.blur)
        binding.wallpaperZoomLabel.text = getString(R.string.wallpaper_zoom, tuning.zoom)

        val checked = when (tuning.fit) {
            WallpaperTuning.FIT -> R.id.wallpaper_fit_fit
            WallpaperTuning.STRETCH -> R.id.wallpaper_fit_stretch
            else -> R.id.wallpaper_fit_fill
        }
        syncingControls = true
        if (binding.wallpaperFitGroup.checkedButtonId != checked) binding.wallpaperFitGroup.check(checked)
        syncingControls = false
        binding.wallpaperFitHelp.setText(
            if (tuning.fit == WallpaperTuning.STRETCH) R.string.wallpaper_stretch_warning else R.string.wallpaper_fit_help
        )
        val fill = tuning.fit == WallpaperTuning.FILL
        binding.wallpaperZoom.isEnabled = fill && !state.applying
        binding.wallpaperResetPosition.isEnabled = fill && !state.applying
        binding.wallpaperPositionHelp.isVisible = fill

        val source = draft?.selection?.online?.source.orEmpty()
        binding.wallpaperSource.isVisible = source.isNotBlank()
        binding.wallpaperSource.setOnClickListener { openWallpaperSource(this, source) }

        discardCallback.isEnabled = !leaving
        if (state.applied && !leaving) {
            leaving = true
            Toast.makeText(requireContext(), R.string.library_background_applied, Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }

    private fun requestExit() {
        if (viewModel.editor.value.applying || leaving) return
        val draft: WallpaperDraft? = viewModel.editor.value.draft
        if (draft == null || !draft.changed) {
            leave()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wallpaper_discard_title)
            .setMessage(R.string.wallpaper_discard_message)
            .setNegativeButton(R.string.wallpaper_keep_editing, null)
            .setPositiveButton(R.string.wallpaper_discard) { _, _ -> leave() }
            .show()
    }

    private fun leave() {
        leaving = true
        viewModel.discardDraft()
        findNavController().popBackStack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        renderer?.close()
        renderer = null
        _binding = null
    }
}

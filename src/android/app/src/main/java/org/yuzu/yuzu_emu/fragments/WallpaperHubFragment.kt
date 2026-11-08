// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentWallpaperHubBinding
import org.yuzu.yuzu_emu.features.settings.ui.OnlineBackgroundAdapter
import org.yuzu.yuzu_emu.features.settings.ui.WallpaperHubState
import org.yuzu.yuzu_emu.features.settings.ui.WallpaperViewModel
import org.yuzu.yuzu_emu.features.settings.ui.applyWallpaperBarInsets
import org.yuzu.yuzu_emu.features.settings.ui.message
import org.yuzu.yuzu_emu.features.settings.ui.respectReducedMotion
import org.yuzu.yuzu_emu.features.settings.ui.wallpaperColumns
import org.yuzu.yuzu_emu.utils.background.OnlineBackground
import org.yuzu.yuzu_emu.utils.background.SecureBackgroundStore
import org.yuzu.yuzu_emu.utils.background.WallpaperImages
import org.yuzu.yuzu_emu.utils.background.WallpaperRenderer
import org.yuzu.yuzu_emu.utils.background.WallpaperSelection
import org.yuzu.yuzu_emu.utils.background.WallpaperSettings
import org.yuzu.yuzu_emu.utils.background.WallpaperSettingsSnapshot

class WallpaperHubFragment : Fragment() {
    private var _binding: FragmentWallpaperHubBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WallpaperViewModel by navGraphViewModels(R.id.wallpaper_graph)

    private var renderer: WallpaperRenderer? = null
    private var previewJob: Job? = null
    private var previewGeneration = 0L
    private val recommendationAdapters = mutableListOf<OnlineBackgroundAdapter>()
    private var popularAdapter: OnlineBackgroundAdapter? = null
    private var applyingPreference = false
    private var renderedRecommendations: Any? = null

    private val pickLocalImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val persisted = runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
        if (!persisted) {
            Toast.makeText(requireContext(), R.string.wallpaper_permission_error, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        viewModel.beginEdit(WallpaperSelection(uri.toString()))
        findNavController().navigate(R.id.wallpaperEditorFragment)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentWallpaperHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applyWallpaperBarInsets(binding.wallpaperToolbar, binding.wallpaperHubScroll)
        binding.wallpaperToolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        renderer = WallpaperRenderer(
            binding.wallpaperPreviewCard.libraryBackground,
            binding.wallpaperPreviewCard.libraryBackgroundScrim
        )
        popularAdapter = OnlineBackgroundAdapter(viewLifecycleOwner) { openEditor(it) }
        binding.wallpaperPopularGrid.layoutManager = GridLayoutManager(requireContext(), wallpaperColumns(requireContext()))
        binding.wallpaperPopularGrid.adapter = popularAdapter
        binding.wallpaperPopularGrid.respectReducedMotion()

        binding.wallpaperEdit.setOnClickListener {
            viewModel.beginEdit(WallpaperSelection(viewModel.committed.value.uri))
            findNavController().navigate(R.id.wallpaperEditorFragment)
        }
        binding.wallpaperSearch.setOnClickListener {
            findNavController().navigate(R.id.wallpaperSearchFragment)
        }
        binding.wallpaperChoose.setOnClickListener { pickLocalImage.launch(arrayOf("image/*")) }
        binding.wallpaperRestore.setOnClickListener { confirmRestoreDefault() }
        binding.wallpaperRefresh.setOnClickListener { viewModel.refreshHub(force = true) }
        binding.wallpaperPersonalized.setOnCheckedChangeListener { _, checked ->
            if (!applyingPreference && checked != viewModel.hub.value.personalized) {
                viewModel.setPersonalized(checked)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.committed.collect { renderPreview(it) } }
                launch { viewModel.hub.collect { renderHub(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshHub()
    }

    private fun openEditor(item: OnlineBackground) {
        viewModel.beginEdit(WallpaperSelection(online = item))
        findNavController().navigate(R.id.wallpaperEditorFragment)
    }

    private fun renderPreview(snapshot: WallpaperSettingsSnapshot) {
        previewJob?.cancel()
        val generation = ++previewGeneration
        renderer?.render(null, snapshot.tuning)
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val image = WallpaperImages.load(requireContext().applicationContext, WallpaperSelection(snapshot.uri))
                if (generation == previewGeneration) renderer?.render(image.bitmap, snapshot.tuning)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == previewGeneration) renderer?.render(null, snapshot.tuning)
            }
        }
    }

    private fun renderHub(state: WallpaperHubState) {
        applyingPreference = true
        binding.wallpaperPersonalized.isChecked = state.personalized
        applyingPreference = false
        binding.wallpaperPersonalized.isEnabled = !state.savingPreference
        binding.wallpaperRefresh.isEnabled = !state.savingPreference
        renderRecommendations(state)
        val popular = popularAdapter ?: return
        popular.submit(state.popular)
        binding.wallpaperPopularStatus.text = when {
            state.popularLoading -> getString(R.string.wallpaper_loading)
            state.popularError != null -> state.popularError.message(requireContext())
            state.popular.isEmpty() -> getString(R.string.wallpaper_no_matches)
            else -> ""
        }
        binding.wallpaperPopularStatus.isVisible = binding.wallpaperPopularStatus.text.isNotEmpty()
    }

    private fun renderRecommendations(state: WallpaperHubState) {
        val container = binding.wallpaperRecommendationGroups
        val status = binding.wallpaperRecommendationStatus
        when {
            !state.personalized -> status.text = getString(R.string.wallpaper_personalized_off)
            state.recommendationsLoading -> status.text = getString(R.string.wallpaper_loading)
            state.recommendationError != null -> status.text = state.recommendationError.message(requireContext())
            state.recommendations.isEmpty() -> status.text = getString(R.string.wallpaper_no_history)
            else -> status.text = ""
        }
        status.isVisible = status.text.isNotEmpty()
        if (!state.personalized) return

        val signature = Triple(state.recommendationsLoading, state.recommendationError, state.recommendations)
        if (signature == renderedRecommendations) return
        renderedRecommendations = signature
        container.removeAllViews()
        recommendationAdapters.clear()

        state.recommendations.forEach { group ->
            container.addView(label(getString(R.string.wallpaper_because, group.game)))
            if (group.items.isEmpty()) {
                container.addView(label(getString(R.string.wallpaper_group_empty), caption = true))
                return@forEach
            }
            val grid = RecyclerView(requireContext()).apply {
                layoutManager = GridLayoutManager(requireContext(), wallpaperColumns(requireContext()))
                isNestedScrollingEnabled = false
                respectReducedMotion()
                adapter = OnlineBackgroundAdapter(viewLifecycleOwner) { openEditor(it) }
                    .also { adapter ->
                        adapter.submit(group.items)
                        recommendationAdapters += adapter
                    }
            }
            container.addView(grid)
        }
    }

    private fun label(text: String, caption: Boolean = false) = TextView(requireContext()).apply {
        this.text = text
        setTextAppearance(
            if (caption) com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
            else com.google.android.material.R.style.TextAppearance_Material3_TitleLarge
        )
        setTextColor(
            MaterialColors.getColor(
                this,
                if (caption) com.google.android.material.R.attr.colorOnSurfaceVariant
                else com.google.android.material.R.attr.colorOnSurface
            )
        )
        setPadding(0, resources.getDimensionPixelSize(R.dimen.spacing_small), 0, 0)
    }

    private fun confirmRestoreDefault() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wallpaper_restore)
            .setMessage(R.string.wallpaper_restore_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wallpaper_restore) { _, _ -> restoreDefault() }
            .show()
    }

    private fun restoreDefault() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                WallpaperSettings.save(WallpaperSettingsSnapshot("", WallpaperSettings.read().tuning))
                SecureBackgroundStore.pruneUnselected("")
            }
            viewModel.refreshHub()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewGeneration++
        previewJob?.cancel()
        renderer?.close()
        renderer = null
        recommendationAdapters.clear()
        renderedRecommendations = null
        popularAdapter = null
        _binding = null
    }
}

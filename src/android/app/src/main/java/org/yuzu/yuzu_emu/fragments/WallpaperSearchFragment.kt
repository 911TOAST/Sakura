// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.FragmentWallpaperSearchBinding
import org.yuzu.yuzu_emu.features.settings.ui.OnlineBackgroundAdapter
import org.yuzu.yuzu_emu.features.settings.ui.WallpaperSearchState
import org.yuzu.yuzu_emu.features.settings.ui.WallpaperViewModel
import org.yuzu.yuzu_emu.features.settings.ui.applyWallpaperBarInsets
import org.yuzu.yuzu_emu.features.settings.ui.applyKeyboardInsets
import org.yuzu.yuzu_emu.features.settings.ui.message
import org.yuzu.yuzu_emu.features.settings.ui.respectReducedMotion
import org.yuzu.yuzu_emu.features.settings.ui.wallpaperColumns
import org.yuzu.yuzu_emu.utils.background.OnlineBackground
import org.yuzu.yuzu_emu.utils.background.OnlineBackgroundCatalog
import org.yuzu.yuzu_emu.utils.background.WallpaperProviders
import org.yuzu.yuzu_emu.utils.background.WallpaperSelection

class WallpaperSearchFragment : Fragment() {
    private var _binding: FragmentWallpaperSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WallpaperViewModel by navGraphViewModels(R.id.wallpaper_graph)
    private var adapter: OnlineBackgroundAdapter? = null
    private var restoring = false
    private var pendingScroll: android.os.Parcelable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentWallpaperSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applyWallpaperBarInsets(
            binding.wallpaperToolbar, binding.wallpaperSearchGrid, binding.wallpaperSearchBottom
        )
        binding.wallpaperSearchGrid.applyKeyboardInsets()
        binding.wallpaperToolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        adapter = OnlineBackgroundAdapter(viewLifecycleOwner) { openEditor(it) }
        binding.wallpaperSearchGrid.layoutManager = GridLayoutManager(requireContext(), wallpaperColumns(requireContext()))
        binding.wallpaperSearchGrid.adapter = adapter
        binding.wallpaperSearchGrid.respectReducedMotion()

        restoring = true
        binding.wallpaperQuery.setText(viewModel.search.value.spec.query)
        binding.wallpaperQuery.setSelection(binding.wallpaperQuery.text?.length ?: 0)
        restoring = false

        binding.wallpaperQuery.doAfterTextChanged { editable ->
            if (restoring) return@doAfterTextChanged
            viewModel.search(viewModel.search.value.spec.copy(query = editable?.toString().orEmpty()), debounce = true)
        }
        binding.wallpaperQuery.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.search(viewModel.search.value.spec, debounce = false, manual = true)
                true
            } else {
                false
            }
        }
        binding.wallpaperProvider.setOnClickListener { chooseProvider() }
        binding.wallpaperOrientation.setOnClickListener { chooseFilter(Filter.ORIENTATION) }
        binding.wallpaperResolution.setOnClickListener { chooseFilter(Filter.RESOLUTION) }
        binding.wallpaperSort.setOnClickListener { chooseFilter(Filter.SORT) }
        binding.wallpaperAdvanced.setOnClickListener { showAdvancedDialog() }
        binding.wallpaperSurprise.setOnClickListener { viewModel.surpriseMe() }
        binding.wallpaperSearchMore.setOnClickListener {
            if (viewModel.search.value.error != null) viewModel.retrySearch() else viewModel.nextPage()
        }
        binding.wallpaperSearchGrid.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                viewModel.searchScroll = recyclerView.layoutManager?.onSaveInstanceState()
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.search.collect { renderSearch(it) } }
                launch { viewModel.history.collect { renderChips() } }
            }
        }
        renderChips()
        pendingScroll = viewModel.searchScroll
        if (!viewModel.search.value.started) {
            viewModel.search(viewModel.search.value.spec, debounce = false)
        }
    }

    private fun restoreScroll() {
        val saved = pendingScroll ?: return
        pendingScroll = null
        binding.wallpaperSearchGrid.post {
            binding.wallpaperSearchGrid.layoutManager?.onRestoreInstanceState(saved)
        }
    }

    private fun openEditor(item: OnlineBackground) {
        viewModel.beginEdit(WallpaperSelection(online = item))
        findNavController().navigate(R.id.wallpaperEditorFragment)
    }

    private enum class Filter { ORIENTATION, RESOLUTION, SORT }

    private fun chooseProvider() {
        val spec = viewModel.search.value.spec
        val selected = WallpaperProviders.ids.indexOf(WallpaperProviders.normalize(spec.provider))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wallpaper_provider_title)
            .setSingleChoiceItems(R.array.wallpaper_provider_names, selected) { dialog, which ->
                val provider = WallpaperProviders.ids[which]
                // A persisted catalog would override the provider, so leave it behind explicitly.
                OnlineBackgroundCatalog.setCatalogUrl(requireContext(), "")
                viewModel.search(spec.copy(provider = provider, catalogUrl = ""), debounce = false)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseFilter(filter: Filter) {
        val spec = viewModel.search.value.spec
        val (title, values, selected) = when (filter) {
            Filter.ORIENTATION -> Triple(R.string.wallpaper_orientation, R.array.wallpaper_orientations, spec.orientation)
            Filter.RESOLUTION -> Triple(R.string.wallpaper_resolution, R.array.wallpaper_resolutions, spec.resolution)
            Filter.SORT -> Triple(R.string.wallpaper_sort, R.array.wallpaper_sorts, spec.sort)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(values, selected) { dialog, which ->
                val updated = when (filter) {
                    Filter.ORIENTATION -> spec.copy(orientation = which)
                    Filter.RESOLUTION -> spec.copy(resolution = which)
                    Filter.SORT -> spec.copy(sort = which)
                }
                viewModel.search(updated, debounce = false)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAdvancedDialog() {
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = resources.getDimensionPixelSize(R.dimen.spacing_large)
            setPadding(padding, padding, padding, 0)
        }
        val notice = android.widget.TextView(requireContext()).apply {
            setText(R.string.wallpaper_catalog_notice)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.wallpaper_catalog_url)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(OnlineBackgroundCatalog.catalogUrl(requireContext()))
        }
        container.addView(notice)
        container.addView(input)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wallpaper_advanced)
            .setView(container)
            .setNeutralButton(R.string.wallpaper_source_wallhaven) { _, _ ->
                OnlineBackgroundCatalog.setCatalogUrl(requireContext(), "")
                viewModel.search(
                    viewModel.search.value.spec.copy(catalogUrl = "", provider = WallpaperProviders.WALLHAVEN),
                    debounce = false
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.wallpaper_source_catalog, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text?.toString()?.trim().orEmpty()
                val parsed = url.toHttpUrlOrNull()
                if (parsed == null || !parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                    Toast.makeText(requireContext(), R.string.wallpaper_invalid_url, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                OnlineBackgroundCatalog.setCatalogUrl(requireContext(), parsed.toString())
                viewModel.search(viewModel.search.value.spec.copy(catalogUrl = parsed.toString()), debounce = false)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun renderChips() {
        val container = binding.wallpaperSuggestions
        container.removeAllViews()
        binding.wallpaperSuggestionsLabel.setText(
            if (viewModel.history.value.isEmpty()) R.string.wallpaper_topics else R.string.wallpaper_history
        )
        container.addView(suggestion(getString(R.string.wallpaper_clear_history)) {
            viewModel.clearHistory()
        }.also { it.isVisible = viewModel.history.value.isNotEmpty() })
        viewModel.history.value.forEach { query ->
            container.addView(suggestion(query) { applyQuery(query) })
        }
        val names = resources.getStringArray(R.array.wallpaper_topic_names)
        val queries = resources.getStringArray(R.array.wallpaper_topic_queries)
        names.forEachIndexed { index, name ->
            val query = queries.getOrNull(index) ?: return@forEachIndexed
            container.addView(suggestion(name) { applyQuery(query) })
        }
    }

    private fun suggestion(text: String, onClick: () -> Unit) = (
        layoutInflater.inflate(R.layout.item_wallpaper_suggestion, binding.wallpaperSuggestions, false) as Chip
        ).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun applyQuery(query: String) {
        restoring = true
        binding.wallpaperQuery.setText(query)
        binding.wallpaperQuery.setSelection(query.length)
        restoring = false
        viewModel.search(viewModel.search.value.spec.copy(query = query), debounce = false, manual = true)
    }

    private fun renderSearch(state: WallpaperSearchState) {
        adapter?.submit(state.items) {
            if (isAdded && state.items.isNotEmpty()) restoreScroll()
        }
        val provider = WallpaperProviders.normalize(state.spec.provider)
        binding.wallpaperProvider.text = resources.getString(
            R.string.wallpaper_filter_value,
            getString(R.string.wallpaper_provider),
            resources.getStringArray(R.array.wallpaper_provider_names)[WallpaperProviders.ids.indexOf(provider)]
        )
        binding.wallpaperOrientation.text = resources.getString(
            R.string.wallpaper_filter_value,
            getString(R.string.wallpaper_orientation),
            resources.getStringArray(R.array.wallpaper_orientations)[state.spec.orientation.coerceIn(0, 3)]
        )
        binding.wallpaperResolution.text = resources.getString(
            R.string.wallpaper_filter_value,
            getString(R.string.wallpaper_resolution),
            resources.getStringArray(R.array.wallpaper_resolutions)[state.spec.resolution.coerceIn(0, 2)]
        )
        binding.wallpaperSort.text = resources.getString(
            R.string.wallpaper_filter_value,
            getString(R.string.wallpaper_sort),
            resources.getStringArray(R.array.wallpaper_sorts)[state.spec.sort.coerceIn(0, 2)]
        )
        binding.wallpaperAdvanced.isChecked = state.spec.catalogUrl.isNotBlank()
        binding.wallpaperAdvanced.setText(
            if (state.spec.catalogUrl.isBlank()) R.string.wallpaper_advanced else R.string.wallpaper_source_catalog
        )
        binding.wallpaperSort.isEnabled = state.spec.catalogUrl.isBlank() && provider != WallpaperProviders.BING
        binding.wallpaperSourceNotice.setText(
            when {
                state.spec.catalogUrl.isNotBlank() -> R.string.wallpaper_catalog_notice
                provider == WallpaperProviders.SAFEBOORU -> R.string.wallpaper_safebooru_note
                provider == WallpaperProviders.BING -> R.string.wallpaper_bing_note
                else -> R.string.wallpaper_discovery_note
            }
        )
        binding.wallpaperSearchProgress.isVisible = state.loading
        binding.wallpaperSearchStatus.text = when {
            state.error != null -> state.error.message(requireContext())
            state.started && !state.loading && state.items.isEmpty() && state.page >= state.lastPage ->
                getString(R.string.wallpaper_no_matches)
            state.loading && state.items.isEmpty() -> getString(R.string.wallpaper_loading)
            else -> ""
        }
        binding.wallpaperSearchStatus.isVisible = binding.wallpaperSearchStatus.text.isNotEmpty()
        val more = state.started && (state.error != null || (!state.loading && state.page < state.lastPage))
        binding.wallpaperSearchMore.isVisible = more
        binding.wallpaperSearchMore.setText(if (state.error != null) R.string.wallpaper_retry else R.string.wallpaper_more)
        binding.wallpaperSearchMore.isEnabled = !state.loading
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.searchScroll = binding.wallpaperSearchGrid.layoutManager?.onSaveInstanceState()
        adapter = null
        _binding = null
    }
}

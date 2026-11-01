// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.adapters.GameAdapter
import org.yuzu.yuzu_emu.databinding.FragmentGamesBinding
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.model.AppletInfo
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.model.HomeViewModel
import org.yuzu.yuzu_emu.ui.main.MainActivity
import org.yuzu.yuzu_emu.utils.SuyuMotion
import org.yuzu.yuzu_emu.utils.ViewUtils.updateMargins
import org.yuzu.yuzu_emu.utils.collect
import info.debatty.java.stringsimilarity.Jaccard
import info.debatty.java.stringsimilarity.JaroWinkler
import java.util.Locale
import androidx.core.content.edit
import androidx.core.view.doOnNextLayout

class GamesFragment : Fragment() {
    private var _binding: FragmentGamesBinding? = null
    private val binding get() = _binding!!

    private var lastViewType: Int = GameAdapter.VIEW_TYPE_GRID
    private var fallbackBottomInset: Int = 0
    private var displayedGameCount: Int = 0
    private var navigatingToSettings: Boolean = false
    private var isLoadingGames: Boolean = false
    private var hasAnimatedEntrance: Boolean = false
    private var emptyStateShown: Boolean = false

    companion object {
        private const val SEARCH_TEXT = "SearchText"
        private const val PREF_SORT_TYPE = "GamesSortType"
    }

    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()
    private lateinit var gameAdapter: GameAdapter

    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)

    private lateinit var mainActivity: MainActivity
    private val getGamesDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result ->
            if (result != null) {
                mainActivity.processGamesDir(result, true)
            }
        }

    private fun getCurrentViewType(): Int {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (isLandscape) CarouselRecyclerView.CAROUSEL_VIEW_TYPE_LANDSCAPE else CarouselRecyclerView.CAROUSEL_VIEW_TYPE_PORTRAIT
        val fallback = if (isLandscape) GameAdapter.VIEW_TYPE_CAROUSEL else GameAdapter.VIEW_TYPE_GRID
        return preferences.getInt(key, fallback)
    }

    private fun setCurrentViewType(type: Int) {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (isLandscape) CarouselRecyclerView.CAROUSEL_VIEW_TYPE_LANDSCAPE else CarouselRecyclerView.CAROUSEL_VIEW_TYPE_PORTRAIT
        preferences.edit { putInt(key, type) }
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGamesBinding.inflate(inflater)
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setStatusBarShadeVisibility(true)
        mainActivity = requireActivity() as MainActivity
        navigatingToSettings = false

        if (savedInstanceState != null) {
            binding.searchText.setText(savedInstanceState.getString(SEARCH_TEXT))
        }

        gameAdapter = GameAdapter(
            requireActivity() as AppCompatActivity
        )

        applyGridGamesBinding()

        binding.swipeRefresh.apply {
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setOnRefreshListener {
                gamesViewModel.reloadGames(false)
            }
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setProgressBackgroundColorSchemeColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.swipeRefresh,
                    com.google.android.material.R.attr.colorPrimary
                )
            )
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setColorSchemeColors(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.swipeRefresh,
                    com.google.android.material.R.attr.colorOnPrimary
                )
            )
            post {
                if (_binding == null) {
                    return@post
                }
                (binding.swipeRefresh as? SwipeRefreshLayout)?.isRefreshing = gamesViewModel.isReloading.value
            }
        }

        gamesViewModel.isReloading.collect(viewLifecycleOwner) {
            (binding.swipeRefresh as? SwipeRefreshLayout)?.isRefreshing = it
            isLoadingGames = it
            updateEmptyState()
        }
        gamesViewModel.games.collect(viewLifecycleOwner) {
            if (it.isNotEmpty()) {
                setAdapter(it)
            } else {
                gameAdapter.submitList(emptyList())
                gamesViewModel.setFilteredGames(emptyList())
                displayedGameCount = 0
                updateEmptyState()
            }
        }
        gamesViewModel.shouldSwapData.collect(
            viewLifecycleOwner,
            resetState = { gamesViewModel.setShouldSwapData(false) }
        ) {
            if (it) {
                setAdapter(gamesViewModel.games.value)
            }
        }
        gamesViewModel.shouldScrollToTop.collect(
            viewLifecycleOwner,
            resetState = { gamesViewModel.setShouldScrollToTop(false) }
        ) { if (it) scrollToTop() }

        gamesViewModel.shouldScrollAfterReload.collect(viewLifecycleOwner) { shouldScroll ->
            if (shouldScroll) {
                binding.gridGames.post {
                    (binding.gridGames as? CarouselRecyclerView)?.pendingScrollAfterReload = true
                    gameAdapter.notifyDataSetChanged()
                }
                gamesViewModel.setShouldScrollAfterReload(false)
            }
        }

        setupTopView()

        updateButtonsVisibility()
        displayedGameCount = gamesViewModel.games.value.size
        updateLibraryCount()

        binding.addDirectory.setOnClickListener {
            chooseGamesDirectory()
        }

        binding.buttonEmptyAdd.setOnClickListener {
            if (gamesViewModel.games.value.isEmpty()) {
                chooseGamesDirectory()
            } else {
                resetLibraryFilters()
            }
        }

        // The floating pill handles system bars with margins, so swallow the
        // insets the widget would otherwise convert into internal padding.
        ViewCompat.setOnApplyWindowInsetsListener(binding.navBar) { _, _ ->
            WindowInsetsCompat.CONSUMED
        }

        setInsets()
        animateScreenEntrance()
    }

    /**
     * Raw M3 screen entrance: brand, search, filters and nav settle in with a
     * short top-down cascade on the emphasized curve.
     */
    private fun animateScreenEntrance() {
        val context = requireContext()
        val decelerate = SuyuMotion.emphasizedDecelerate(context)
        val duration = SuyuMotion.durationMedium2(context)
        val rise = resources.getDimensionPixelSize(R.dimen.spacing_med).toFloat()

        val targets = listOf(
            Triple(binding.header, 0L, rise),
            Triple(binding.frameSearch, 70L, rise),
            Triple(binding.filterScroll, 130L, rise)
        )
        targets.forEach { (view, delay, offset) ->
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = offset
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(duration)
                .setInterpolator(decelerate)
                .start()
        }

        // The pill rises on a real spring so the bar lands with physical weight.
        binding.navBar.animate().cancel()
        binding.navBar.alpha = 0f
        binding.navBar.translationY = rise * 3
        binding.navBar.animate()
            .alpha(1f)
            .setStartDelay(180L)
            .setDuration(220L)
            .setInterpolator(decelerate)
            .start()
        SpringAnimation(binding.navBar, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring = SpringForce(0f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                stiffness = SpringForce.STIFFNESS_LOW
            }
            start()
        }
    }

    /**
     * Spring pop on the newly selected pill destination.
     */
    private fun animateNavSelection(itemId: Int) {
        val index = (0 until binding.navBar.menu.size())
            .firstOrNull { binding.navBar.menu.getItem(it).itemId == itemId } ?: return
        val menuView = binding.navBar.getChildAt(0) as? ViewGroup ?: return
        val itemView = menuView.getChildAt(index) ?: return

        itemView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        itemView.scaleX = 0.82f
        itemView.scaleY = 0.82f
        listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y).forEach { property ->
            SpringAnimation(itemView, property, 1f).apply {
                spring = SpringForce(1f).apply {
                    dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                    stiffness = SpringForce.STIFFNESS_MEDIUM
                }
                start()
            }
        }
    }

    val applyGridGamesBinding = {
        (binding.gridGames as? RecyclerView)?.apply {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val currentViewType = getCurrentViewType()
            val savedViewType = if (isLandscape || currentViewType != GameAdapter.VIEW_TYPE_CAROUSEL) currentViewType else GameAdapter.VIEW_TYPE_GRID

            //This prevents Grid/List views from reusing scaled or otherwise modified ViewHolders left over from the carousel.
            adapter = null
            recycledViewPool.clear()

            gameAdapter.setViewType(savedViewType)
            binding.viewIcon.setImageResource(
                when (savedViewType) {
                    GameAdapter.VIEW_TYPE_GRID -> R.drawable.ic_m3_grid_view
                    GameAdapter.VIEW_TYPE_GRID_COMPACT -> R.drawable.ic_m3_view_module
                    GameAdapter.VIEW_TYPE_LIST -> R.drawable.ic_m3_view_agenda
                    GameAdapter.VIEW_TYPE_CAROUSEL -> R.drawable.ic_m3_view_carousel
                    else -> R.drawable.ic_m3_grid_view
                }
            )
            // Raw M3 emphasis swap for the view-mode glyph.
            binding.viewIcon.animate().cancel()
            binding.viewIcon.scaleX = 0.75f
            binding.viewIcon.scaleY = 0.75f
            binding.viewIcon.alpha = 0.4f
            binding.viewIcon.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(SuyuMotion.durationShort4(requireContext()))
                .setInterpolator(SuyuMotion.emphasizedDecelerate(requireContext()))
                .start()
            currentFilter = preferences.getInt(PREF_SORT_TYPE, View.NO_ID)

            // Set the correct layout manager
            layoutManager = when (savedViewType) {
                GameAdapter.VIEW_TYPE_GRID -> {
                    val columns = resources.getInteger(R.integer.game_columns_grid)
                    GridLayoutManager(context, columns)
                }
                GameAdapter.VIEW_TYPE_GRID_COMPACT -> {
                    val columns = resources.getInteger(R.integer.game_columns_grid)
                    GridLayoutManager(context, columns)
                }
                GameAdapter.VIEW_TYPE_LIST -> {
                    val columns = resources.getInteger(R.integer.game_columns_list)
                    GridLayoutManager(context, columns)
                }
                GameAdapter.VIEW_TYPE_CAROUSEL -> {
                    LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                }
                else -> throw IllegalArgumentException("Invalid view type: $savedViewType")
            }
            if (savedViewType == GameAdapter.VIEW_TYPE_CAROUSEL) {
                (binding.gridGames as? View)?.let { it -> ViewCompat.requestApplyInsets(it)}
                doOnNextLayout { //Carousel: important to avoid overlap issues
                    (this as? CarouselRecyclerView)?.notifyLaidOut(fallbackBottomInset)
                }
            } else {
                (this as? CarouselRecyclerView)?.setupCarousel(false)
            }
            adapter = gameAdapter
            lastViewType = savedViewType

            // Replay the staggered M3 entrance whenever the user rebinds with another view mode.
            if (savedViewType != GameAdapter.VIEW_TYPE_CAROUSEL) {
                gameAdapter.playEntranceAnimation()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) {
            outState.putString(SEARCH_TEXT, binding.searchText.text.toString())
        }
    }

    override fun onPause() {
        super.onPause()
        if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) {
            gamesViewModel.lastScrollPosition = (binding.gridGames as? CarouselRecyclerView)?.getClosestChildPosition() ?: 0
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updateButtonsVisibility()
            updateEmptyState()
        }
        if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) {
            (binding.gridGames as? CarouselRecyclerView)?.setupCarousel(true)
            (binding.gridGames as? CarouselRecyclerView)?.restoreScrollState(gamesViewModel.lastScrollPosition)
        }
    }

    private fun setAdapter(games: List<Game>) {
        val currentSearchText = binding.searchText.text.toString()
        if (currentSearchText.isNotEmpty() || currentFilter != View.NO_ID) {
            filterAndSearch(games)
        } else {
            submitFilteredGames(games)
        }
    }

    /**
     * Raw M3 reveal/conceal for the search-clear icon: emphasized fade, no snap.
     */
    private fun setSearchClearVisible(visible: Boolean) {
        if (visible) {
            if (binding.clearButton.visibility != View.INVISIBLE) return
            binding.clearButton.visibility = View.VISIBLE
            binding.clearButton.alpha = 0f
            binding.clearButton.animate()
                .alpha(1f)
                .setDuration(SuyuMotion.durationShort4(requireContext()))
                .setInterpolator(SuyuMotion.emphasizedDecelerate(requireContext()))
                .start()
        } else {
            if (binding.clearButton.visibility == View.INVISIBLE) return
            if (binding.clearButton.alpha == 0f) {
                binding.clearButton.visibility = View.INVISIBLE
                return
            }
            binding.clearButton.animate()
                .alpha(0f)
                .setDuration(SuyuMotion.durationShort2(requireContext()))
                .setInterpolator(SuyuMotion.emphasizedAccelerate(requireContext()))
                .withEndAction { binding.clearButton.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun setupTopView() {
        binding.searchText.doOnTextChanged() { text: CharSequence?, _: Int, _: Int, _: Int ->
            setSearchClearVisible(text.toString().isNotEmpty())
            filterAndSearch()
        }

        binding.clearButton.setOnClickListener {
            binding.searchText.setText("")
        }
        binding.searchBackground.setOnClickListener { focusSearch() }

        // Setup view button
        binding.viewButton.setOnClickListener {
            showViewMenu(it)
        }

        // Setup quick filters
        when (currentFilter) {
            R.id.alphabetical -> binding.filterChips.check(R.id.filter_alphabetical)
            R.id.filter_recently_played -> binding.filterChips.check(R.id.filter_recent)
            R.id.filter_recently_added -> binding.filterChips.check(R.id.filter_new)
            else -> binding.filterChips.check(R.id.filter_all)
        }
        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.filter_alphabetical -> R.id.alphabetical
                R.id.filter_recent -> R.id.filter_recently_played
                R.id.filter_new -> R.id.filter_recently_added
                else -> View.NO_ID
            }
            preferences.edit { putInt(PREF_SORT_TYPE, currentFilter) }
            filterAndSearch()
        }

        // Setup the persistent bottom destinations
        binding.navBar.selectedItemId = R.id.nav_library
        binding.navBar.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_qlaunch -> {
                    launchQLaunch()
                    false
                }

                R.id.nav_settings -> {
                    navigateToSettings()
                    false
                }

                else -> {
                    animateNavSelection(item.itemId)
                    true
                }
            }
        }
    }

    private fun chooseGamesDirectory() {
        getGamesDirectory.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
    }

    private fun resetLibraryFilters() {
        currentFilter = View.NO_ID
        preferences.edit { putInt(PREF_SORT_TYPE, currentFilter) }
        binding.filterChips.check(R.id.filter_all)
        binding.searchText.setText("")
        filterAndSearch()
    }

    private fun navigateToSettings() {
        if (navigatingToSettings) return
        navigatingToSettings = true

        // The pill drops out of the way first, then the settings panel swipes in from the left.
        binding.navBar.animate().cancel()
        binding.navBar.animate()
            .translationY(
                binding.navBar.height.toFloat() +
                    resources.getDimensionPixelSize(R.dimen.spacing_medlarge)
            )
            .alpha(0f)
            .setDuration(SuyuMotion.durationShort4(requireContext()))
            .setInterpolator(SuyuMotion.emphasizedAccelerate(requireContext()))
            .withEndAction {
                findNavController()
                    .navigate(R.id.action_gamesFragment_to_homeSettingsFragment)
            }
            .start()
    }

    private fun showViewMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_game_views, popup.menu)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!isLandscape) {
            popup.menu.findItem(R.id.view_carousel)?.isVisible = false
        }

        val currentViewType = getCurrentViewType()
        when (currentViewType) {
            GameAdapter.VIEW_TYPE_LIST -> popup.menu.findItem(R.id.view_list).isChecked = true
            GameAdapter.VIEW_TYPE_GRID_COMPACT -> popup.menu.findItem(R.id.view_grid_compact).isChecked = true
            GameAdapter.VIEW_TYPE_GRID -> popup.menu.findItem(R.id.view_grid).isChecked = true
            GameAdapter.VIEW_TYPE_CAROUSEL -> popup.menu.findItem(R.id.view_carousel).isChecked = true
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.view_grid -> {
                    if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) onPause()
                    setCurrentViewType(GameAdapter.VIEW_TYPE_GRID)
                    applyGridGamesBinding()
                    item.isChecked = true
                    true
                }

                R.id.view_grid_compact -> {
                    if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) onPause()
                    setCurrentViewType(GameAdapter.VIEW_TYPE_GRID_COMPACT)
                    applyGridGamesBinding()
                    item.isChecked = true
                    true
                }

                R.id.view_list -> {
                    if (getCurrentViewType() == GameAdapter.VIEW_TYPE_CAROUSEL) onPause()
                    setCurrentViewType(GameAdapter.VIEW_TYPE_LIST)
                    applyGridGamesBinding()
                    item.isChecked = true
                    true
                }

                R.id.view_carousel -> {
                    if (!item.isChecked || getCurrentViewType() != GameAdapter.VIEW_TYPE_CAROUSEL) {
                        setCurrentViewType(GameAdapter.VIEW_TYPE_CAROUSEL)
                        applyGridGamesBinding()
                        item.isChecked = true
                        onResume()
                    }
                    true
                }

                else -> false
            }
        }

        popup.show()
    }

    // Track current filter
    private var currentFilter = View.NO_ID

    private fun filterAndSearch(baseList: List<Game> = gamesViewModel.games.value) {
        val filteredList: List<Game> = when (currentFilter) {
            R.id.alphabetical -> baseList.sortedBy { it.title }
            R.id.filter_recently_played -> {
                baseList.filter {
                    val lastPlayedTime = preferences.getLong(it.keyLastPlayedTime, 0L)
                    lastPlayedTime > (System.currentTimeMillis() - 24 * 60 * 60 * 1000)
                }.sortedByDescending { preferences.getLong(it.keyLastPlayedTime, 0L) }
            }
            R.id.filter_recently_added -> {
                baseList.filter {
                    val addedTime = preferences.getLong(it.keyAddedToLibraryTime, 0L)
                    addedTime > (System.currentTimeMillis() - 24 * 60 * 60 * 1000)
                }.sortedByDescending { preferences.getLong(it.keyAddedToLibraryTime, 0L) }
            }
            else -> baseList
        }

        val searchTerm = binding.searchText.text.toString().lowercase(Locale.getDefault())
        if (searchTerm.isEmpty()) {
            submitFilteredGames(filteredList)
            return
        }

        val searchAlgorithm = if (searchTerm.length > 1) Jaccard(2) else JaroWinkler()
        val sortedList = filteredList.mapNotNull { game ->
            val title = game.title.lowercase(Locale.getDefault())
            val score = searchAlgorithm.similarity(searchTerm, title)
            if (score > 0.03) {
                ScoredGame(score, game)
            } else {
                null
            }
        }.sortedByDescending { it.score }.map { it.item }

        submitFilteredGames(sortedList)
    }

    private fun submitFilteredGames(games: List<Game>) {
        // Raw M3 entrance: staggered fade+slide tiles on the first library load.
        if (!hasAnimatedEntrance && games.isNotEmpty()) {
            hasAnimatedEntrance = true
            if (getCurrentViewType() != GameAdapter.VIEW_TYPE_CAROUSEL) {
                gameAdapter.playEntranceAnimation(games.size)
            }
        }
        ((binding.gridGames as? RecyclerView)?.adapter as? GameAdapter)?.submitList(games)
        gamesViewModel.setFilteredGames(games)
        displayedGameCount = games.size
        updateEmptyState()
    }

    private fun updateLibraryCount() {
        if (_binding == null) return
        val text = getString(
            if (displayedGameCount == 1) R.string.home_games_count_one
            else R.string.home_games_count,
            displayedGameCount
        )
        if (binding.libraryCount.text.toString() == text) return
        binding.libraryCount.text = text
        // Raw M3 accent: subtle emphasized settle whenever the count changes.
        binding.libraryCount.animate().cancel()
        binding.libraryCount.scaleX = 0.92f
        binding.libraryCount.scaleY = 0.92f
        binding.libraryCount.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SuyuMotion.durationShort4(requireContext()))
            .setInterpolator(SuyuMotion.emphasizedDecelerate(requireContext()))
            .start()
    }

    private fun updateEmptyState() {
        if (_binding == null) return

        updateLibraryCount()

        val libraryIsEmpty = gamesViewModel.games.value.isEmpty()
        val shouldShowFab = BooleanSetting.ENABLE_FOLDER_BUTTON.getBoolean() && !libraryIsEmpty
        if (shouldShowFab) {
            if (binding.addDirectory.isOrWillBeHidden) binding.addDirectory.show()
        } else {
            if (binding.addDirectory.isOrWillBeShown) binding.addDirectory.hide()
        }
        val shouldShow = displayedGameCount == 0 && !isLoadingGames
        if (shouldShow != emptyStateShown) {
            emptyStateShown = shouldShow
            // Cancels any in-flight entrance/exit; its end action never fires on cancel.
            binding.emptyState.animate().cancel()
            if (shouldShow) {
                binding.emptyState.isVisible = true
                binding.emptyState.bringToFront()

                if (libraryIsEmpty) {
                    binding.emptyStateTitle.setText(R.string.empty_library_title)
                    binding.noticeText.setText(R.string.empty_library_message)
                    binding.buttonEmptyAdd.setText(R.string.choose_game_folder)
                    binding.buttonEmptyAdd.setIconResource(R.drawable.ic_m3_create_new_folder)
                } else {
                    binding.emptyStateTitle.setText(R.string.empty_results_title)
                    binding.noticeText.setText(R.string.empty_results_message)
                    binding.buttonEmptyAdd.setText(R.string.clear_search_and_filters)
                    binding.buttonEmptyAdd.setIconResource(R.drawable.ic_m3_close)
                }

                // Raw M3 entrance: fade-through slide on the emphasized curve.
                binding.emptyState.alpha = 0f
                binding.emptyState.translationY =
                    resources.getDimensionPixelSize(R.dimen.spacing_large).toFloat()
                binding.emptyState.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(SuyuMotion.durationMedium2(requireContext()))
                    .setInterpolator(SuyuMotion.emphasizedDecelerate(requireContext()))
                    .start()

                // Layered M3 cascade: icon block, copy and action settle in order.
                val decelerate = SuyuMotion.emphasizedDecelerate(requireContext())
                val duration = SuyuMotion.durationMedium2(requireContext())
                val rise = resources.getDimensionPixelSize(R.dimen.spacing_med).toFloat()
                listOf(
                    binding.emptyStateTitle,
                    binding.noticeText,
                    binding.buttonEmptyAdd
                ).forEachIndexed { index, child ->
                    child.animate().cancel()
                    child.alpha = 0f
                    child.translationY = rise
                    child.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setStartDelay(100L + index * 60L)
                        .setDuration(duration)
                        .setInterpolator(decelerate)
                        .start()
                }
            } else {
                // Raw M3 exit: quick emphasized-accelerate fade-out.
                binding.emptyState.animate()
                    .alpha(0f)
                    .setDuration(SuyuMotion.durationShort4(requireContext()))
                    .setInterpolator(SuyuMotion.emphasizedAccelerate(requireContext()))
                    .withEndAction { binding.emptyState.isVisible = false }
                    .start()
            }
        }
    }

    private inner class ScoredGame(val score: Double, val item: Game)

    private fun focusSearch() {
        binding.searchText.requestFocus()
        val imm = requireActivity()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        imm?.showSoftInput(binding.searchText, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Reset transient view state so a fresh binding gets the correct entrance.
        emptyStateShown = false
        hasAnimatedEntrance = false
        _binding = null
    }

    private fun scrollToTop() {
        if (_binding != null) {
            (binding.gridGames as? CarouselRecyclerView)?.smoothScrollToPosition(0)
        }
    }

    private fun launchQLaunch() {
        try {
            val appletPath = NativeLibrary.getAppletLaunchPath(AppletInfo.QLaunch.entryId)
            if (appletPath.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.applets_error_applet,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            NativeLibrary.setCurrentAppletId(AppletInfo.QLaunch.appletId)

            val qlaunchGame = Game(
                title = getString(R.string.qlaunch_applet),
                path = appletPath
            )

            val action = HomeNavigationDirections.actionGlobalEmulationActivity(qlaunchGame)
            findNavController().navigate(action)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to launch QLaunch: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateButtonsVisibility() {
        val showQLaunch = BooleanSetting.ENABLE_QLAUNCH_BUTTON.getBoolean()
        val isFirmwareAvailable = NativeLibrary.isFirmwareAvailable()

        val shouldShowQLaunch = showQLaunch && isFirmwareAvailable
        binding.navBar.menu.findItem(R.id.nav_qlaunch)?.isVisible = shouldShowQLaunch
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            (binding.swipeRefresh as? SwipeRefreshLayout)?.setProgressViewEndTarget(
                false,
                barInsets.top + resources.getDimensionPixelSize(R.dimen.spacing_refresh_end)
            )

            val leftInset = barInsets.left + cutoutInsets.left
            val rightInset = barInsets.right + cutoutInsets.right
            val topInset = maxOf(barInsets.top, cutoutInsets.top)
            val bottomInset = maxOf(
                barInsets.bottom,
                cutoutInsets.bottom,
                windowInsets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            )

            val mlpSwipe = binding.swipeRefresh.layoutParams as ViewGroup.MarginLayoutParams
            mlpSwipe.leftMargin = leftInset
            mlpSwipe.rightMargin = rightInset
            binding.swipeRefresh.layoutParams = mlpSwipe

            val mlpHeader = binding.header.layoutParams as ViewGroup.MarginLayoutParams
            mlpHeader.leftMargin = leftInset
            mlpHeader.rightMargin = rightInset
            mlpHeader.topMargin = topInset
            binding.header.layoutParams = mlpHeader

            binding.libraryHeader.updatePadding(
                left = leftInset + resources.getDimensionPixelSize(R.dimen.spacing_chip),
                right = rightInset + resources.getDimensionPixelSize(R.dimen.spacing_chip)
            )
            binding.frameSearch.updateMargins(
                left = leftInset + resources.getDimensionPixelSize(R.dimen.spacing_chip),
                right = rightInset + resources.getDimensionPixelSize(R.dimen.spacing_chip)
            )
            binding.filterScroll.updatePadding(
                left = leftInset + resources.getDimensionPixelSize(R.dimen.spacing_chip),
                right = rightInset + resources.getDimensionPixelSize(R.dimen.spacing_chip)
            )

            // The pill consumes its own insets (see onViewCreated) and floats above
            // the system bars with real margins instead of internal padding.
            val mlpNav = binding.navBar.layoutParams as ViewGroup.MarginLayoutParams
            mlpNav.leftMargin = leftInset + resources.getDimensionPixelSize(R.dimen.spacing_large)
            mlpNav.rightMargin = rightInset + resources.getDimensionPixelSize(R.dimen.spacing_large)
            mlpNav.bottomMargin =
                barInsets.bottom + resources.getDimensionPixelSize(R.dimen.spacing_medlarge)
            binding.navBar.layoutParams = mlpNav

            binding.gridGames.updatePadding(top = resources.getDimensionPixelSize(R.dimen.spacing_small))

            val mlpFab = binding.addDirectory.layoutParams as ViewGroup.MarginLayoutParams
            mlpFab.rightMargin = rightInset + resources.getDimensionPixelSize(R.dimen.spacing_chip)
            binding.addDirectory.layoutParams = mlpFab

            fallbackBottomInset = bottomInset
            (binding.gridGames as? CarouselRecyclerView)?.notifyInsetsReady(bottomInset)
            windowInsets
        }
}

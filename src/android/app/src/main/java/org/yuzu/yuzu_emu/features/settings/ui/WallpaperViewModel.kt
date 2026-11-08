// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.features.settings.ui

import android.app.Application
import android.os.Build
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.yuzu.yuzu_emu.utils.background.BackgroundMetadata
import org.yuzu.yuzu_emu.utils.background.LoadedWallpaper
import org.yuzu.yuzu_emu.utils.background.OnlineBackground
import org.yuzu.yuzu_emu.utils.background.OnlineBackgroundCatalog
import org.yuzu.yuzu_emu.utils.background.SecureBackgroundStore
import org.yuzu.yuzu_emu.utils.background.WallpaperImages
import org.yuzu.yuzu_emu.utils.background.WallpaperRateLimitException
import org.yuzu.yuzu_emu.utils.background.WallpaperRecommendation
import org.yuzu.yuzu_emu.utils.background.WallpaperRepository
import org.yuzu.yuzu_emu.utils.background.WallpaperRequestGeneration
import org.yuzu.yuzu_emu.utils.background.WallpaperSearchSpec
import org.yuzu.yuzu_emu.utils.background.WallpaperSelection
import org.yuzu.yuzu_emu.utils.background.WallpaperSettings
import org.yuzu.yuzu_emu.utils.background.WallpaperSettingsSnapshot
import org.yuzu.yuzu_emu.utils.background.WallpaperTuning
import org.yuzu.yuzu_emu.utils.background.mergeWallpaperPage

enum class WallpaperFailure { NETWORK, RATE_LIMIT, IMAGE, SAVE }

data class WallpaperSearchState(
    val spec: WallpaperSearchSpec = WallpaperSearchSpec(),
    val items: List<OnlineBackground> = emptyList(),
    val loading: Boolean = false,
    val started: Boolean = false,
    val page: Int = 0,
    val lastPage: Int = 1,
    val error: WallpaperFailure? = null
)

data class WallpaperHubState(
    val personalized: Boolean = false,
    val savingPreference: Boolean = false,
    val recommendations: List<WallpaperRecommendation> = emptyList(),
    val recommendationsLoading: Boolean = false,
    val recommendationError: WallpaperFailure? = null,
    val popular: List<OnlineBackground> = emptyList(),
    val popularLoading: Boolean = false,
    val popularError: WallpaperFailure? = null
)

@Serializable
data class WallpaperDraft(
    val selection: WallpaperSelection,
    val tuning: WallpaperTuning,
    val original: WallpaperSettingsSnapshot
) {
    val changed: Boolean get() = selection.online != null || selection.uri != original.uri || tuning != original.tuning
}

data class WallpaperEditorState(
    val draft: WallpaperDraft? = null,
    val image: LoadedWallpaper? = null,
    val loading: Boolean = false,
    val applying: Boolean = false,
    val applied: Boolean = false,
    val error: WallpaperFailure? = null
) {
    val canApply: Boolean get() = draft != null && image != null && !loading && !applying && !applied
}

/** Scoped to the wallpaper navigation graph, never to a second game-scanning ViewModel. */
class WallpaperViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = WallpaperRepository(application)
    private val json = Json { ignoreUnknownKeys = true }
    private val searchGeneration = WallpaperRequestGeneration()
    private val previewGeneration = WallpaperRequestGeneration()
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var recommendationJob: Job? = null
    private var popularJob: Job? = null
    private var recommendationGeneration = 0L

    private val _search = MutableStateFlow(
        WallpaperSearchState(
            spec = restore<WallpaperSearchSpec>("search_spec")
                ?: WallpaperSearchSpec(catalogUrl = OnlineBackgroundCatalog.catalogUrl(application))
        )
    )
    val search = _search.asStateFlow()
    private val _hub = MutableStateFlow(WallpaperHubState(personalized = WallpaperSettings.personalized()))
    val hub = _hub.asStateFlow()
    private val _editor = MutableStateFlow(WallpaperEditorState(draft = restore("draft")))
    val editor = _editor.asStateFlow()
    private val _history = MutableStateFlow(repository.history())
    val history = _history.asStateFlow()
    private val _committed = MutableStateFlow(WallpaperSettings.read())
    val committed = _committed.asStateFlow()

    var searchScroll: Parcelable?
        get() = saved["search_scroll"]
        set(value) { saved["search_scroll"] = value }

    init {
        if (!_hub.value.personalized) repository.clearRecommendations()
        if (_editor.value.draft != null) loadPreview()
        if (saved.get<Boolean>("search_started") == true) search(_search.value.spec, debounce = false)
    }

    private inline fun <reified T> restore(key: String): T? = saved.get<String>(key)?.let {
        runCatching { json.decodeFromString<T>(it) }.getOrNull()
    }

    fun search(spec: WallpaperSearchSpec, debounce: Boolean = true, manual: Boolean = false) {
        if (manual) {
            repository.rememberSearch(spec.query)
            _history.value = repository.history()
        }
        val normalized = spec.copy(query = spec.query.take(200), orientation = spec.orientation.coerceIn(0, 3),
            resolution = spec.resolution.coerceIn(0, 2), sort = spec.sort.coerceIn(0, 2))
        saved["search_spec"] = json.encodeToString(normalized)
        saved["search_started"] = true
        searchScroll = null
        searchJob?.cancel()
        val generation = searchGeneration.next()
        val old = _search.value
        // While typing, keep the last results visible instead of blanking the grid.
        _search.value = if (debounce) old.copy(spec = normalized, loading = true, started = true, error = null)
        else WallpaperSearchState(spec = normalized, loading = true, started = true)
        searchJob = viewModelScope.launch {
            if (debounce) delay(400)
            fetchPage(generation, normalized, 1)
        }
    }

    private suspend fun fetchPage(generation: Long, spec: WallpaperSearchSpec, page: Int) {
        try {
            val result = repository.search(spec, page)
            if (!searchGeneration.accepts(generation)) return
            _search.value = _search.value.copy(
                items = mergeWallpaperPage(if (page == 1) emptyList() else _search.value.items, result.items),
                page = result.page, lastPage = result.lastPage, loading = false, error = null
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (searchGeneration.accepts(generation)) _search.value = _search.value.copy(loading = false, error = error.failure())
        }
    }

    fun nextPage() {
        val state = _search.value
        if (state.loading || !state.started || state.page >= state.lastPage) return
        _search.value = state.copy(loading = true, error = null)
        val generation = searchGeneration.next()
        searchJob = viewModelScope.launch { fetchPage(generation, state.spec, state.page + 1) }
    }

    fun retrySearch() {
        if (_search.value.page == 0) search(_search.value.spec, debounce = false) else nextPage()
    }

    fun clearHistory() {
        repository.clearHistory()
        _history.value = emptyList()
    }

    /** Called on hub visits or explicit refresh; no timer or automatic wallpaper rotation. */
    fun refreshHub(force: Boolean = false) {
        _committed.value = WallpaperSettings.read()
        if (popularJob?.isActive != true || force) {
            popularJob?.cancel()
            _hub.value = _hub.value.copy(popularLoading = true, popularError = null)
            popularJob = viewModelScope.launch {
                try {
                    val items = repository.popular(force)
                    _hub.value = _hub.value.copy(popular = items, popularLoading = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _hub.value = _hub.value.copy(popularLoading = false, popularError = error.failure())
                }
            }
        }
        refreshRecommendations(force)
    }

    private fun refreshRecommendations(force: Boolean) {
        if (!_hub.value.personalized || (recommendationJob?.isActive == true && !force)) return
        recommendationJob?.cancel()
        val generation = ++recommendationGeneration
        _hub.value = _hub.value.copy(recommendationsLoading = true, recommendationError = null)
        recommendationJob = viewModelScope.launch {
            try {
                val games = repository.recentGames()
                val groups = repository.recommendations(games, force)
                if (generation == recommendationGeneration && _hub.value.personalized) {
                    _hub.value = _hub.value.copy(recommendations = groups, recommendationsLoading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == recommendationGeneration) _hub.value = _hub.value.copy(
                    recommendationsLoading = false, recommendationError = error.failure()
                )
            }
        }
    }

    fun setPersonalized(enabled: Boolean) {
        val old = _hub.value.personalized
        if (_hub.value.savingPreference || enabled == old) return
        if (!enabled) {
            recommendationGeneration++
            recommendationJob?.cancel()
            repository.clearRecommendations()
        }
        _hub.value = _hub.value.copy(personalized = enabled, savingPreference = true,
            recommendations = if (enabled) _hub.value.recommendations else emptyList(),
            recommendationsLoading = false, recommendationError = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { WallpaperSettings.setPersonalized(enabled) }
                _hub.value = _hub.value.copy(savingPreference = false)
                if (enabled) refreshRecommendations(false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _hub.value = _hub.value.copy(personalized = old, savingPreference = false, recommendationError = WallpaperFailure.SAVE)
            }
        }
    }

    fun beginEdit(selection: WallpaperSelection = WallpaperSelection(WallpaperSettings.read().uri), reset: Boolean = false) {
        if (_editor.value.applying) return
        val original = WallpaperSettings.read()
        val tuning = if (reset) WallpaperTuning(blur = original.tuning.blur) else original.tuning
        val draft = WallpaperDraft(selection, tuning, original)
        saved["draft"] = json.encodeToString(draft)
        _editor.value = WallpaperEditorState(draft = draft)
        loadPreview()
    }

    fun loadPreview() {
        val draft = _editor.value.draft ?: return
        if (_editor.value.applying) return
        previewJob?.cancel()
        val generation = previewGeneration.next()
        _editor.value = _editor.value.copy(image = null, loading = true, error = null)
        previewJob = viewModelScope.launch {
            try {
                val image = WallpaperImages.load(getApplication<Application>(), draft.selection)
                if (previewGeneration.accepts(generation)) _editor.value = _editor.value.copy(image = image, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (previewGeneration.accepts(generation)) _editor.value = _editor.value.copy(
                    loading = false, error = if (error is WallpaperRateLimitException) WallpaperFailure.RATE_LIMIT else WallpaperFailure.IMAGE
                )
            }
        }
    }

    fun tune(tuning: WallpaperTuning) {
        val state = _editor.value
        if (state.applying || state.applied) return
        val draft = state.draft ?: return
        val adjusted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) tuning.copy(blur = draft.tuning.blur) else tuning
        val updated = draft.copy(tuning = adjusted.clamped())
        saved["draft"] = json.encodeToString(updated)
        _editor.value = state.copy(draft = updated)
    }

    fun resetAdjustments() = tune(WallpaperTuning())

    fun discardDraft() {
        if (_editor.value.applying) return
        previewGeneration.next()
        previewJob?.cancel()
        saved.remove<String>("draft")
        _editor.value = WallpaperEditorState()
    }

    fun apply() {
        val state = _editor.value
        if (!state.canApply) return
        val draft = state.draft ?: return
        val image = state.image ?: return
        _editor.value = state.copy(applying = true, error = null)
        previewGeneration.next()
        previewJob?.cancel()
        viewModelScope.launch {
            try {
                // Apply is a user-committed transaction; leaving the screen cannot cancel it halfway.
                val snapshot = withContext(Dispatchers.IO + NonCancellable) {
                    val uri = draft.selection.online?.let { online ->
                        SecureBackgroundStore.commitOnline(
                            requireNotNull(image.onlineBytes),
                            BackgroundMetadata(online.id, online.title, online.author, online.source)
                        ) { selectionUri -> WallpaperSettings.save(WallpaperSettingsSnapshot(selectionUri, draft.tuning)) }
                    } ?: draft.selection.uri.also {
                        WallpaperSettings.save(WallpaperSettingsSnapshot(it, draft.tuning))
                    }
                    runCatching { SecureBackgroundStore.pruneUnselected(uri) }
                    WallpaperSettingsSnapshot(uri, draft.tuning)
                }
                _committed.value = snapshot
                saved.remove<String>("draft")
                _editor.value = _editor.value.copy(applying = false, applied = true, image = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _editor.value = _editor.value.copy(applying = false, error = WallpaperFailure.SAVE)
            }
        }
    }

    private fun Exception.failure() = if (this is WallpaperRateLimitException) WallpaperFailure.RATE_LIMIT else WallpaperFailure.NETWORK
}

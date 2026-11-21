// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.background.LoadedWallpaper
import org.yuzu.yuzu_emu.utils.background.WallpaperImages
import org.yuzu.yuzu_emu.utils.background.WallpaperPalette
import org.yuzu.yuzu_emu.utils.background.WallpaperRenderer
import org.yuzu.yuzu_emu.utils.background.WallpaperSelection
import org.yuzu.yuzu_emu.utils.background.WallpaperSettings
import org.yuzu.yuzu_emu.utils.background.WallpaperTuning
import java.util.WeakHashMap

/**
 * Paints the library's optional custom background.
 *
 * The editor and library share measured fit/crop math, a black dimming layer, and an optional
 * GPU blur on API 31+. Every load belongs to this view's lifecycle and selection generation.
 *
 * Two sources are supported: a user-picked document URI, and the online gallery, whose picture is
 * stored encrypted and decrypted into memory only for this view.
 *
 * The decoded picture is kept for the lifetime of the selection instead of being re-decoded on
 * every resume. A 2560-edge wallpaper is up to ~26 MB of ARGB_8888, and the library resumes every
 * time the user comes back from a game, so the old code churned that allocation (and the decode
 * itself) on each visit. [trimMemory] gives it back when the system asks for memory.
 */
object LibraryBackgroundApplier {

    /** Transient decode/keystore failures get a couple of quick retries before we give up. */
    private const val LOAD_ATTEMPTS = 3
    private const val LOAD_RETRY_DELAY_MS = 200L

    private class Binding(val root: View, val renderer: WallpaperRenderer) {
        var job: Job? = null
        var generation = 0L
        var uri: String? = null
        var bitmap: Bitmap? = null
        var tuning = WallpaperTuning()
    }

    /** Root view -> binding, so [trimMemory] can reach pictures that are off screen. */
    private val bindings = WeakHashMap<View, Binding>()

    fun applyTo(root: View, owner: LifecycleOwner) {
        val background = root.findViewById<ImageView>(R.id.library_background) ?: return
        val scrim = root.findViewById<View>(R.id.library_background_scrim) ?: return
        val binding = root.getTag(R.id.wallpaper_binding) as? Binding ?: Binding(
            root, WallpaperRenderer(background, scrim)
        ).also { created ->
            root.setTag(R.id.wallpaper_binding, created)
            synchronized(bindings) { bindings[root] = created }
            owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    created.generation++
                    created.job?.cancel()
                    release(created)
                    created.renderer.close()
                    root.setTag(R.id.wallpaper_binding, null)
                    synchronized(bindings) { bindings.remove(root) }
                    owner.lifecycle.removeObserver(this)
                }
            })
        }
        binding.job?.cancel()
        val generation = ++binding.generation
        val settings = WallpaperSettings.read()
        val selectionChanged = binding.uri != settings.uri
        val alreadyDecoded = binding.bitmap?.isRecycled == false
        if (selectionChanged) {
            binding.uri = settings.uri
            // Keep the previous picture on screen while the new one decodes. Rendering null here
            // used to blank the library on every switch.
            WallpaperPalette.clear()
        } else if (!alreadyDecoded) {
            // The picture was released under memory pressure; the decode below brings it back.
            WallpaperPalette.clear()
        } else {
            // Same picture, new tuning: no decode, no allocation, and no palette resample. The
            // seed was published when this picture was decoded.
            binding.tuning = settings.tuning
            binding.renderer.update(settings.tuning)
            return
        }
        binding.job = owner.lifecycleScope.launch {
            val loaded = loadWithRetry(root.context.applicationContext, WallpaperSelection(settings.uri))
            if (generation != binding.generation) {
                loaded?.bitmap?.recycle()
                return@launch
            }
            val bitmap = loaded?.bitmap?.takeIf { !it.isRecycled }
            if (bitmap != null) {
                val previous = binding.bitmap
                binding.bitmap = bitmap
                binding.tuning = settings.tuning
                binding.renderer.render(bitmap, settings.tuning)
                // Only this binding owned `previous`; the ImageView already points at the new one.
                if (previous !== bitmap && previous?.isRecycled == false) {
                    previous.recycle()
                }
                publishPalette(binding, generation, owner)
            } else if (selectionChanged) {
                // The new selection has nothing showable; clear rather than keep the old picture.
                release(binding)
                binding.renderer.render(null, settings.tuning)
                WallpaperPalette.clear()
            } else {
                // Transient failure on the same selection: keep the picture already on screen
                // instead of wiping a wallpaper that was displaying fine a moment ago.
                binding.renderer.update(settings.tuning)
            }
        }
    }

    /**
     * Drops decoded pictures the user cannot currently see. Called from `onTrimMemory`; the
     * library reloads on its next resume, which is cheaper than being killed mid-game.
     */
    fun trimMemory() {
        val snapshot = synchronized(bindings) { bindings.values.toList() }
        snapshot.forEach { binding ->
            if (!binding.root.isAttachedToWindow || binding.root.visibility != View.VISIBLE) {
                binding.job?.cancel()
                binding.generation++
                release(binding)
                binding.renderer.render(null, binding.tuning)
            }
        }
    }

    private fun release(binding: Binding) {
        binding.bitmap?.let { if (!it.isRecycled) it.recycle() }
        binding.bitmap = null
    }

    private fun publishPalette(binding: Binding, generation: Long, owner: LifecycleOwner) {
        val bitmap = binding.bitmap?.takeIf { !it.isRecycled } ?: return
        owner.lifecycleScope.launch {
            // A quick selection change or a trim can recycle the picture between the check and the
            // sample; the palette is cosmetic, so a miss is not worth a crash.
            runCatching {
                if (generation == binding.generation && !bitmap.isRecycled) {
                    // Publishes the wallpaper's own seed for the Chameleon theme.
                    WallpaperPalette.applyFromWallpaper(bitmap)
                }
            }
        }
    }

    /**
     * Loads the selection, retrying transient failures (keystore busy, a decode that lost an allocation
     * race, a momentary IO error). A permanent rejection (unsupported URI/image) is not retried.
     * Returns null when the picture could not be loaded; the caller decides whether that may clear the
     * current wallpaper.
     */
    private suspend fun loadWithRetry(
        context: Context,
        selection: WallpaperSelection
    ): LoadedWallpaper? {
        var lastError: Exception? = null
        repeat(LOAD_ATTEMPTS) { attempt ->
            try {
                return WallpaperImages.load(context, selection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (permanent: IllegalArgumentException) {
                Log.warning("[Wallpaper] Unsupported selection: ${permanent.message}")
                return null
            } catch (error: Exception) {
                lastError = error
                if (attempt < LOAD_ATTEMPTS - 1) {
                    delay(LOAD_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        Log.warning("[Wallpaper] Load failed after $LOAD_ATTEMPTS attempts: ${lastError?.message}")
        return null
    }
}

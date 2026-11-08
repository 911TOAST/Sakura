// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.view.View
import android.widget.ImageView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.background.WallpaperImages
import org.yuzu.yuzu_emu.utils.background.WallpaperRenderer
import org.yuzu.yuzu_emu.utils.background.WallpaperSelection
import org.yuzu.yuzu_emu.utils.background.WallpaperSettings

/**
 * Paints the library's optional custom background.
 *
 * The editor and library share measured fit/crop math, a black dimming layer, and an optional
 * GPU blur on API 31+. Every load belongs to this view's lifecycle and selection generation.
 *
 * Two sources are supported: a user-picked document URI, and the online gallery, whose picture is
 * stored encrypted and decrypted into memory only for this view.
 */
object LibraryBackgroundApplier {

    private class Binding(val renderer: WallpaperRenderer) {
        var job: Job? = null
        var generation = 0L
        var uri: String? = null
    }

    fun applyTo(root: View, owner: LifecycleOwner) {
        val background = root.findViewById<ImageView>(R.id.library_background) ?: return
        val scrim = root.findViewById<View>(R.id.library_background_scrim) ?: return
        val binding = root.getTag(R.id.wallpaper_binding) as? Binding ?: Binding(
            WallpaperRenderer(background, scrim)
        ).also { created ->
            root.setTag(R.id.wallpaper_binding, created)
            owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    created.generation++
                    created.job?.cancel()
                    created.renderer.close()
                    root.setTag(R.id.wallpaper_binding, null)
                    owner.lifecycle.removeObserver(this)
                }
            })
        }
        binding.job?.cancel()
        val generation = ++binding.generation
        val settings = WallpaperSettings.read()
        if (binding.uri != settings.uri) {
            binding.uri = settings.uri
            binding.renderer.render(null, settings.tuning)
        } else {
            // A tuning change alone keeps the already decoded picture on screen.
            binding.renderer.update(settings.tuning)
        }
        binding.job = owner.lifecycleScope.launch {
            try {
                val loaded = WallpaperImages.load(root.context.applicationContext, WallpaperSelection(settings.uri))
                if (generation == binding.generation) binding.renderer.render(loaded.bitmap, settings.tuning)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == binding.generation) binding.renderer.render(null, settings.tuning)
            }
        }
    }
}

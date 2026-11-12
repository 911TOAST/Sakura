// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
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
import org.yuzu.yuzu_emu.views.AuroraVeilView

/**
 * Paints the library's optional custom background.
 *
 * The editor and library share measured fit/crop math, a black dimming layer, and an optional
 * GPU blur on API 31+. Every load belongs to this view's lifecycle and selection generation.
 *
 * Two sources are supported: a user-picked document URI, and the online gallery, whose picture is
 * stored encrypted and decrypted into memory only for this view.
 *
 * The same decoded picture also feeds the atmosphere: the aurora veil takes the wallpaper's own
 * palette, and the Chameleon theme publishes it to Compose surfaces unless a cover owns the slot.
 */
object LibraryBackgroundApplier {

    /** Transient decode/keystore failures get a couple of quick retries before we give up. */
    private const val LOAD_ATTEMPTS = 3
    private const val LOAD_RETRY_DELAY_MS = 200L

    private class Binding(val renderer: WallpaperRenderer, val aurora: AuroraVeilView?) {
        var job: Job? = null
        var generation = 0L
        var uri: String? = null
        var sensorRegistered = false

        /** Low-passed gravity keeps the veil's lean smooth instead of jittery. */
        val tiltListener = object : SensorEventListener {
            private val gravity = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                gravity[0] = 0.8f * gravity[0] + 0.2f * event.values[0]
                gravity[1] = 0.8f * gravity[1] + 0.2f * event.values[1]
                aurora?.setTilt(-gravity[0] / 9.81f, gravity[1] / 9.81f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    fun applyTo(root: View, owner: LifecycleOwner) {
        val background = root.findViewById<ImageView>(R.id.library_background) ?: return
        val scrim = root.findViewById<View>(R.id.library_background_scrim) ?: return
        val aurora = root.findViewById<AuroraVeilView>(R.id.library_aurora)
        val binding = root.getTag(R.id.wallpaper_binding) as? Binding ?: Binding(
            WallpaperRenderer(background, scrim), aurora
        ).also { created ->
            root.setTag(R.id.wallpaper_binding, created)
            owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    syncParallax(created, owner)
                }

                override fun onStop(owner: LifecycleOwner) {
                    syncParallax(created, owner)
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    created.generation++
                    created.job?.cancel()
                    syncParallax(created, owner)
                    created.renderer.close()
                    created.aurora?.setPalette(null)
                    root.setTag(R.id.wallpaper_binding, null)
                    owner.lifecycle.removeObserver(this)
                }
            })
        }
        binding.job?.cancel()
        val generation = ++binding.generation
        val settings = WallpaperSettings.read()
        val selectionChanged = binding.uri != settings.uri
        if (selectionChanged) {
            binding.uri = settings.uri
            // Keep the previous picture on screen while the new one decodes; only the atmosphere
            // steps down right away. Rendering null here used to blank the library on every switch.
            binding.aurora?.setPalette(null)
            WallpaperPalette.clear()
        } else {
            // A tuning change alone keeps the already decoded picture on screen.
            binding.renderer.update(settings.tuning)
        }
        syncParallax(binding, owner)
        binding.job = owner.lifecycleScope.launch {
            val loaded = loadWithRetry(root.context.applicationContext, WallpaperSelection(settings.uri))
            if (generation != binding.generation) {
                return@launch
            }
            val bitmap = loaded?.bitmap?.takeIf { !it.isRecycled }
            if (bitmap != null) {
                binding.renderer.render(bitmap, settings.tuning)
                updateAtmosphere(binding, bitmap, generation, owner)
            } else if (selectionChanged) {
                // The new selection has nothing showable; clear rather than keep the old picture.
                binding.renderer.render(null, settings.tuning)
                binding.aurora?.setPalette(null)
                WallpaperPalette.clear()
            } else {
                // Transient failure on the same selection: keep the picture already on screen
                // instead of wiping a wallpaper that was displaying fine a moment ago.
                binding.renderer.update(settings.tuning)
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

    /** One decode, two payoffs: the veil follows the picture, the theme follows the seed slot. */
    private suspend fun updateAtmosphere(
        binding: Binding, bitmap: android.graphics.Bitmap?, generation: Long, owner: LifecycleOwner
    ) {
        val enabled = WallpaperSettings.appearance().aurora
        if (enabled && bitmap != null) {
            val seed = WallpaperPalette.applyFromWallpaper(bitmap)
            if (generation == binding.generation) binding.aurora?.setPalette(seed)
        } else {
            // Off (or no picture): both the veil and the wallpaper theme seed step down.
            WallpaperPalette.clear()
            binding.aurora?.setPalette(null)
        }
        binding.aurora?.post { syncParallax(binding, owner) }
    }

    /** The sensor only runs while the library is started, the veil is visible, and the user opted in. */
    private fun syncParallax(binding: Binding, owner: LifecycleOwner) {
        val context = binding.aurora?.context ?: return
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val wanted = WallpaperSettings.appearance().parallax &&
            binding.aurora.visibility == View.VISIBLE &&
            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (wanted && !binding.sensorRegistered) {
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
                manager.registerListener(binding.tiltListener, sensor, SensorManager.SENSOR_DELAY_UI)
                binding.sensorRegistered = true
            }
        } else if (!wanted && binding.sensorRegistered) {
            manager.unregisterListener(binding.tiltListener)
            binding.sensorRegistered = false
        }
    }
}

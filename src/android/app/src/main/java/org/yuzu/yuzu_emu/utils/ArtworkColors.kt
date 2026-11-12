// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.background.WallpaperPalette

/**
 * Artwork-powered colour, the way music players tint themselves from album art: pull the most
 * vivid colour out of a game's cover and grow a whole Material 3 Expressive scheme from it.
 *
 * The seed is published as state so Compose surfaces can follow the library, and [applyBackdrop]
 * gives the library itself an art-tinted wash without touching the XML theme.
 */
object ArtworkColors {

    /** Edge length of the sample grid. Big enough to judge a cover, small enough to stay cheap. */
    private const val SAMPLE_SIZE = 24

    private val _seed = MutableStateFlow<Int?>(null)
    val seed: StateFlow<Int?> = _seed

    /** Direct publish for palette sources that already sampled off the main thread. */
    fun setSeed(seed: Int?) {
        _seed.value = seed
    }

    /** True while a game cover, not the wallpaper, owns the published seed. */
    fun coverOwnsSeed(): Boolean = WallpaperPalette.coverActive

    /**
     * Picks the most characterful colour of the cover: saturated pixels win, near-white and
     * near-black pixels are ignored because every cover has them. Hues are averaged as vectors so
     * reds do not average into cyan when the cover wraps around the wheel.
     */
    fun seedFromArtwork(bitmap: Bitmap?): Int? {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }

        val sample = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        sample.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
        if (sample !== bitmap) {
            sample.recycle()
        }

        val hsv = FloatArray(3)
        var hueX = 0.0
        var hueY = 0.0
        var saturation = 0.0
        var value = 0.0
        var weight = 0.0

        for (pixel in pixels) {
            if (AndroidColor.alpha(pixel) < 200) {
                continue
            }
            AndroidColor.colorToHSV(pixel, hsv)
            val luma = (0.2126 * AndroidColor.red(pixel) +
                0.7152 * AndroidColor.green(pixel) +
                0.0722 * AndroidColor.blue(pixel)) / 255.0
            if (luma < 0.06 || luma > 0.94 || hsv[1] < 0.18) {
                continue
            }
            // Vivid, mid-lit pixels describe the cover's identity best.
            val pixelWeight = hsv[1].toDouble() * hsv[1] *
                (0.4 + 0.6 * (1.0 - kotlin.math.abs(luma - 0.5) * 2.0))
            if (pixelWeight <= 0.0) {
                continue
            }
            val angle = Math.toRadians(hsv[0].toDouble())
            hueX += kotlin.math.cos(angle) * pixelWeight
            hueY += kotlin.math.sin(angle) * pixelWeight
            saturation += hsv[1].toDouble() * pixelWeight
            value += hsv[2].toDouble() * pixelWeight
            weight += pixelWeight
        }

        if (weight < 0.5) {
            return null
        }

        val hue = ((Math.toDegrees(kotlin.math.atan2(hueY, hueX)) + 360.0) % 360.0).toFloat()
        return AndroidColor.HSVToColor(
            floatArrayOf(
                hue,
                (saturation / weight).toFloat().coerceIn(0.35f, 0.95f),
                (value / weight).toFloat().coerceIn(0.45f, 0.95f)
            )
        )
    }

    /** Builds a full Material 3 scheme around the seed. */
    fun schemeFromSeed(seed: Int, dark: Boolean): ColorScheme =
        if (dark) darkScheme(seed) else lightScheme(seed)

    /** Loads the cover for [game] and publishes its seed. Runs off the main thread. */
    suspend fun applyFromArtwork(lifecycleOwner: LifecycleOwner, game: Game?) {
        if (game == null) {
            WallpaperPalette.onCoverSeedChanged(null)
            return
        }
        // Decoding a cover is a disk read plus a native metadata call, and the sample pass touches
        // every pixel: both stay off the main thread.
        val seed = withContext(Dispatchers.IO) {
            val bitmap = runCatching { GameIconUtils.getGameIcon(lifecycleOwner, game) }.getOrNull()
            seedFromArtwork(bitmap)
        }
        WallpaperPalette.onCoverSeedChanged(seed)
    }

    fun clear() {
        WallpaperPalette.onCoverSeedChanged(null)
    }

    private fun lightScheme(seed: Int): ColorScheme {
        val hsv = seedHsv(seed)
        val primary = tonal(hsv, 0f, 0.95f, 0.40f)
        return lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = tonal(hsv, 0f, 0.85f, 0.88f),
            onPrimaryContainer = tonal(hsv, 0f, 0.75f, 0.17f),
            inversePrimary = tonal(hsv, 0f, 0.75f, 0.80f),
            secondary = tonal(hsv, 18f, 0.45f, 0.42f),
            onSecondary = Color.White,
            secondaryContainer = tonal(hsv, 18f, 0.45f, 0.89f),
            onSecondaryContainer = tonal(hsv, 18f, 0.45f, 0.18f),
            tertiary = tonal(hsv, 62f, 0.50f, 0.42f),
            onTertiary = Color.White,
            tertiaryContainer = tonal(hsv, 62f, 0.55f, 0.90f),
            onTertiaryContainer = tonal(hsv, 62f, 0.55f, 0.18f),
            background = tonal(hsv, 0f, 0.12f, 0.985f),
            onBackground = tonal(hsv, 0f, 0.14f, 0.11f),
            surface = tonal(hsv, 0f, 0.12f, 0.985f),
            onSurface = tonal(hsv, 0f, 0.14f, 0.11f),
            surfaceVariant = tonal(hsv, 0f, 0.18f, 0.92f),
            onSurfaceVariant = tonal(hsv, 0f, 0.16f, 0.36f),
            surfaceTint = primary,
            surfaceDim = tonal(hsv, 0f, 0.14f, 0.86f),
            surfaceBright = tonal(hsv, 0f, 0.10f, 0.985f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = tonal(hsv, 0f, 0.14f, 0.965f),
            surfaceContainer = tonal(hsv, 0f, 0.15f, 0.945f),
            surfaceContainerHigh = tonal(hsv, 0f, 0.16f, 0.92f),
            surfaceContainerHighest = tonal(hsv, 0f, 0.17f, 0.895f),
            inverseSurface = tonal(hsv, 0f, 0.15f, 0.20f),
            inverseOnSurface = tonal(hsv, 0f, 0.10f, 0.94f),
            outline = tonal(hsv, 0f, 0.12f, 0.52f),
            outlineVariant = tonal(hsv, 0f, 0.15f, 0.80f),
            scrim = Color.Black
        )
    }

    private fun darkScheme(seed: Int): ColorScheme {
        val hsv = seedHsv(seed)
        val primary = tonal(hsv, 0f, 0.70f, 0.80f)
        return darkColorScheme(
            primary = primary,
            onPrimary = tonal(hsv, 0f, 0.75f, 0.18f),
            primaryContainer = tonal(hsv, 0f, 0.80f, 0.32f),
            onPrimaryContainer = tonal(hsv, 0f, 0.70f, 0.90f),
            inversePrimary = tonal(hsv, 0f, 0.95f, 0.42f),
            secondary = tonal(hsv, 18f, 0.40f, 0.80f),
            onSecondary = tonal(hsv, 18f, 0.45f, 0.18f),
            secondaryContainer = tonal(hsv, 18f, 0.40f, 0.32f),
            onSecondaryContainer = tonal(hsv, 18f, 0.40f, 0.90f),
            tertiary = tonal(hsv, 62f, 0.45f, 0.82f),
            onTertiary = tonal(hsv, 62f, 0.50f, 0.18f),
            tertiaryContainer = tonal(hsv, 62f, 0.50f, 0.32f),
            onTertiaryContainer = tonal(hsv, 62f, 0.50f, 0.90f),
            background = tonal(hsv, 0f, 0.16f, 0.07f),
            onBackground = tonal(hsv, 0f, 0.10f, 0.92f),
            surface = tonal(hsv, 0f, 0.16f, 0.07f),
            onSurface = tonal(hsv, 0f, 0.10f, 0.92f),
            surfaceVariant = tonal(hsv, 0f, 0.16f, 0.28f),
            onSurfaceVariant = tonal(hsv, 0f, 0.12f, 0.75f),
            surfaceTint = primary,
            surfaceDim = tonal(hsv, 0f, 0.16f, 0.07f),
            surfaceBright = tonal(hsv, 0f, 0.16f, 0.26f),
            surfaceContainerLowest = tonal(hsv, 0f, 0.18f, 0.04f),
            surfaceContainerLow = tonal(hsv, 0f, 0.17f, 0.10f),
            surfaceContainer = tonal(hsv, 0f, 0.17f, 0.13f),
            surfaceContainerHigh = tonal(hsv, 0f, 0.17f, 0.17f),
            surfaceContainerHighest = tonal(hsv, 0f, 0.17f, 0.21f),
            inverseSurface = tonal(hsv, 0f, 0.10f, 0.92f),
            inverseOnSurface = tonal(hsv, 0f, 0.16f, 0.15f),
            outline = tonal(hsv, 0f, 0.12f, 0.58f),
            outlineVariant = tonal(hsv, 0f, 0.14f, 0.30f),
            scrim = Color.Black
        )
    }

    private fun seedHsv(seed: Int): FloatArray =
        FloatArray(3).also { AndroidColor.colorToHSV(seed, it) }

    /**
     * One tonal step: rotate the seed hue, clamp saturation, force lightness so contrast stays
     * inside the Material 3 range no matter how garish the cover art is.
     */
    private fun tonal(base: FloatArray, hueShift: Float, saturation: Float, lightness: Float): Color {
        val hue = (base[0] + hueShift + 360f) % 360f
        val sat = (saturation * (0.6f + 0.4f * base[1])).coerceIn(0.04f, 0.98f)
        val argb = AndroidColor.HSVToColor(floatArrayOf(hue, sat, lightnessFromLightness(lightness)))
        return Color(argb)
    }

    /**
     * HSV is not HSL: approximate the M3 lightness ladder with a value curve so 0.40 reads as a
     * dark-but-legible primary and 0.94 reads as a near-white container.
     */
    private fun lightnessFromLightness(lightness: Float): Float =
        if (lightness >= 0.5f) 1f - (1f - lightness) * 0.55f else lightness * 1.35f + 0.10f
}

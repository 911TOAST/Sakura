// SPDX-FileCopyrightText: Copyright 2026 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.utils.ArtworkColors
import org.yuzu.yuzu_emu.utils.Theme

// =============================================================================
// Sakura Rose — Material 3 Expressive palette
// Primary chroma boosted from baseline M3 (H=345, C=72 → deep vivid rose).
// Tertiary pivoted to warm amber-gold for the expressive contrast pop that
// M3 Expressive research identified as the "bold key colour" driver.
// Dark scheme uses luminous petal pink primary on deep plum-rose surfaces.
// Both schemes mirror res/values[night]/suyu_colors.xml exactly.
// =============================================================================

private val RoseLight = lightColorScheme(
    primary = Color(0xFFC2185B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD6E4),
    onPrimaryContainer = Color(0xFF3E0020),
    secondary = Color(0xFF8C4A5D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD8E6),
    onSecondaryContainer = Color(0xFF3A0722),
    tertiary = Color(0xFF9C6B00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA0),
    onTertiaryContainer = Color(0xFF301E00),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF22191A),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF22191A),
    surfaceVariant = Color(0xFFF2DCE0),
    onSurfaceVariant = Color(0xFF514348),
    surfaceDim = Color(0xFFE8D5D9),
    surfaceBright = Color(0xFFFFF8F8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0F3),
    surfaceContainer = Color(0xFFFCEAED),
    surfaceContainerHigh = Color(0xFFF5E1E5),
    surfaceContainerHighest = Color(0xFFEFD9DD),
    outline = Color(0xFF85737A),
    outlineVariant = Color(0xFFD5C2C6),
    inverseSurface = Color(0xFF382D2F),
    inverseOnSurface = Color(0xFFFFECEE),
    inversePrimary = Color(0xFFFFB1CB),
    surfaceTint = Color(0xFFC2185B),
)

private val RoseDark = darkColorScheme(
    primary = Color(0xFFFFB1CB),
    onPrimary = Color(0xFF690030),
    primaryContainer = Color(0xFF8E1045),
    onPrimaryContainer = Color(0xFFFFD6E4),
    secondary = Color(0xFFEFBDD0),
    onSecondary = Color(0xFF531C31),
    secondaryContainer = Color(0xFF6D3248),
    onSecondaryContainer = Color(0xFFFFD8E6),
    tertiary = Color(0xFFF0BD6E),
    onTertiary = Color(0xFF4E3500),
    tertiaryContainer = Color(0xFF6E4D00),
    onTertiaryContainer = Color(0xFFFFDEA0),
    background = Color(0xFF190F11),
    onBackground = Color(0xFFF3E0E5),
    surface = Color(0xFF190F11),
    onSurface = Color(0xFFF3E0E5),
    surfaceVariant = Color(0xFF524047),
    onSurfaceVariant = Color(0xFFD7C3C8),
    surfaceDim = Color(0xFF190F11),
    surfaceBright = Color(0xFF423437),
    surfaceContainerLowest = Color(0xFF130A0C),
    surfaceContainerLow = Color(0xFF1F1315),
    surfaceContainer = Color(0xFF231719),
    surfaceContainerHigh = Color(0xFF2E2124),
    surfaceContainerHighest = Color(0xFF392B2F),
    outline = Color(0xFFA08D92),
    outlineVariant = Color(0xFF524047),
    inverseSurface = Color(0xFFF3E0E5),
    inverseOnSurface = Color(0xFF190F11),
    inversePrimary = Color(0xFFC2185B),
    surfaceTint = Color(0xFFFFB1CB),
)

// Material 3 Expressive shape scale: bigger, rounder, more confident than baseline M3. The
// extra-large corner is the one dialogs, sheets and hero cards ride on.
private val SuyuShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// Google Sans (OFL-1.1), Latin subset, mirrors res/font/google_sans.xml.
private val GoogleSans = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_semibold, FontWeight.SemiBold),
    Font(R.font.google_sans_bold, FontWeight.Bold),
)

// =============================================================================
// M3 Expressive type scale
// Key changes vs baseline M3:
//   - Display: tighter negative tracking (feels more premium / editorial)
//   - Headlines: slightly tighter tracking for confidence
//   - Body: neutral tracking unchanged (readability first)
//   - Label: positive tracking (legibility at small sizes)
// =============================================================================
private val SuyuTypography = run {
    val base = Typography()
    Typography(
        displayLarge = base.displayLarge.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
        ),
        displayMedium = base.displayMedium.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.35).sp,
        ),
        displaySmall = base.displaySmall.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
        ),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.1).sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.05.sp,
        ),
        titleSmall = base.titleSmall.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.05.sp,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontFamily = GoogleSans,
            letterSpacing = 0.1.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = GoogleSans,
            letterSpacing = 0.1.sp,
        ),
        bodySmall = base.bodySmall.copy(
            fontFamily = GoogleSans,
            letterSpacing = 0.15.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.1.sp,
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = GoogleSans,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.25.sp,
        ),
    )
}

@Composable
fun suyuScheme(artworkSeed: Int? = null): ColorScheme {
    val darkTheme = isSystemInDarkTheme()
    // Material You is the only mode allowed to follow the wallpaper. Everything else renders the
    // Sakura Rose expressive palette, which is what ThemeHelper puts on the XML windows: a Compose
    // screen silently turning into stock dynamic colour broke the app's identity on every Pixel.
    // Cached for this composition: the theme is applied when the activity is created, and the
    // dashboard recomposes every frame, so this must not reach into the native config each time.
    val materialYou = remember {
        runCatching { Theme.from(IntSetting.THEME.getInt()) == Theme.MaterialYou }
            .getOrDefault(false)
    }
    if (materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return runCatching {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }.getOrElse { if (darkTheme) RoseDark else RoseLight }
    }
    // Otherwise the library's cover art wins, the way a music player colours itself from the
    // album on screen. Rose is the fallback until a cover has been sampled.
    if (artworkSeed != null) {
        return ArtworkColors.schemeFromSeed(artworkSeed, darkTheme)
    }
    return if (darkTheme) RoseDark else RoseLight
}

@Composable
fun SuyuM3Theme(artworkSeed: Int? = null, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = suyuScheme(artworkSeed),
        shapes = SuyuShapes,
        typography = SuyuTypography,
        content = content
    )
}
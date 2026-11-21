package com.shrey.downloader.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R
import com.shrey.downloader.domain.model.GlassEffectMode
import com.shrey.downloader.domain.model.ThemeMode

val ShreySansFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

val ShreyMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium)
)

@Immutable
data class ShreyPalette(
    val isDark: Boolean,
    val backgroundPrimary: Color,
    val backgroundSecondary: Color,
    val ambientGlowTop: Color,
    val ambientGlowSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val glassFillPrimary: Color,
    val glassFillElevated: Color,
    val glassFillReduced: Color,
    val glassHighlightTop: Color,
    val glassHighlightBottom: Color,
    val glassBorderTop: Color,
    val glassBorderBottom: Color,
    val accentSolid: Color,
    val accentOnSolid: Color,
    val pillSelectedFill: Color,
    val pillSelectedBorder: Color,
    val progressTrack: Color,
    val progressFill: Color,
    val statusSuccess: Color,
    val statusWarning: Color,
    val statusError: Color,
    val statusErrorFill: Color,
    val divider: Color
)

private val DarkShreyPalette = ShreyPalette(
    isDark = true,
    backgroundPrimary = Color(0xFF08090B),
    backgroundSecondary = Color(0xFF111318),
    ambientGlowTop = Color(0x18CFD8EA),
    ambientGlowSecondary = Color(0x0E9AA8C7),
    textPrimary = Color(0xFFF7F8FA),
    textSecondary = Color(0xFFA5A9B4),
    textTertiary = Color(0xFF6B707D),
    glassFillPrimary = Color(0xB814171E),
    glassFillElevated = Color(0xCC1B1F28),
    glassFillReduced = Color(0xFF13161C),
    glassHighlightTop = Color(0x16FFFFFF),
    glassHighlightBottom = Color(0x03FFFFFF),
    glassBorderTop = Color(0x2EFFFFFF),
    glassBorderBottom = Color(0x0AFFFFFF),
    accentSolid = Color(0xFFF2F4F8),
    accentOnSolid = Color(0xFF0A0C10),
    pillSelectedFill = Color(0x22FFFFFF),
    pillSelectedBorder = Color(0x38FFFFFF),
    progressTrack = Color(0x1FFFFFFF),
    progressFill = Color(0xFFE8ECF4),
    statusSuccess = Color(0xFF68D39B),
    statusWarning = Color(0xFFE5B869),
    statusError = Color(0xFFEA767C),
    statusErrorFill = Color(0x1FEA767C),
    divider = Color(0x14FFFFFF)
)

private val LightShreyPalette = ShreyPalette(
    isDark = false,
    backgroundPrimary = Color(0xFFF4F5F8),
    backgroundSecondary = Color(0xFFE9ECF2),
    ambientGlowTop = Color(0x30FFFFFF),
    ambientGlowSecondary = Color(0x18B8C3D8),
    textPrimary = Color(0xFF10131A),
    textSecondary = Color(0xFF5A6070),
    textTertiary = Color(0xFF838998),
    glassFillPrimary = Color(0xCCFFFFFF),
    glassFillElevated = Color(0xE6FFFFFF),
    glassFillReduced = Color(0xFFFDFDFE),
    glassHighlightTop = Color(0x66FFFFFF),
    glassHighlightBottom = Color(0x08FFFFFF),
    glassBorderTop = Color(0xE6FFFFFF),
    glassBorderBottom = Color(0x1A10131A),
    accentSolid = Color(0xFF14171F),
    accentOnSolid = Color(0xFFF7F8FA),
    pillSelectedFill = Color(0x1410131A),
    pillSelectedBorder = Color(0x2410131A),
    progressTrack = Color(0x1A10131A),
    progressFill = Color(0xFF1B1F29),
    statusSuccess = Color(0xFF1E8E5A),
    statusWarning = Color(0xFFB4781A),
    statusError = Color(0xFFC53F47),
    statusErrorFill = Color(0x14C53F47),
    divider = Color(0x1410131A)
)

val LocalShreyPalette = staticCompositionLocalOf { DarkShreyPalette }
val LocalGlassEffectMode = staticCompositionLocalOf { GlassEffectMode.FULL }
val LocalHapticsEnabled = staticCompositionLocalOf { true }

val ShreyTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = ShreySansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = ShreySansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = ShreySansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = ShreySansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.15).sp
    ),
    titleSmall = TextStyle(
        fontFamily = ShreySansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = ShreySansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = ShreySansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = ShreySansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = ShreySansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = ShreySansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = ShreyMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp
    )
)

@Composable
fun ShreyTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    glassEffectMode: GlassEffectMode = GlassEffectMode.FULL,
    hapticsEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val palette = if (isDark) DarkShreyPalette else LightShreyPalette

    val m3Scheme = if (isDark) {
        darkColorScheme(
            primary = palette.accentSolid,
            onPrimary = palette.accentOnSolid,
            background = palette.backgroundPrimary,
            onBackground = palette.textPrimary,
            surface = palette.backgroundSecondary,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.glassFillPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.glassBorderTop,
            error = palette.statusError
        )
    } else {
        lightColorScheme(
            primary = palette.accentSolid,
            onPrimary = palette.accentOnSolid,
            background = palette.backgroundPrimary,
            onBackground = palette.textPrimary,
            surface = palette.backgroundSecondary,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.glassFillPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.glassBorderBottom,
            error = palette.statusError
        )
    }

    CompositionLocalProvider(
        LocalShreyPalette provides palette,
        LocalGlassEffectMode provides glassEffectMode,
        LocalHapticsEnabled provides hapticsEnabled
    ) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = ShreyTypography,
            content = content
        )
    }
}

object ShreyThemeTokens {
    val palette: ShreyPalette
        @Composable get() = LocalShreyPalette.current
}

class ShreyHaptics(
    private val feedback: HapticFeedback,
    private val enabled: Boolean
) {
    fun tap() {
        if (enabled) {
            feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun confirm() {
        if (enabled) {
            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}

@Composable
fun rememberShreyHaptics(): ShreyHaptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return ShreyHaptics(feedback, enabled)
}

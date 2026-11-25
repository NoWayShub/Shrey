package com.shrey.downloader.core.design

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.shrey.downloader.domain.model.GlassEffectMode

/**
 * Reusable TOP-only progressive backdrop blur container for scrollable screens.
 *
 * How it works:
 * - On Android 12+ (API 31+) in [GlassEffectMode.FULL], records the live scrollable content into
 *   a hardware Compose [GraphicsLayer] display list (zero CPU bitmap copy) and renders a shallow
 *   top strip (`statusBarHeight + blurHeightBelowStatusBar`) through a GPU [BlurEffect] masked
 *   with a smooth cubic-eased [BlendMode.DstIn] alpha gradient so content genuinely blurs as it
 *   glides under the status bar and transitions seamlessly into sharp content below.
 * - On Android 10-11 (API 29-30) or in [GlassEffectMode.REDUCED], falls back transparently to a
 *   lightweight multi-stop atmospheric top scrim (never misrepresented as true backdrop blur).
 * - Strictly TOP edge only, zero touch interception, zero continuous background animation.
 */
@Composable
fun ShreyTopGradualBlurContainer(
    scrollProgress: Float,
    modifier: Modifier = Modifier,
    blurHeightBelowStatusBar: Dp = 28.dp,
    content: @Composable () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val glassMode = LocalGlassEffectMode.current
    val density = LocalDensity.current

    val clampedProgress = scrollProgress.coerceIn(0f, 1f)
    val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val totalStripHeight = statusBarHeight + blurHeightBelowStatusBar

    val useRealBackdropBlur = glassMode == GlassEffectMode.FULL &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val contentLayer = rememberGraphicsLayer()
    val blurredStripLayer = rememberGraphicsLayer()

    // Smooth 8-stop cubic alpha mask for DstIn feathering (eliminates hard edges and banding)
    val blurAlphaMaskBrush = remember(clampedProgress) {
        val peak = (0.35f + 0.65f * clampedProgress).coerceIn(0f, 1f)
        Brush.verticalGradient(
            0.00f to Color.Black.copy(alpha = 1.00f * peak),
            0.26f to Color.Black.copy(alpha = 0.94f * peak),
            0.46f to Color.Black.copy(alpha = 0.76f * peak),
            0.64f to Color.Black.copy(alpha = 0.48f * peak),
            0.79f to Color.Black.copy(alpha = 0.22f * peak),
            0.90f to Color.Black.copy(alpha = 0.06f * peak),
            0.97f to Color.Black.copy(alpha = 0.015f * peak),
            1.00f to Color.Transparent
        )
    }

    // Subtle theme-matched contrast veil over the blur (or full scrim in fallback mode)
    val topContrastBrush = remember(palette.backgroundPrimary, palette.isDark, useRealBackdropBlur, clampedProgress) {
        val base = palette.backgroundPrimary
        val maxAlpha = if (useRealBackdropBlur) {
            val rest = if (palette.isDark) 0.22f else 0.28f
            val scrolled = if (palette.isDark) 0.42f else 0.50f
            rest + (scrolled - rest) * clampedProgress
        } else {
            val rest = if (palette.isDark) 0.36f else 0.44f
            val scrolled = if (palette.isDark) 0.86f else 0.90f
            rest + (scrolled - rest) * clampedProgress
        }
        Brush.verticalGradient(
            0.00f to base.copy(alpha = maxAlpha),
            0.28f to base.copy(alpha = maxAlpha * 0.86f),
            0.52f to base.copy(alpha = maxAlpha * 0.58f),
            0.74f to base.copy(alpha = maxAlpha * 0.26f),
            0.89f to base.copy(alpha = maxAlpha * 0.07f),
            1.00f to Color.Transparent
        )
    }

    val blurRadiusPx = with(density) {
        (11.dp * (0.45f + 0.55f * clampedProgress)).toPx()
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Layer 1: Live scrollable content (recorded into GPU display list only when real blur is enabled)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (useRealBackdropBlur) {
                        Modifier.drawWithContent {
                            contentLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(contentLayer)
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.TopCenter
        ) {
            content()
        }

        // Layer 2: Shallow TOP-only real progressive backdrop blur + contrast veil (zero touch interception)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(totalStripHeight)
                .clipToBounds()
                .semantics {
                    stateDescription = if (useRealBackdropBlur) {
                        "Top progressive backdrop blur active"
                    } else {
                        "Top atmospheric scrim fallback active"
                    }
                }
                .testTag("top_gradual_blur")
        ) {
            if (useRealBackdropBlur) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawBehind {
                            val w = size.width.toInt().coerceAtLeast(1)
                            val h = size.height.toInt().coerceAtLeast(1)
                            if (blurRadiusPx >= 1f && contentLayer.size.width > 0 && contentLayer.size.height > 0) {
                                blurredStripLayer.renderEffect = BlurEffect(
                                    radiusX = blurRadiusPx,
                                    radiusY = blurRadiusPx,
                                    edgeTreatment = TileMode.Clamp
                                )
                                blurredStripLayer.record(size = IntSize(w, h)) {
                                    drawLayer(contentLayer)
                                }
                                drawLayer(blurredStripLayer)
                                drawRect(
                                    brush = blurAlphaMaskBrush,
                                    blendMode = BlendMode.DstIn
                                )
                            }
                        }
                )
            }

            // Smooth atmospheric contrast veil (never intercepts touches)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(topContrastBrush)
            )
        }
    }
}

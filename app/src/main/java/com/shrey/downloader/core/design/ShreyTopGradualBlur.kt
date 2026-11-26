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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
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
import kotlin.math.ceil

data class ShreyTopBlurSpec(
    val restBlurRadius: Dp = 8.dp,
    val scrolledBlurRadius: Dp = 28.dp,
    val midRestBlurRadius: Dp = 4.dp,
    val midScrolledBlurRadius: Dp = 12.dp,
    val blurHeightBelowStatusBar: Dp = 36.dp
)

object ShreyTopBlurDefaults {
    val Standard = ShreyTopBlurSpec(
        restBlurRadius = 8.dp,
        scrolledBlurRadius = 28.dp,
        midRestBlurRadius = 4.dp,
        midScrolledBlurRadius = 12.dp,
        blurHeightBelowStatusBar = 36.dp
    )

    fun effectiveBlurRadius(spec: ShreyTopBlurSpec, scrollProgress: Float): Dp {
        val clamped = scrollProgress.coerceIn(0f, 1f)
        return spec.restBlurRadius + (spec.scrolledBlurRadius - spec.restBlurRadius) * clamped
    }

    fun effectiveMidBlurRadius(spec: ShreyTopBlurSpec, scrollProgress: Float): Dp {
        val clamped = scrollProgress.coerceIn(0f, 1f)
        return spec.midRestBlurRadius + (spec.midScrolledBlurRadius - spec.midRestBlurRadius) * clamped
    }
}

/**
 * Reusable TOP-only progressive backdrop blur container for scrollable screens.
 *
 * Rendering pipeline on Android 12+ (API 31+) in [GlassEffectMode.FULL]:
 * 1. Records a full-screen-width opaque ambient backdrop (matching [ShreyAmbientBackground]) PLUS
 *    the live scrollable content into a hardware Compose [GraphicsLayer] (`contentLayer`) with zero
 *    CPU bitmap copies. Painting an opaque full-width backdrop inside `contentLayer` ensures
 *    blurred pixels stay opaque across the entire screen width (both cards and outer margins)
 *    instead of diluting alpha and letting sharp underlying text bleed through.
 * 2. Renders a compact two-stage progressive top blur strip (`statusBarHeight + 36.dp` by default)
 *    spanning the full screen width and sampled with vertical overscan so the GPU [BlurEffect]
 *    kernel reads real content below the strip boundary rather than smearing a clipped edge:
 *    - Pass 1 (mid-zone optical defocus, 4dp -> 12dp): smoothly feathers out to zero at the bottom
 *      edge of the 36dp zone, leaving the rest of the screen completely sharp.
 *    - Pass 2 (top-zone heavy frost, 8dp -> 28dp): keeps the strongest blur concentrated across
 *      the status bar and upper edge, then tapers smoothly into Pass 1.
 *    Each pass composites in its own [CompositingStrategy.Offscreen] layer with a status-bar-aware
 *    cubic-eased [BlendMode.DstIn] alpha mask so focus tapers smoothly with zero hard edge.
 * 3. Adds a light full-width theme-matched atmospheric contrast veil for status-bar legibility and
 *    edge-to-edge visual continuity across margins and cards alike.
 *
 * On Android 10-11 (API 29-30) or in [GlassEffectMode.REDUCED], falls back honestly to a
 * lightweight multi-stop atmospheric top scrim. Strictly TOP edge only, zero touch interception.
 */
@Composable
fun ShreyTopGradualBlurContainer(
    scrollProgress: Float,
    modifier: Modifier = Modifier,
    blurHeightBelowStatusBar: Dp = ShreyTopBlurDefaults.Standard.blurHeightBelowStatusBar,
    scrollOffsetProvider: (() -> Int)? = null,
    content: @Composable () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val glassMode = LocalGlassEffectMode.current
    val density = LocalDensity.current

    val clampedProgress = scrollProgress.coerceIn(0f, 1f)
    val statusBarHeightPx = WindowInsets.statusBars.getTop(density).toFloat()
    val statusBarHeight = with(density) { statusBarHeightPx.toDp() }
    val totalStripHeight = statusBarHeight + blurHeightBelowStatusBar
    val totalStripHeightPx = with(density) { totalStripHeight.toPx() }.coerceAtLeast(1f)
    // With a compact 36dp zone below the status bar, tall status bars/cutouts (36-48dp) can occupy
    // ~50-60% of the total strip height; allow up to 0.68f so the fade always begins cleanly below
    // the status bar.
    val statusBarFraction = (statusBarHeightPx / totalStripHeightPx).coerceIn(0f, 0.68f)

    val useRealBackdropBlur = glassMode == GlassEffectMode.FULL &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val contentLayer = rememberGraphicsLayer()
    val midBlurLayer = rememberGraphicsLayer()
    val heavyBlurLayer = rememberGraphicsLayer()

    val heavyBlurDp = ShreyTopBlurDefaults.effectiveBlurRadius(ShreyTopBlurDefaults.Standard, clampedProgress)
    val midBlurDp = ShreyTopBlurDefaults.effectiveMidBlurRadius(ShreyTopBlurDefaults.Standard, clampedProgress)
    val heavyRadiusPx = with(density) { heavyBlurDp.toPx() }
    val midRadiusPx = with(density) { midBlurDp.toPx() }

    val heavyBlurEffect = remember(heavyRadiusPx) {
        if (heavyRadiusPx >= 1f) {
            BlurEffect(
                radiusX = heavyRadiusPx,
                radiusY = heavyRadiusPx,
                edgeTreatment = TileMode.Clamp
            )
        } else {
            null
        }
    }
    val midBlurEffect = remember(midRadiusPx) {
        if (midRadiusPx >= 1f) {
            BlurEffect(
                radiusX = midRadiusPx,
                radiusY = midRadiusPx,
                edgeTreatment = TileMode.Clamp
            )
        } else {
            null
        }
    }

    // Status-bar-aware progressive masks tuned for the compact ~36dp below-status-bar band:
    // At rest (progress = 0), keep blur tight to the status bar so top page titles remain sharp.
    // As content scrolls (progress -> 1), hold peak 28dp frost at the very top and feather
    // smoothly to 0.0 by statusBarHeight + 36dp so the rest of the screen stays crisp.
    val midAlphaMaskBrush = remember(statusBarFraction, clampedProgress) {
        buildProgressiveAlphaMask(
            statusBarFraction = statusBarFraction,
            activeReachFraction = lerpFloat(0.24f, 1.00f, clampedProgress),
            peakAlpha = lerpFloat(0.46f, 1.00f, clampedProgress),
            solidHoldFraction = 0.22f
        )
    }

    val heavyAlphaMaskBrush = remember(statusBarFraction, clampedProgress) {
        buildProgressiveAlphaMask(
            statusBarFraction = statusBarFraction,
            activeReachFraction = lerpFloat(0.14f, 0.72f, clampedProgress),
            peakAlpha = lerpFloat(0.42f, 1.00f, clampedProgress),
            solidHoldFraction = 0.18f
        )
    }

    // Full-width theme-matched atmospheric contrast veil over real optical blur (or lightweight
    // multi-stop scrim in Reduced / API 29-30 fallback mode)
    val topContrastBrush = remember(
        palette.backgroundPrimary,
        palette.isDark,
        useRealBackdropBlur,
        statusBarFraction,
        clampedProgress
    ) {
        val base = palette.backgroundPrimary
        val maxAlpha = if (useRealBackdropBlur) {
            val rest = if (palette.isDark) 0.14f else 0.18f
            val scrolled = if (palette.isDark) 0.28f else 0.32f
            lerpFloat(rest, scrolled, clampedProgress)
        } else {
            val rest = if (palette.isDark) 0.38f else 0.46f
            val scrolled = if (palette.isDark) 0.86f else 0.90f
            lerpFloat(rest, scrolled, clampedProgress)
        }
        val reach = lerpFloat(0.28f, 0.94f, clampedProgress)
        buildProgressiveVeilBrush(
            baseColor = base,
            statusBarFraction = statusBarFraction,
            activeReachFraction = reach,
            maxAlpha = maxAlpha
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Layer 1: Full-screen-width opaque ambient backdrop + live scrollable content recorded
        // into GPU display list
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (useRealBackdropBlur) {
                        Modifier.drawWithContent {
                            // Reading scroll offset & progress inside draw phase invalidates GPU
                            // recording on every scroll frame with zero recomposition overhead.
                            scrollOffsetProvider?.invoke()
                            val unusedProgress = clampedProgress
                            if (unusedProgress >= 0f) {
                                contentLayer.record {
                                    drawOpaqueAmbientBackdrop(palette = palette, glassMode = glassMode)
                                    this@drawWithContent.drawContent()
                                }
                                drawLayer(contentLayer)
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.TopCenter
        ) {
            content()
        }

        // Layer 2: Full-screen-width TOP-only progressive 2-stage optical backdrop blur + veil
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
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
                // Pass 1: Mid-strength optical defocus extending smoothly to zero at strip bottom
                BlurPassOverlay(
                    contentLayer = contentLayer,
                    blurLayer = midBlurLayer,
                    blurEffect = midBlurEffect,
                    blurRadiusPx = midRadiusPx,
                    alphaMaskBrush = midAlphaMaskBrush,
                    scrollProgress = clampedProgress,
                    scrollOffsetProvider = scrollOffsetProvider
                )

                // Pass 2: High-strength 28dp top frost nested smoothly over status bar & top edge
                BlurPassOverlay(
                    contentLayer = contentLayer,
                    blurLayer = heavyBlurLayer,
                    blurEffect = heavyBlurEffect,
                    blurRadiusPx = heavyRadiusPx,
                    alphaMaskBrush = heavyAlphaMaskBrush,
                    scrollProgress = clampedProgress,
                    scrollOffsetProvider = scrollOffsetProvider
                )
            }

            // Full-width light theme-matched contrast veil (never intercepts touches)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(topContrastBrush)
            )
        }
    }
}

@Composable
private fun BlurPassOverlay(
    contentLayer: GraphicsLayer,
    blurLayer: GraphicsLayer,
    blurEffect: BlurEffect?,
    blurRadiusPx: Float,
    alphaMaskBrush: Brush,
    scrollProgress: Float,
    scrollOffsetProvider: (() -> Int)?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawBehind {
                scrollOffsetProvider?.invoke()
                val effect = blurEffect
                val contentW = contentLayer.size.width
                val contentH = contentLayer.size.height
                if (effect != null && blurRadiusPx >= 1f && scrollProgress >= 0f && contentW > 0 && contentH > 0) {
                    val w = size.width.toInt().coerceAtLeast(1)
                    val h = size.height.toInt().coerceAtLeast(1)
                    // Sample extra vertical overscan below the strip so the GPU blur kernel reads
                    // real scrolling pixels past the bottom feather edge instead of clamping.
                    val overscanPx = ceil(blurRadiusPx * 2f).toInt()
                    val recordHeight = (h + overscanPx).coerceAtMost(contentH.coerceAtLeast(h))

                    blurLayer.clip = true
                    blurLayer.renderEffect = effect
                    blurLayer.record(size = IntSize(w, recordHeight)) {
                        drawLayer(contentLayer)
                    }
                    clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                        drawLayer(blurLayer)
                    }
                    drawRect(
                        brush = alphaMaskBrush,
                        size = size,
                        blendMode = BlendMode.DstIn
                    )
                }
            }
    )
}

private fun DrawScope.drawOpaqueAmbientBackdrop(
    palette: ShreyPalette,
    glassMode: GlassEffectMode
) {
    drawRect(color = palette.backgroundPrimary)
    if (glassMode == GlassEffectMode.FULL) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.ambientGlowTop, Color.Transparent),
                center = Offset(size.width * 0.22f, size.height * 0.08f),
                radius = size.maxDimension * 0.58f
            ),
            radius = size.maxDimension * 0.58f,
            center = Offset(size.width * 0.22f, size.height * 0.08f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.ambientGlowSecondary, Color.Transparent),
                center = Offset(size.width * 0.85f, size.height * 0.34f),
                radius = size.maxDimension * 0.45f
            ),
            radius = size.maxDimension * 0.45f,
            center = Offset(size.width * 0.85f, size.height * 0.34f)
        )
    }
}

private fun buildProgressiveAlphaMask(
    statusBarFraction: Float,
    activeReachFraction: Float,
    peakAlpha: Float,
    solidHoldFraction: Float
): Brush {
    val sb = statusBarFraction.coerceIn(0f, 0.68f)
    val remaining = (1f - sb).coerceAtLeast(0.05f)
    val reach = (remaining * activeReachFraction.coerceIn(0.05f, 1f)).coerceAtMost(1f - sb)
    val hold = solidHoldFraction.coerceIn(0.05f, 0.45f)

    fun stopAt(t: Float): Float = (sb + reach * t).coerceIn(0f, 1f)

    val s1 = stopAt(hold)
    val s2 = stopAt(hold + (1f - hold) * 0.25f)
    val s3 = stopAt(hold + (1f - hold) * 0.48f)
    val s4 = stopAt(hold + (1f - hold) * 0.68f)
    val s5 = stopAt(hold + (1f - hold) * 0.84f)
    val s6 = stopAt(hold + (1f - hold) * 0.94f)
    val sEnd = stopAt(1f)

    return Brush.verticalGradient(
        0.00f to Color.Black.copy(alpha = 1.00f * peakAlpha),
        s1 to Color.Black.copy(alpha = 1.00f * peakAlpha),
        s2 to Color.Black.copy(alpha = 0.84f * peakAlpha),
        s3 to Color.Black.copy(alpha = 0.54f * peakAlpha),
        s4 to Color.Black.copy(alpha = 0.26f * peakAlpha),
        s5 to Color.Black.copy(alpha = 0.09f * peakAlpha),
        s6 to Color.Black.copy(alpha = 0.02f * peakAlpha),
        sEnd to Color.Transparent,
        1.00f to Color.Transparent
    )
}

private fun buildProgressiveVeilBrush(
    baseColor: Color,
    statusBarFraction: Float,
    activeReachFraction: Float,
    maxAlpha: Float
): Brush {
    val sb = statusBarFraction.coerceIn(0f, 0.68f)
    val remaining = (1f - sb).coerceAtLeast(0.05f)
    val reach = (remaining * activeReachFraction.coerceIn(0.05f, 1f)).coerceAtMost(1f - sb)

    fun stopAt(t: Float): Float = (sb + reach * t).coerceIn(0f, 1f)

    return Brush.verticalGradient(
        0.00f to baseColor.copy(alpha = maxAlpha),
        stopAt(0.18f) to baseColor.copy(alpha = maxAlpha * 0.86f),
        stopAt(0.46f) to baseColor.copy(alpha = maxAlpha * 0.52f),
        stopAt(0.72f) to baseColor.copy(alpha = maxAlpha * 0.20f),
        stopAt(0.90f) to baseColor.copy(alpha = maxAlpha * 0.05f),
        stopAt(1.00f) to Color.Transparent,
        1.00f to Color.Transparent
    )
}

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float {
    return start + (end - start) * fraction.coerceIn(0f, 1f)
}

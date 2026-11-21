package com.shrey.downloader.core.design

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shrey.downloader.domain.model.GlassEffectMode

/**
 * Atmospheric obsidian / porcelain backdrop with soft, static radial light mist so
 * translucent glass surfaces sit naturally in space without per-frame animation overhead.
 */
@Composable
fun ShreyAmbientBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val glassMode = LocalGlassEffectMode.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.backgroundPrimary)
            .drawBehind {
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
    ) {
        content()
    }
}

/**
 * Shallow, iOS-inspired TOP-only gradual blur and atmospheric fade overlay.
 * Softly cushions scrolling content as it passes beneath the status bar without
 * hard edges, banding, or heavy per-frame full-screen blur overhead.
 * Intentionally applied only to the top edge (never the bottom).
 */
@Composable
fun ShreyTopGradualBlur(
    scrollProgress: Float = 1f,
    modifier: Modifier = Modifier,
    fadeHeightBelowStatusBar: Dp = 32.dp
) {
    val palette = ShreyThemeTokens.palette
    val glassMode = LocalGlassEffectMode.current
    val density = LocalDensity.current
    val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val totalHeight = statusBarHeight + fadeHeightBelowStatusBar

    // Subtle ambient presence at rest; gently deepens as content scrolls beneath the status bar.
    val intensity by animateFloatAsState(
        targetValue = 0.44f + (0.56f * scrollProgress.coerceIn(0f, 1f)),
        animationSpec = tween(durationMillis = 180),
        label = "top_gradual_blur_intensity"
    )

    val baseColor = palette.backgroundPrimary
    val frostedVeil = if (palette.isDark) {
        Color(0xFF10131A)
    } else {
        Color(0xFFFFFFFF)
    }

    // Smooth cubic-eased multi-stop alpha gradient eliminates visible banding across dark & light themes.
    val topVeilBrush = remember(baseColor, intensity) {
        Brush.verticalGradient(
            0.00f to baseColor.copy(alpha = 0.90f * intensity),
            0.22f to baseColor.copy(alpha = 0.80f * intensity),
            0.40f to baseColor.copy(alpha = 0.62f * intensity),
            0.56f to baseColor.copy(alpha = 0.40f * intensity),
            0.70f to baseColor.copy(alpha = 0.22f * intensity),
            0.82f to baseColor.copy(alpha = 0.10f * intensity),
            0.92f to baseColor.copy(alpha = 0.03f * intensity),
            1.00f to Color.Transparent
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .testTag("top_gradual_blur")
    ) {
        // Soft diffused frosted mist band confined to the upper status-bar edge on API 31+ in Full Glass mode.
        if (glassMode == GlassEffectMode.FULL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mistAlpha = (if (palette.isDark) 0.22f else 0.36f) * intensity
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusBarHeight + (fadeHeightBelowStatusBar * 0.55f))
                    .blur(radius = 12.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .background(
                        Brush.verticalGradient(
                            0.0f to frostedVeil.copy(alpha = mistAlpha),
                            0.6f to frostedVeil.copy(alpha = mistAlpha * 0.45f),
                            1.0f to Color.Transparent
                        )
                    )
            )
        }

        // Primary smooth multi-stop atmospheric dissolve
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(topVeilBrush)
        )
    }
}

/**
 * Foundational Liquid Glass container with layered translucency, directional top-lip
 * specular sheen, and subtle vertical hairline rim. Automatically adapts to Full vs Reduced mode.
 */
@Composable
fun ShreyGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    elevated: Boolean = false,
    tintOverride: Color? = null,
    borderHighlightOverride: Color? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val glassMode = LocalGlassEffectMode.current

    val baseFill = tintOverride ?: when {
        glassMode == GlassEffectMode.REDUCED -> palette.glassFillReduced
        elevated -> palette.glassFillElevated
        else -> palette.glassFillPrimary
    }

    val sheenBrush = remember(palette, glassMode) {
        if (glassMode == GlassEffectMode.FULL) {
            Brush.verticalGradient(
                colors = listOf(palette.glassHighlightTop, palette.glassHighlightBottom, Color.Transparent)
            )
        } else {
            SolidColor(Color.Transparent)
        }
    }

    val borderBrush = remember(palette, borderHighlightOverride) {
        Brush.verticalGradient(
            colors = listOf(
                borderHighlightOverride ?: palette.glassBorderTop,
                palette.glassBorderBottom
            )
        )
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(baseFill)
            .background(sheenBrush)
            .border(
                width = 0.85.dp,
                brush = borderBrush,
                shape = shape
            )
            .padding(contentPadding),
        content = content
    )
}

@Composable
fun ShreyGlassCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    ShreyGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevated = elevated,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun ShreyPrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    val palette = ShreyThemeTokens.palette
    val haptics = rememberShreyHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !loading) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "primary_pill_scale"
    )

    val containerColor = if (enabled) palette.accentSolid else palette.accentSolid.copy(alpha = 0.35f)
    val contentColor = if (enabled) palette.accentOnSolid else palette.accentOnSolid.copy(alpha = 0.6f)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = contentColor),
                enabled = enabled && !loading,
                role = Role.Button
            ) {
                haptics.confirm()
                onClick()
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
                Spacer(Modifier.width(10.dp))
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ShreyGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    contentColor: Color = ShreyThemeTokens.palette.textPrimary,
    height: Dp = 46.dp,
    horizontalPadding: Dp = 14.dp,
    iconSpacing: Dp = 6.dp
) {
    val palette = ShreyThemeTokens.palette
    val haptics = rememberShreyHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.975f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "glass_btn_scale"
    )

    val shape = RoundedCornerShape(15.dp)
    val effectiveHorizontalPad = if (text.isBlank()) 10.dp else horizontalPadding
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .defaultMinSize(minHeight = height)
            .clip(shape)
            .background(palette.glassFillElevated)
            .border(
                BorderStroke(
                    width = 0.85.dp,
                    brush = Brush.verticalGradient(listOf(palette.glassBorderTop, palette.glassBorderBottom))
                ),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = contentColor),
                enabled = enabled,
                role = Role.Button
            ) {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = effectiveHorizontalPad, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (enabled) contentColor else palette.textTertiary,
                    modifier = Modifier.size(16.dp)
                )
                if (text.isNotEmpty()) {
                    Spacer(Modifier.width(iconSpacing))
                }
            }
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) contentColor else palette.textTertiary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Compact square Liquid Glass icon button designed for secondary card actions (e.g., Hide, Delete, Info)
 * so primary labeled buttons retain generous horizontal breathing room on compact Android screens.
 */
@Composable
fun ShreyGlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = ShreyThemeTokens.palette.textSecondary,
    size: Dp = 44.dp
) {
    val palette = ShreyThemeTokens.palette
    val haptics = rememberShreyHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "glass_icon_btn_scale"
    )

    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size)
            .clip(shape)
            .background(palette.glassFillElevated)
            .border(
                BorderStroke(
                    width = 0.85.dp,
                    brush = Brush.verticalGradient(listOf(palette.glassBorderTop, palette.glassBorderBottom))
                ),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = contentColor),
                enabled = enabled,
                role = Role.Button
            ) {
                haptics.tap()
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) contentColor else palette.textTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun ShreyGlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onPasteClick: () -> Unit,
    onClearClick: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = ShreyThemeTokens.palette
    val haptics = rememberShreyHaptics()
    var isFocused by remember { mutableStateOf(false) }

    val borderTop by animateColorAsState(
        targetValue = if (isFocused) palette.accentSolid.copy(alpha = 0.55f) else palette.glassBorderTop,
        label = "url_field_border"
    )

    ShreyGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevated = isFocused,
        borderHighlightOverride = borderTop,
        contentPadding = PaddingValues(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = if (isFocused) palette.textPrimary else palette.textTertiary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary),
                    cursorBrush = SolidColor(palette.accentSolid),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("url_input_field")
                        .onFocusChanged { isFocused = it.isFocused }
                )
            }
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = {
                        haptics.tap()
                        onClearClick()
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("clear_url_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Clear link",
                        tint = palette.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        haptics.tap()
                        onPasteClick()
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("paste_url_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentPaste,
                        contentDescription = "Paste link from clipboard",
                        tint = palette.textSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun <T> ShreySegmentedControl(
    items: List<T>,
    selectedItem: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "segment",
    itemHeight: Dp = 42.dp
) {
    val palette = ShreyThemeTokens.palette
    val haptics = rememberShreyHaptics()

    ShreyGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val bg by animateColorAsState(
                    targetValue = if (isSelected) palette.pillSelectedFill else Color.Transparent,
                    label = "segment_bg"
                )
                val border by animateColorAsState(
                    targetValue = if (isSelected) palette.pillSelectedBorder else Color.Transparent,
                    label = "segment_border"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) palette.textPrimary else palette.textSecondary,
                    label = "segment_text"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = itemHeight)
                        .clip(RoundedCornerShape(13.dp))
                        .background(bg)
                        .border(0.75.dp, border, RoundedCornerShape(13.dp))
                        .clickable(
                            role = Role.Tab,
                            onClick = {
                                if (!isSelected) {
                                    haptics.tap()
                                    onSelect(item)
                                }
                            }
                        )
                        .semantics { selected = isSelected }
                        .testTag("${testTagPrefix}_${labelFor(item).lowercase().replace(" ", "_")}")
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelFor(item),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ShreyProgressBar(
    progressFraction: Float,
    modifier: Modifier = Modifier,
    indeterminate: Boolean = false
) {
    val palette = ShreyThemeTokens.palette
    val animatedFraction by animateFloatAsState(
        targetValue = progressFraction.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "shrey_progress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(CircleShape)
            .background(palette.progressTrack)
    ) {
        val fillWidth = if (indeterminate) 0.35f else animatedFraction
        Box(
            modifier = Modifier
                .fillMaxWidth(fillWidth.coerceAtLeast(0.02f))
                .height(5.dp)
                .clip(CircleShape)
                .background(palette.progressFill)
        )
    }
}

data class ShreyNavDestination(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val badgeCount: Int = 0
)

@Composable
fun ShreyGlassBottomBar(
    destinations: List<ShreyNavDestination>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    val haptics = rememberShreyHaptics()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        ShreyGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(28.dp),
            elevated = true,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { dest ->
                    val isSelected = dest.id == selectedId
                    val pillColor by animateColorAsState(
                        targetValue = if (isSelected) palette.pillSelectedFill else Color.Transparent,
                        label = "nav_pill"
                    )
                    val tint by animateColorAsState(
                        targetValue = if (isSelected) palette.textPrimary else palette.textTertiary,
                        label = "nav_tint"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 52.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(pillColor)
                            .clickable(
                                role = Role.Tab,
                                onClick = {
                                    if (!isSelected) {
                                        haptics.tap()
                                        onSelect(dest.id)
                                    }
                                }
                            )
                            .semantics {
                                selected = isSelected
                                contentDescription = dest.label
                            }
                            .testTag("nav_${dest.id}")
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Box {
                                Icon(
                                    imageVector = if (isSelected) dest.selectedIcon else dest.icon,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(21.dp)
                                )
                                if (dest.badgeCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(palette.accentSolid)
                                    )
                                }
                            }
                            Text(
                                text = dest.label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                color = tint,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShreyEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    ShreyGlassCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(palette.pillSelectedFill)
                    .border(0.75.dp, palette.glassBorderTop, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ShreyErrorCard(
    title: String,
    message: String,
    actionHint: String,
    technicalDetails: String?,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    var expanded by remember { mutableStateOf(false) }

    ShreyGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tintOverride = palette.statusErrorFill,
        borderHighlightOverride = palette.statusError.copy(alpha = 0.45f),
        contentPadding = PaddingValues(18.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = palette.statusError,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                if (actionHint.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = actionHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onRetry != null) {
                ShreyGlassButton(
                    text = "Retry",
                    onClick = onRetry,
                    modifier = Modifier.testTag("error_retry_button")
                )
            }
            if (!technicalDetails.isNullOrBlank()) {
                ShreyGlassButton(
                    text = if (expanded) "Hide Details" else "Technical Details",
                    leadingIcon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    onClick = { expanded = !expanded },
                    contentColor = palette.textSecondary,
                    modifier = Modifier.testTag("error_details_toggle")
                )
            }
        }

        AnimatedVisibility(
            visible = expanded && !technicalDetails.isNullOrBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.backgroundPrimary.copy(alpha = 0.65f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = technicalDetails.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                }
            }
        }
    }
}

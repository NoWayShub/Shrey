package com.shrey.downloader.feature.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shrey.downloader.core.design.ShreyGlassCard
import com.shrey.downloader.core.design.ShreyThemeTokens
import com.shrey.downloader.core.design.ShreyTopGradualBlur

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    val scroll = rememberScrollState()
    val topBlurProgress by remember {
        derivedStateOf { (scroll.value / 96f).coerceIn(0f, 1f) }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .verticalScroll(scroll)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SubScreenTopBar(title = "About Shrey", onBack = onBack)

            ShreyGlassCard(elevated = true) {
                Text(
                    text = "SHREY",
                    style = MaterialTheme.typography.displaySmall.copy(
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = palette.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Version 0.0.2 · Native Android",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Shrey is a local-first native Android media downloader designed around simplicity, speed, and restraint. Media inspection, stream merging, audio transcoding, and storage happen directly on your device using yt-dlp and FFmpeg.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            }

            ShreyGlassCard {
                Text(
                    text = "PRIVACY & LOCAL PROCESSING",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textTertiary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Shrey includes no cloud backend, account system, analytics SDK, or tracking telemetry. All extraction and media conversion run locally on your device. Network traffic occurs strictly between your device and the media hosts for the URLs you submit.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            }

            ShreyGlassCard {
                Text(
                    text = "CONTENT RESPONSIBILITY",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textTertiary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Shrey is intended exclusively for media you own or have permission to download. It does not bypass DRM, paywalls, private authentication gates, or regional access controls.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            }

            ShreyGlassCard {
                Text(
                    text = "TECHNOLOGY ACKNOWLEDGMENTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textTertiary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Powered locally by yt-dlp, youtubedl-android (JunkFood02 / yausername), FFmpeg, Jetpack Compose, Room, DataStore, Coil, and OkHttp. Shrey is an independent project and is not affiliated with or endorsed by these upstream projects.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            }
        }

        ShreyTopGradualBlur(
            scrollProgress = topBlurProgress,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

private data class LicenseNotice(
    val name: String,
    val license: String,
    val notes: String
)

private val shreyLicenses = listOf(
    LicenseNotice(
        name = "yt-dlp",
        license = "The Unlicense (Public Domain)",
        notes = "Core media metadata extraction and stream downloader."
    ),
    LicenseNotice(
        name = "youtubedl-android (library & ffmpeg)",
        license = "GNU General Public License v3.0 (GPL-3.0)",
        notes = "Android wrapper bundling Python runtime, yt-dlp, and FFmpeg binaries. Distribution of APKs containing GPLv3 components requires complying with GPLv3 source-availability obligations."
    ),
    LicenseNotice(
        name = "FFmpeg",
        license = "LGPL v2.1+ / GPL v2+ (depending on build configuration)",
        notes = "Local stream muxing and audio transcoding (M4A, MP3, Opus)."
    ),
    LicenseNotice(
        name = "Jetpack Compose, AndroidX, Room, DataStore, Navigation",
        license = "Apache License 2.0",
        notes = "Official Android UI, persistence, lifecycle, and navigation libraries."
    ),
    LicenseNotice(
        name = "Coil & OkHttp",
        license = "Apache License 2.0",
        notes = "Memory-aware thumbnail loading and TLS-verified HTTP client."
    ),
    LicenseNotice(
        name = "Plus Jakarta Sans & JetBrains Mono",
        license = "SIL Open Font License 1.1",
        notes = "Bundled typography assets."
    )
)

@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    val scroll = rememberScrollState()
    val topBlurProgress by remember {
        derivedStateOf { (scroll.value / 96f).coerceIn(0f, 1f) }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .verticalScroll(scroll)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SubScreenTopBar(title = "Open-Source Licenses", onBack = onBack)

            ShreyGlassCard {
                shreyLicenses.forEachIndexed { index, notice ->
                    if (index > 0) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = palette.divider)
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        text = notice.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = notice.license,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = notice.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textTertiary
                    )
                }
            }
        }

        ShreyTopGradualBlur(
            scrollProgress = topBlurProgress,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun SubScreenTopBar(
    title: String,
    onBack: () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .background(palette.glassFillPrimary)
                .border(0.75.dp, palette.glassBorderTop, CircleShape)
                .testTag("subscreen_back_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Navigate back",
                tint = palette.textPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = palette.textPrimary
        )
    }
}

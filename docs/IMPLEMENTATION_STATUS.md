# Shrey v0.0.2 — Implementation Status

## Current Milestone
v0.0.2 Beta UI Polish Pass (built on top of verified v0.0.1 real-device 4K download engine)

## v0.0.2 Focused UI Improvements
- **Bottom Navigation Overlap Fix (`ShreyNavigation.kt`, `ShreyGlassComponents.kt`, primary screens):**
  - Dynamically measures the floating glass bottom bar height via `onSizeChanged` and incorporates `WindowInsets.navigationBars` and `WindowInsets.ime` into `bottomClearance` so scrollable content on Home, Downloads, Library, and Settings always clears the floating pill bar and gesture/3-button system navigation bars.
- **Downloads & Library Action Row Redesign (`DownloadsScreen.kt`, `LibraryScreen.kt`, `ShreyGlassComponents.kt`):**
  - Added compact `ShreyGlassIconButton` for secondary card actions (Hide, Delete, Technical Details, Remove) and tightened `ShreyGlassButton` padding/icon spacing so primary `Open`, `Share`, and `Retry` labels never clip on narrow screens.
  - Added `BoxWithConstraints` + `fontScale` adaptive two-row wrapping for ultra-narrow screens or large accessibility font scales.
- **Settings Layout Density & Polish (`SettingsScreen.kt`):**
  - Refined card padding, vertical rhythm, sub-section `HorizontalDivider` grouping, sleeker `SettingPillChip` dimensions, tighter `FlowRow` chip spacing, and ellipsis-safe `DiagnosticRow` layout while preserving all existing settings and behavior.
- **Subtle TOP-Only Gradual Blur (`ShreyTopGradualBlur`):**
  - Implemented native Jetpack Compose `ShreyTopGradualBlur` with smooth multi-stop cubic-eased alpha fade (eliminating banding in Dark and Light themes), scroll-reactive intensity, and gentle API 31+ `Modifier.blur` frosted mist confined strictly to the upper status-bar edge (no bottom blur). Applied across Home, Downloads, Library, Settings, About, and Licenses.
- **Zero Engine Regressions:**
  - `yt-dlp`, `youtubedl-android`, `FFmpeg`, `Room`, `DataStore`, `MediaStoragePublisher`, and `ShreyDownloadService` remain untouched.

## Completed Baseline (v0.0.1)
- **Foundation & Engine:** `applicationId = "com.shrey.downloader"`, `minSdk = 29`, `targetSdk = 36`, multi-ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), `youtubedl-android:0.18.1` + `:ffmpeg:0.18.1`, `UrlValidator`, `YtDlpMediaExtractor`, `YtDlpDownloadEngine`, `MediaStoragePublisher`, Room queue & DataStore preferences, and foreground `ShreyDownloadService`.

## Known Limitations
- **yt-dlp-ejs JS Runtime:** `youtubedl-android:0.18.1` does not bundle a verified Android Deno/QuickJS runtime for YouTube `n`-challenge solving; reported transparently in Settings diagnostics.
- **Real-Device Visual Verification:** Automated JVM/Robolectric tests run headless; final visual check of blur softness, insets, and touch ergonomics on physical hardware (Samsung phone) should be verified after installing `app/build/outputs/apk/debug/app-debug.apk`.


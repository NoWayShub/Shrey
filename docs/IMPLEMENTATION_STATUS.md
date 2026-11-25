# Shrey v0.0.3 — Implementation Status

## Current Milestone
v0.0.3 Beta Release (Intelligent Updates & Liquid Glass)

## v0.0.3 Key Additions
- **Intelligent Update Subsystem (`core/update/`):**
  - Single-flight `UpdateManager` checking GitHub releases (Stable & Beta channels), `yt-dlp` updates, and bundled FFmpeg compatibility (`7.1.1`).
  - `DailyUpdateCheckWorker` (`WorkManager 2.10.0`) for periodic background update checks and notifications.
  - Cryptographic verification via `UpdateManifestVerifier` (ECDSA-SHA256 P-256) and `ApkUpdateVerifier` (SHA-256 & signing certificate validation).
  - `SafeEngineUpdater` providing staged archive swaps, smoke verification, and automatic rollback to last-known-good engine state.
  - `YtDlpArchiveInspector` for fast in-process inspection of `yt-dlp` and `yt-dlp-ejs` archive versions without spawning Python.
- **Settings Updates Card (`SettingsUpdatesCard.kt`):**
  - Integrated Liquid Glass update card in Settings with channel selector, auto-check preferences, live compatibility details, and release notes preview.
- **Liquid Glass Refinement (`ShreyTopGradualBlur.kt`, `ShreyGlassComponents.kt`):**
  - Dedicated `ShreyTopGradualBlur` component and updated UI navigation/card layouts.
- **Test Coverage:** 55 automated unit & Robolectric tests verified passing (0 failures, 0 errors).

## Completed Baseline (v0.0.1 – v0.0.2)
- **Foundation & Engine:** `applicationId = "com.shrey.downloader"`, `minSdk = 29`, `targetSdk = 36`, multi-ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), `youtubedl-android:0.18.1` + `:ffmpeg:0.18.1`, `UrlValidator`, `YtDlpMediaExtractor`, `YtDlpDownloadEngine`, `MediaStoragePublisher`, Room queue & DataStore preferences, and foreground `ShreyDownloadService`.
- **UI Ergonomics:** Adaptive bottom navigation bar clearance, compact secondary icon actions, narrow-display wrapping, and top-only gradual blur.

## Known Limitations
- **yt-dlp-ejs JS Runtime:** `youtubedl-android:0.18.1` does not bundle an external Deno runtime; Shrey inspects bundled QuickJS/EJS versions honestly and reports compatibility live in Settings diagnostics.
- **Unsupported Streams:** DRM-protected streams, live broadcasts, and authenticated media remain unsupported.

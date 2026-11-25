# Changelog

All notable changes to **Shrey** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.0.3-beta] - 2026-11-25

### Added
- **Intelligent Update Subsystem (`core/update/`):**
  - Added `UpdateManager` with single-flight update checking across Shrey GitHub releases (Stable and Beta channels), `yt-dlp`, and FFmpeg runtime compatibility.
  - Added `DailyUpdateCheckWorker` powered by WorkManager (`2.10.0`) for background scheduled update checks with deduplicated update notifications.
  - Added `UpdateManifestVerifier` (ECDSA-SHA256 P-256 signature verification) and `ApkUpdateVerifier` (SHA-256 and APK signature checks) for secure release validation.
  - Added `SafeEngineUpdater` with staged zipimport archive replacement, health smoke checks, and automatic rollback to last-known-good `yt-dlp`.
  - Added `YtDlpArchiveInspector` to inspect bundled/downloaded `yt-dlp` and `yt-dlp-ejs` zipimport versions without spawning Python.
  - Updated bundled `res/raw/ytdlp` runtime baseline.
- **Settings Updates Card (`SettingsUpdatesCard.kt`):** Added dedicated UI card in Settings for update channel switching (Stable/Beta), auto-check controls, manual checks, runtime compatibility diagnostics, and changelog previews.
- **Standalone Top Gradual Blur Component (`ShreyTopGradualBlur.kt`):** Extracted and refined `ShreyTopGradualBlur` Liquid Glass overlay.
- **Expanded Automated Testing:** Added 24 new tests covering archive inspection, manifest signature verification, WorkManager scheduling, safe engine updates/rollback, and Settings UI update cards (55 total automated unit & Robolectric tests).

---

## [0.0.2] - 2026-11-21

### Changed / Improved
- **Bottom Navigation Clearance:** Dynamically measures the floating glass bottom bar height and calculates adaptive clearance incorporating `WindowInsets.navigationBars` and `WindowInsets.ime`. Scrollable content on Home, Downloads, Library, and Settings now fully clears the floating pill bar across gesture and 3-button navigation.
- **Action Button Layout:** Redesigned download card actions with `ShreyGlassIconButton` for secondary actions (Hide, Delete, Technical Details) and `BoxWithConstraints` adaptive wrapping. Primary `Open`, `Share`, and `Retry` labels remain fully readable on narrow screens without truncation.
- **Settings Layout Density:** Refined card padding, vertical rhythm, sleeker `SettingPillChip` dimensions, tighter `FlowRow` chip spacing, and ellipsis-safe `DiagnosticRow` formatting for better display ergonomics.
- **Top Gradual Blur Overlay:** Added `ShreyTopGradualBlur` with smooth multi-stop cubic-eased alpha gradient and scroll-reactive intensity confined strictly to the upper status-bar edge across all primary screens.
- **Automated Testing:** Added Robolectric tests covering narrow 360dp card actions and top gradual blur rendering (31 total automated tests).

---

## [0.0.1-beta] - 2026-11-21

### Added
- **Initial Public Beta Source Release:** Native Android media downloader powered by `yt-dlp` and `FFmpeg`.
- **Extraction Engine:** Mutex-protected `EngineCoordinator` integrating `youtubedl-android:0.18.1` and `:ffmpeg:0.18.1`.
- **Format Selection & Analysis:** Extraction and inspection of actual format IDs, bitrates, codecs, resolutions (up to 4K / 2160p), frame rates, and estimated vs. exact file sizes.
- **Four Download Modes:**
  - Video with Audio (direct stream muxing or FFmpeg merge).
  - Audio Only (original stream or transcoding to M4A, MP3, Opus).
  - Video Only (isolated stream extraction).
  - Thumbnail Only (OkHttp TLS fetch with Bitmap conversion).
- **Persistent Queue & Storage:** Room database queue, MediaStore atomic publishing (`Movies/Shrey`, `Music/Shrey`, `Pictures/Shrey`), SAF custom directory support, and Android 14/15/16 `dataSync` Foreground Service with cancellation and progress notifications.
- **Liquid Glass Interface:** Dark (`#08090B`) and Light (`#F4F5F8`) themes, Full/Reduced glass blur modes, and tactile feedback.
- **Comprehensive Unit Testing:** 29 automated unit and Robolectric tests covering URL validation, JSON parsing, format mapping, progress mapping, database persistence, and UI flows.
- **Open-Source Compliance:** GNU General Public License v3.0 (`LICENSE`), comprehensive third-party attributions (`LICENSES.md`), and build documentation (`docs/BUILDING.md`).

### Known Limitations in v0.0.1-beta
- YouTube JavaScript challenge runtime (`yt-dlp-ejs`) is not bundled in `youtubedl-android:0.18.1`; status is reported live in Settings diagnostics.
- Floating bottom navigation overlays content on Home and Settings (scheduled for fix in v0.0.2).
- Action button labels may truncate on narrow viewports on the Downloads screen (scheduled for fix in v0.0.2).
- Physical testing confirmed for 4K video download on Samsung Galaxy device; audio transcoding and video-only modes require broader device validation.

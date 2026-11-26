# Shrey v0.0.4 — Implementation Status

## Current Milestone
v0.0.4 Beta Release (Playlists, Channel Bulk Downloads & Liquid Glass)

## v0.0.4 Key Additions
- **Smart URL Classification & Paginated Collection Extraction (`MediaUrlClassifier`, `YtDlpCollectionExtractor`):**
  - Canonical URI classification for single videos, playlists, YouTube channels (`Videos`, `Shorts`, `Completed Streams` tabs), and ambiguous video/playlist links.
  - Incremental `--flat-playlist` extraction (`50` items/page) preventing excessive memory consumption on large channels.
- **Batch Download Orchestration (`BatchDownloadCoordinator`, Room v3):**
  - Extends `DownloadQueueManager` with global `concurrency = 1`, cross-queue deduplication, `BulkQualityPreset` & `ChannelScopePreset` selection, subfolder organization, pause/resume, and process-death recovery.
  - Persistent Room entities (`DownloadBatchEntity`, `BatchItemEntity`, `ChannelDiscoveryCheckpointEntity`) with non-destructive migrations (`MIGRATION_1_2`, `MIGRATION_2_3`).
- **Batch & Collection UI (`CollectionPreviewCards.kt`, `BatchDownloadCards.kt`):**
  - Interactive playlist/channel entry preview, tab/scope controls, and live batch progress cards matching the Liquid Glass design system.
- **Version & Update Polish (`UpdateVersionComparator.kt`, `UpdateCheckerV004Test.kt`):**
  - Refined `versionCode` + SemVer precedence rules for safe in-app release vs. prerelease checks.

## Completed Baseline (v0.0.1 – v0.0.3)
- **Foundation & Engine:** `applicationId = "com.shrey.downloader"`, `minSdk = 29`, `targetSdk = 36`, multi-ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), `youtubedl-android:0.18.1` + `:ffmpeg:0.18.1`, `UrlValidator`, `YtDlpMediaExtractor`, `YtDlpDownloadEngine`, `MediaStoragePublisher`, Room queue & DataStore preferences, and foreground `ShreyDownloadService`.
- **Intelligent Updates:** Single-flight `UpdateManager`, `DailyUpdateCheckWorker` (WorkManager), `UpdateManifestVerifier` (ECDSA-SHA256 P-256), `ApkUpdateVerifier`, and `SafeEngineUpdater` rollback support.

## Known Limitations
- **yt-dlp-ejs JS Runtime:** `youtubedl-android:0.18.1` does not bundle an external Deno runtime; Shrey inspects bundled QuickJS/EJS versions honestly and reports compatibility live in Settings diagnostics.
- **Unsupported Streams:** DRM-protected streams, live broadcasts, and authenticated media remain unsupported.

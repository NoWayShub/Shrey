# Shrey — Known Issues and Technical Limitations

This document tracks known issues, technical limitations, and targeted improvements for upcoming releases of **Shrey**.

---

## 1. User Interface Issues (Resolved in v0.0.2)

### Issue 1: Floating Bottom Navigation Overlaps Content
- **Description:** On the Home and Settings screens, the floating navigation bar overlays the bottom portion of scrollable content.
- **Affected Areas:** `HomeScreen`, `SettingsScreen`, `ShreyAppRoot` Scaffold bottom bar insets.
- **Root Cause:** Content padding did not dynamically account for the floating navigation bar's measured height plus system navigation bar window insets.
- **Status:** Resolved in v0.0.2 via dynamic height measurement and adaptive bottom clearance in `ShreyNavigation.kt`.

### Issue 2: Truncated Action Buttons on Downloads Screen
- **Description:** On certain screen sizes and font scalings, action buttons on download cards displayed truncated labels such as `O...` (Open) and `Sh...` (Share).
- **Affected Area:** `DownloadsScreen.kt` and `LibraryScreen.kt` action button rows.
- **Root Cause:** Fixed button widths and horizontal constraints without adaptive wrapping or icon-primary fallbacks.
- **Status:** Resolved in v0.0.2 with `ShreyGlassIconButton` for secondary actions and `BoxWithConstraints` adaptive wrapping.

### Issue 3: Settings Screen Layout Density
- **Description:** Several chips and option rows in Settings had excessive padding or wrapped awkwardly on narrow displays.
- **Affected Area:** `SettingsScreen.kt`.
- **Status:** Resolved in v0.0.2 with streamlined chip dimensions, refined padding, and ellipsis-safe diagnostic rows.

---

## 2. Engine and Upstream Platform Limitations

### Issue 4: yt-dlp JavaScript Challenge Runtime (`yt-dlp-ejs`)
- **Description:** Recent upstream `yt-dlp` updates utilize external JavaScript runtimes (e.g., QuickJS, Deno) to resolve certain YouTube `n`-parameter and signature deciphering algorithms.
- **Impact:** Upstream `io.github.junkfood02.youtubedl-android:0.18.1` does not yet ship an integrated cross-ABI Android JS engine binary. Videos requiring this challenge may experience reduced download speeds or extraction failures.
- **Mitigation:** Shrey checks `nativeLibraryDir` for bundled engines, displays honest real-time status in Settings diagnostics, and surfaces clear error messages when challenge solvers are missing.

### Issue 5: Unsupported Content Types
- **Live Streams:** Active live video feeds cannot be downloaded in real time in v0.0.1.
- **DRM-Protected Content:** Encrypted DRM streams (e.g., Widevine DRM) are strictly unsupported and will not be decrypted.
- **Private / Authenticated Media:** Media requiring user logins or session cookies is not supported in v0.0.1.

---

## 3. Physical Device Testing Status

The v0.0.1 release has undergone initial physical-device verification on a Samsung Galaxy smartphone:
- **Verified:** 2160p / 4K YouTube video extraction, multi-format stream analysis, download, MediaStore atomic publishing, and local playback.
- **Pending Physical Validation:**
  - Audio transcoding to MP3, M4A, and Opus formats under low-memory conditions.
  - Video-only stream downloading without audio track merging.
  - Background download cancellation and retry during network disconnection.
  - SAF (Storage Access Framework) custom folder writing across non-Samsung OEM Android implementations.

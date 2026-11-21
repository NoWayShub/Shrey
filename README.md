# Shrey

<p align="center">
  <strong>Your media. Your way.</strong>
</p>

<p align="center">
  A native Android media downloader powered locally by <strong>yt-dlp</strong> and <strong>FFmpeg</strong>, featuring a clean Liquid Glass-inspired interface, multi-format stream analysis, and local media management.
</p>

<p align="center">
  <a href="#features">Features</a> •
  <a href="#installation">Installation</a> •
  <a href="#requirements">Requirements</a> •
  <a href="#how-to-use">How to Use</a> •
  <a href="#technology-stack">Tech Stack</a> •
  <a href="#building-from-source">Building</a> •
  <a href="#privacy">Privacy</a> •
  <a href="#known-limitations">Limitations</a> •
  <a href="#license">License</a>
</p>

---

## Overview

**Shrey** is an independent, open-source Android application designed to give you direct control over your media. It executes yt-dlp metadata extraction and media downloading entirely on your device—with zero intermediary cloud servers, zero account sign-ins, and zero analytics trackers.

---

## Features

### Implemented Core Capabilities
- **Local yt-dlp Extraction:** Analyzes media URLs directly on-device to retrieve titles, channels, durations, thumbnails, and complete format manifests.
- **Granular Format Selection:** Inspects real streams with resolutions up to 4K (2160p), bitrates, frame rates (FPS), HDR flags, and audio codecs.
- **Four Download Modes:**
  1. **Video with Audio:** Direct combined streams or automated FFmpeg muxing of adaptive video and audio tracks.
  2. **Audio Only:** Direct stream extraction or FFmpeg transcoding to M4A, MP3, or Opus.
  3. **Video Only:** Downloads high-resolution video streams without muxing audio.
  4. **Thumbnail Only:** Downloads high-resolution thumbnails with Bitmap JPG/PNG export.
- **Scoped Storage & SAF:** Atomic pending writes via Android MediaStore (`Movies/Shrey`, `Music/Shrey`, `Pictures/Shrey`) and custom target directories via Storage Access Framework (SAF).
- **Persistent Queue & Service:** Room-backed sequential download queue, process-death recovery, and an Android `dataSync` Foreground Service with live notification progress and cancellation.
- **Liquid Glass Interface:** Material 3 design with dark (`#08090B`) and light (`#F4F5F8`) themes, blur intensity options, and haptic feedback.
- **System Share Target:** Accepts shared URLs directly from browsers and other apps via Android's native share sheet.

### Verified Physical-Device Testing
- **Confirmed on Samsung Galaxy Device (Android 14+):**
  - Application launch and UI navigation.
  - YouTube media analysis and real metadata extraction.
  - Selection and download of a 2160p / 4K video stream (~44.9 MB).
  - MediaStore atomic publication and in-app library playback.
- **Pending Broader Physical Validation:**
  - Audio transcoding under memory constraints.
  - Video-only stream downloads.
  - Thumbnail-only downloads.
  - Background downloads across unstable network connections.

---

## Installation

1. Navigate to the official [GitHub Releases](https://github.com/NoWayShub/Shrey/releases) page.
2. Download the latest `Shrey-v0.0.1-beta.apk` asset.
3. On your Android smartphone, tap the downloaded APK to install.
4. If prompted, enable **Install unknown apps** for your browser or file manager in Android Settings (`Settings > Apps > Special app access > Install unknown apps`).
5. Open **Shrey** and grant the notification permission when prompted so you can monitor download progress in the background.

---

## Requirements

| Requirement | Specification |
| :--- | :--- |
| **Minimum Android Version** | Android 10 (API Level 29) |
| **Target SDK** | Android 16 (API Level 36) |
| **Supported ABIs** | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` |
| **Network** | Direct Internet connection for stream downloading |

---

## How to Use

1. **Copy a Media URL:** Copy a URL from your web browser or supported media source.
2. **Analyze in Shrey:** Open Shrey and paste the link into the URL field (or share it to Shrey via the Android Share menu), then tap **Analyze**.
3. **Choose Mode & Quality:** Select your desired download mode (Video + Audio, Audio Only, Video Only, or Thumbnail) and pick from the available resolutions and bitrates.
4. **Download:** Tap **Download**. Shrey queues the job and runs the download via a persistent foreground service.
5. **Manage & Play:** View real-time progress in the notification shade or the **Downloads** tab. Access finished downloads anytime in the **Library** tab.

---

## Technology Stack

- **Language:** Kotlin 2.4 / Jetpack Compose (BOM 2024.09.00)
- **Architecture:** MVVM + Clean Architecture with Coroutines & StateFlow
- **Extraction & Engine:** [yt-dlp](https://github.com/yt-dlp/yt-dlp) via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (`0.18.1`)
- **Muxing & Transcoding:** [FFmpeg](https://ffmpeg.org/) bundled via `youtubedl-android:ffmpeg`
- **Database & Persistence:** Room 2.7.0 (with KSP code generation) & Jetpack DataStore Preferences
- **Image Loading:** Coil Compose 2.7.0
- **HTTP Networking:** OkHttp 4.10.0
- **Testing:** JUnit 4, Robolectric 4.16.1, AndroidX Compose UI Test

---

## Building from Source

Detailed build and configuration steps are documented in [docs/BUILDING.md](docs/BUILDING.md).

```powershell
# Clone the repository
git clone https://github.com/NoWayShub/Shrey.git
cd Shrey

# Build debug APK
.\gradlew.bat assembleDebug

# Run test suite
.\gradlew.bat testDebugUnitTest
```

---

## Privacy

Shrey is engineered with a strict privacy-first architecture:
- **No Telemetry or Tracking:** Zero tracking libraries, advertising SDKs, or third-party analytics are bundled.
- **No Cloud Proxies:** All extraction and downloads connect directly between your device and the media source.
- **Local Storage:** Download history, preferences, and queue state remain strictly on your local device.

---

## Known Limitations

Please review [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) for full details on current limitations and roadmap items:
- **YouTube JS Runtime (`yt-dlp-ejs`):** Certain YouTube signature challenges require external JS runtime engines (Deno/QuickJS) not yet bundled by `youtubedl-android:0.18.1`. Shrey surfaces live diagnostics in Settings.
- **UI Adjustments:** Minor visual layout overlaps (floating bottom nav content padding and button label clipping on narrow screens) are tracked and slated for refinement in v0.0.2.
- **Encrypted / Private Streams:** Live feeds, DRM-encrypted media, and paywalled/private videos are unsupported.

---

## Legal Notice

Shrey is intended strictly for personal archiving and downloading of media that you own or have explicit authorization to download. Users are solely responsible for complying with applicable local copyright laws and third-party terms of service. Shrey is an independent project and is not affiliated with, authorized by, or endorsed by Google LLC, YouTube, Apple Inc., or any other service.

---

## License

Shrey is open-source software licensed under the **GNU General Public License v3.0 (GPL-3.0)**.
See the [LICENSE](LICENSE) file for the complete license text.

For third-party dependencies, bundled libraries, and font notices, please consult [LICENSES.md](LICENSES.md).

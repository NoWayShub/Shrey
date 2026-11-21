# Open-Source Licenses & Attribution

Shrey v0.0.1 is an independent native Android application and is not affiliated with or endorsed by any of the upstream projects listed below.

## Core Media Engine & Native Components

### 1. yt-dlp
- **Project:** https://github.com/yt-dlp/yt-dlp
- **License:** The Unlicense (Public Domain)
- **Usage:** Local media metadata extraction and stream downloading.

### 2. youtubedl-android (`io.github.junkfood02.youtubedl-android:library` & `:ffmpeg`, v0.18.1)
- **Project:** https://github.com/JunkFood02/youtubedl-android (originally forked from `yausername/youtubedl-android`)
- **License:** GNU General Public License v3.0 (GPL-3.0)
- **Obligations:** Because `youtubedl-android` is licensed under GPL-3.0 and bundles Python/yt-dlp/FFmpeg Android binaries, any public distribution of compiled Shrey APKs or AABs incorporating these artifacts must comply with GPL-3.0, including providing complete corresponding source code and preserving GPL-3.0 license notices.

### 3. FFmpeg (packaged via `youtubedl-android:ffmpeg`)
- **Project:** https://ffmpeg.org/
- **License:** LGPL v2.1+ / GPL v2+ (depending on upstream build configuration)
- **Usage:** Local adaptive video+audio stream muxing and audio transcoding (M4A, MP3, Opus).
- **Obligations:** Distribution must preserve FFmpeg copyright notices, license text, and comply with the upstream `youtubedl-android:ffmpeg` build license terms.

### 4. Python Runtime (bundled by `youtubedl-android`)
- **Project:** https://www.python.org/
- **License:** Python Software Foundation License Version 2 (PSF-2.0)

## Android, UI, Storage & Networking Libraries

- **AndroidX Core, Activity, Lifecycle, Navigation Compose, Room, DataStore, DocumentFile** — Apache License 2.0
- **Jetpack Compose & Material 3** — Apache License 2.0
- **Kotlin Coroutines & kotlinx.serialization** — Apache License 2.0
- **Coil (`io.coil-kt:coil-compose`)** — Apache License 2.0
- **OkHttp (`com.squareup.okhttp3:okhttp`)** — Apache License 2.0
- **JUnit 4** — Eclipse Public License 1.0
- **Robolectric** — MIT License / Apache License 2.0

## Bundled Typography

- **Plus Jakarta Sans** — SIL Open Font License 1.1
- **JetBrains Mono** — SIL Open Font License 1.1

## Content & Distribution Notice

Shrey is intended strictly for media the user owns or has authorization to download. It does not implement DRM circumvention, paywall bypassing, or credential extraction. Distribution on Google Play is subject to Google Play Developer Program Policies and regional copyright regulations and is not guaranteed.

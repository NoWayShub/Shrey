# Building Shrey from Source

This document describes how to compile, test, and package **Shrey** on your local workstation.

## System Prerequisites

1. **Java Development Kit (JDK):**
   - JDK 17, 21, or 25 (OpenJDK or Android Studio JetBrains Runtime / JBR).
   - Ensure `JAVA_HOME` is set to your JDK installation path.

2. **Android SDK:**
   - Android SDK Platform 36 (`compileSdk = 36`, `targetSdk = 36`).
   - Android SDK Build-Tools 36.0.0 or newer.
   - Android NDK (required for native yt-dlp and FFmpeg library linking).
   - Minimum supported device SDK: Android 10 (API level 29).

3. **Operating System:**
   - Windows 10/11, macOS, or modern Linux distribution.

## Gradle Wrapper Portability

The project includes the standard Gradle Wrapper (`v9.3.1`). It downloads automatically from the official Gradle services distribution without requiring any local pre-installed Gradle directory:

- Linux / macOS: `./gradlew`
- Windows (PowerShell / Command Prompt): `.\gradlew.bat`

## Build Commands

### 1. Compile and Build Debug APK

```powershell
.\gradlew.bat assembleDebug
```

The resulting debug APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 2. Run Automated Unit and Robolectric Tests

```powershell
.\gradlew.bat testDebugUnitTest
```

Test reports are placed in:
`app/build/reports/tests/testDebugUnitTest/`

### 3. Build Signed Release APK

To build a release APK, configure your signing credentials via environment variables:

```powershell
$env:KEYSTORE_PATH = "C:\path\to\your\release.keystore"
$env:STORE_PASSWORD = "your_store_password"
$env:KEY_PASSWORD = "your_key_password"
.\gradlew.bat assembleRelease
```

The output APK will be placed at:
`app/build/outputs/apk/release/app-release.apk`

### 4. Verify APK Signature and Alignment

```powershell
apksigner verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
```

## ABI Architecture Details

Shrey bundles the `youtubedl-android` native runtime and FFmpeg binaries. The build supports:
- `arm64-v8a` (Primary target for modern 64-bit Android smartphones)
- `armeabi-v7a` (32-bit ARM legacy support)
- `x86_64` (64-bit Android Studio emulators)
- `x86` (32-bit Android Studio emulators)

Due to bundled Python, yt-dlp, and FFmpeg native shared libraries across 4 ABIs, universal APKs are approximately 250 MB.

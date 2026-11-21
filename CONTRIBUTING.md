# Contributing to Shrey

Thank you for your interest in contributing to **Shrey**! Shrey is a native Android media downloader built with Kotlin, Jetpack Compose, yt-dlp, and FFmpeg under the GNU General Public License v3.0 (GPL-3.0).

---

## 1. Code of Conduct & Ground Rules

- **Respect Intellectual Property & Legal Boundaries:** Shrey is intended solely for media the user owns or is authorized to download. We do not accept contributions that circumvent DRM, bypass paywalls, extract authenticated session cookies, or violate copyright protections.
- **Maintain Architectural Cleanliness:** Keep engine extraction, persistence, background execution, and UI cleanly separated.
- **Licensing Compliance:** All code contributed to Shrey will be distributed under the terms of the GNU General Public License v3.0 (GPL-3.0).

---

## 2. Reporting Issues

Before opening a new issue:
1. Search existing [GitHub Issues](https://github.com/NoWayShub/Shrey/issues) to ensure the bug or feature hasn't already been reported.
2. Check [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) for existing known limitations.
3. When filing a bug report, use our **Bug Report** template and provide:
   - Android OS version and device model.
   - Exact steps to reproduce.
   - Observed behavior vs. expected behavior.
   - Relevant logcat snippets (ensure no private URLs or personal information are included).

---

## 3. Development Workflow

### Setting Up Your Environment
1. Clone the repository:
   ```bash
   git clone https://github.com/NoWayShub/Shrey.git
   cd Shrey
   ```
2. Open the project in Android Studio (Meerkat 2024.3+ or newer) with Android SDK 36 and JDK 21+ configured.
3. Build the project locally:
   ```bash
   ./gradlew assembleDebug
   ```

### Running Tests
Always run unit and Robolectric tests prior to submitting changes:
```bash
./gradlew testDebugUnitTest
```
Ensure all tests pass and any new features include corresponding unit or Robolectric test coverage.

### Pull Request Guidelines
1. Create a descriptive feature branch:
   ```bash
   git checkout -b feature/adaptive-nav-insets
   ```
2. Keep commits atomic with meaningful imperative messages (e.g., `Fix floating navigation bar bottom content padding`).
3. Push to your fork and submit a Pull Request targeting the `main` branch.
4. Ensure CI checks pass on your PR.

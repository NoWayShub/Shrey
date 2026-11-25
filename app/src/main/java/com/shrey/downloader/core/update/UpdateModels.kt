package com.shrey.downloader.core.update

import kotlinx.serialization.Serializable

enum class UpdateChannel(val label: String) {
    STABLE("Stable"),
    BETA("Beta")
}

enum class UpdateCheckState(val label: String) {
    IDLE("Not checked yet"),
    CHECKING("Checking…"),
    UP_TO_DATE("Up to date"),
    UPDATE_AVAILABLE("Update available"),
    FAILED("Check failed")
}

enum class EngineCompatibilityStatus(val label: String) {
    COMPATIBLE("Compatible"),
    UNSIGNED_UPSTREAM_ONLY("Unverified upstream only"),
    INCOMPATIBLE_PYTHON("Requires newer Python"),
    INCOMPATIBLE_RUNTIME("Requires newer JS runtime"),
    UNKNOWN("Compatibility unknown")
}

data class UpdatePreferences(
    val autoCheckEnabled: Boolean = true,
    val autoInstallEngineUpdates: Boolean = false,
    val notifyOnUpdates: Boolean = true,
    val channel: UpdateChannel = UpdateChannel.STABLE
)

/**
 * Maintainer-authored, cryptographically signed manifest describing an approved yt-dlp engine update
 * and the supported bundled FFmpeg baseline.
 */
@Serializable
data class SignedEngineManifest(
    val schemaVersion: Int = 1,
    val ytDlpVersion: String,
    val ejsVersion: String,
    val minPythonVersion: String = "3.11",
    val minQuickJsVersion: String = "2025-04-26",
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAtIso: String,
    val supportedFfmpegVersion: String = "7.1.1"
)

/**
 * Signed wrapper verified using ECDSA-SHA256 (P-256) against the embedded maintainer SPKI public key.
 */
@Serializable
data class SignedManifestEnvelope(
    val keyId: String,
    val manifestJson: String,
    val signatureBase64: String
)

data class GitHubReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val isPrerelease: Boolean,
    val publishedAt: String?,
    val assets: List<GitHubReleaseAsset>
)

data class GitHubReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String? = null
)

data class EngineUpdateStatus(
    val installedVersion: String,
    val lastKnownGoodVersion: String,
    val installedEjsVersion: String,
    val installedPythonVersion: String,
    val installedQuickJsVersion: String,
    val availableVersion: String? = null,
    val availableReleaseUrl: String? = null,
    val compatibilityStatus: EngineCompatibilityStatus = EngineCompatibilityStatus.UNKNOWN,
    val compatibilityDetail: String = "Up to date with verified bundled release.",
    val updateAvailable: Boolean = false,
    val signedManifestVerified: Boolean = false,
    val canSafelyActivateAtRuntime: Boolean = false,
    val lastActivationMessage: String? = null
)

data class FfmpegUpdateStatus(
    val bundledVersion: String = "7.1.1",
    val supportedVersion: String = "7.1.1",
    val availableViaAppUpdate: Boolean = false,
    val note: String = "Bundled native FFmpeg is updated exclusively via signed Shrey APK releases."
)

data class AppUpdateStatus(
    val installedVersionName: String,
    val installedVersionCode: Long,
    val latestVersionName: String? = null,
    val latestTagName: String? = null,
    val isPrerelease: Boolean = false,
    val updateAvailable: Boolean = false,
    val releaseNotes: String? = null,
    val releasePageUrl: String? = null,
    val apkDownloadUrl: String? = null,
    val apkFileName: String? = null,
    val apkSizeBytes: Long? = null,
    val isDownloadingApk: Boolean = false,
    val apkDownloadProgress: Float? = null,
    val verifiedApkUri: String? = null,
    val verificationStatusMessage: String? = null,
    val verificationError: String? = null
)

data class UpdateSnapshot(
    val preferences: UpdatePreferences = UpdatePreferences(),
    val lastCheckedAtMillis: Long? = null,
    val checkState: UpdateCheckState = UpdateCheckState.IDLE,
    val lastErrorMessage: String? = null,
    val engine: EngineUpdateStatus,
    val ffmpeg: FfmpegUpdateStatus = FfmpegUpdateStatus(),
    val app: AppUpdateStatus,
    val distributionPolicyNote: String =
        "Automatic runtime yt-dlp installation stays disabled unless a maintainer-signed manifest is configured and verified. Native FFmpeg updates ship only inside signed Shrey APK releases."
) {
    val hasAnyUpdateAvailable: Boolean
        get() = app.updateAvailable || engine.updateAvailable || ffmpeg.availableViaAppUpdate
}

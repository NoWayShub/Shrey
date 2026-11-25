package com.shrey.downloader.core.update

import kotlinx.serialization.json.Json
import java.net.URI
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Trust roots and policy constants for Shrey's update subsystem.
 *
 * SECURITY POLICY:
 * - Maintainer manifest signing public key (ECDSA P-256 X.509 SPKI Base64) MUST be provisioned
 *   explicitly by the Shrey maintainer before runtime engine updates are authorized.
 * - While [MAINTAINER_MANIFEST_PUBLIC_KEY_SPKI_BASE64] is blank, runtime yt-dlp activation
 *   is strictly refused; Shrey still checks official GitHub releases and informs the user.
 * - Distribution policy note: Apps distributed on Google Play must NOT dynamically download and
 *   execute updated code outside Play. Runtime engine updates are intended strictly for
 *   maintainer-signed direct distributions (e.g. GitHub Releases) when explicitly opted-in.
 */
object UpdateTrustConfig {
    const val SHREY_REPO_OWNER = "NoWayShub"
    const val SHREY_REPO_NAME = "Shrey"
    const val SHREY_RELEASES_API_URL = "https://api.github.com/repos/NoWayShub/Shrey/releases"
    const val SHREY_RELEASES_WEB_URL = "https://github.com/NoWayShub/Shrey/releases"
    const val YTDLP_LATEST_RELEASE_API_URL = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
    const val YTDLP_RELEASES_WEB_URL = "https://github.com/yt-dlp/yt-dlp/releases"

    const val EXPECTED_PACKAGE_NAME = "com.shrey.downloader"
    const val BUNDLED_PYTHON_VERSION = "3.12.11"
    const val BUNDLED_QUICKJS_VERSION = "2025-04-26"
    const val BUNDLED_FFMPEG_VERSION = "7.1.1"
    const val BUNDLED_EJS_VERSION = "0.8.0"

    /**
     * Leave empty until the maintainer provisions an offline ECDSA P-256 signing keypair and
     * publishes signed manifests. Never auto-generate or trust unpinned keys.
     */
    const val MAINTAINER_MANIFEST_PUBLIC_KEY_SPKI_BASE64: String = ""

    /**
     * Optional pinned production APK signing certificate SHA-256 hex digest (uppercase, no colons).
     * When blank, ApkUpdateVerifier strictly pins against the currently installed Shrey app's own
     * Android signing certificate so a mismatched signer can never overwrite an installation.
     */
    const val PINNED_RELEASE_SIGNING_CERT_SHA256: String = ""

    val ALLOWED_ENGINE_HOSTS: Set<String> = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    val ALLOWED_SHREY_APK_HOSTS: Set<String> = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )
}

sealed interface ManifestVerificationResult {
    data class Verified(val manifest: SignedEngineManifest) : ManifestVerificationResult
    data class Rejected(val reason: String) : ManifestVerificationResult
}

/**
 * Cryptographically verifies maintainer-signed engine update manifests using ECDSA-SHA256 (P-256)
 * and enforces strict schema, host allowlist, anti-downgrade, and runtime compatibility checks.
 */
class UpdateManifestVerifier(
    private val publicKeySpkiBase64: String = UpdateTrustConfig.MAINTAINER_MANIFEST_PUBLIC_KEY_SPKI_BASE64,
    private val installedPythonVersion: String = UpdateTrustConfig.BUNDLED_PYTHON_VERSION,
    private val installedQuickJsVersion: String = UpdateTrustConfig.BUNDLED_QUICKJS_VERSION
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isTrustRootConfigured: Boolean
        get() = publicKeySpkiBase64.isNotBlank()

    fun verify(
        envelope: SignedManifestEnvelope,
        currentInstalledYtDlpVersion: String
    ): ManifestVerificationResult {
        if (publicKeySpkiBase64.isBlank()) {
            return ManifestVerificationResult.Rejected(
                "Maintainer manifest signing key is not configured in this build; runtime engine updates remain disabled."
            )
        }
        if (envelope.manifestJson.isBlank() || envelope.signatureBase64.isBlank()) {
            return ManifestVerificationResult.Rejected("Manifest payload or cryptographic signature is missing.")
        }

        val publicKey = decodeEcPublicKey(publicKeySpkiBase64)
            ?: return ManifestVerificationResult.Rejected("Embedded manifest verification public key is invalid.")

        val signatureBytes = runCatching { Base64.getDecoder().decode(envelope.signatureBase64.trim()) }
            .getOrNull()
            ?: return ManifestVerificationResult.Rejected("Manifest signature is not valid Base64.")

        val signatureValid = runCatching {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(envelope.manifestJson.toByteArray(Charsets.UTF_8))
            sig.verify(signatureBytes)
        }.getOrDefault(false)

        if (!signatureValid) {
            return ManifestVerificationResult.Rejected("Cryptographic signature verification failed.")
        }

        val manifest = runCatching {
            json.decodeFromString<SignedEngineManifest>(envelope.manifestJson)
        }.getOrNull()
            ?: return ManifestVerificationResult.Rejected("Signed manifest JSON is malformed or missing required fields.")

        if (manifest.schemaVersion != 1) {
            return ManifestVerificationResult.Rejected("Unsupported manifest schema version: ${manifest.schemaVersion}.")
        }

        if (!isValidSha256Hex(manifest.sha256)) {
            return ManifestVerificationResult.Rejected("Manifest SHA-256 digest is malformed.")
        }

        if (manifest.sizeBytes <= 0L || manifest.sizeBytes > MAX_ENGINE_ARCHIVE_BYTES) {
            return ManifestVerificationResult.Rejected("Manifest archive size (${manifest.sizeBytes} bytes) is outside safe bounds.")
        }

        if (!isAllowedDownloadUrl(manifest.downloadUrl, UpdateTrustConfig.ALLOWED_ENGINE_HOSTS)) {
            return ManifestVerificationResult.Rejected("Manifest download URL is not on an approved HTTPS release host.")
        }

        // Prevent unexpected/unparseable versions and downgrade attacks
        val versionComparison = UpdateVersionComparator.compareYtDlpVersions(
            candidate = manifest.ytDlpVersion,
            current = currentInstalledYtDlpVersion
        ) ?: return ManifestVerificationResult.Rejected(
            "Manifest yt-dlp version '${manifest.ytDlpVersion}' is invalid or unparseable."
        )
        if (versionComparison <= 0) {
            return ManifestVerificationResult.Rejected(
                "Manifest version ${manifest.ytDlpVersion} is not newer than installed version $currentInstalledYtDlpVersion."
            )
        }

        if (manifest.ejsVersion.isBlank()) {
            return ManifestVerificationResult.Rejected("Manifest is missing required yt-dlp-ejs compatibility version.")
        }

        if (!UpdateVersionComparator.isPythonCompatible(installedPythonVersion, manifest.minPythonVersion)) {
            return ManifestVerificationResult.Rejected(
                "Engine ${manifest.ytDlpVersion} requires Python ${manifest.minPythonVersion}+ (embedded: $installedPythonVersion)."
            )
        }

        if (!UpdateVersionComparator.isQuickJsCompatible(installedQuickJsVersion, manifest.minQuickJsVersion)) {
            return ManifestVerificationResult.Rejected(
                "Engine ${manifest.ytDlpVersion} requires QuickJS ${manifest.minQuickJsVersion}+ (bundled: $installedQuickJsVersion)."
            )
        }

        return ManifestVerificationResult.Verified(manifest)
    }

    private fun decodeEcPublicKey(base64Spki: String): PublicKey? {
        return runCatching {
            val keyBytes = Base64.getDecoder().decode(base64Spki.trim())
            val spec = X509EncodedKeySpec(keyBytes)
            KeyFactory.getInstance("EC").generatePublic(spec)
        }.getOrNull()
    }

    companion object {
        private const val MAX_ENGINE_ARCHIVE_BYTES = 25L * 1024L * 1024L // 25 MiB ceiling for yt-dlp zipimport
        private val sha256Regex = Regex("^[a-fA-F0-9]{64}$")

        fun isValidSha256Hex(value: String): Boolean = sha256Regex.matches(value.trim())

        fun isAllowedDownloadUrl(url: String, allowedHosts: Set<String>): Boolean {
            return runCatching {
                val uri = URI(url.trim())
                val scheme = uri.scheme?.lowercase()
                val host = uri.host?.lowercase()
                scheme == "https" && host != null && allowedHosts.contains(host)
            }.getOrDefault(false)
        }
    }
}

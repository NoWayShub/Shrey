package com.shrey.downloader.core.update

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class UpdateManifestAndVersionTest {

    private val json = Json { encodeDefaults = true }

    private fun generateTestEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun signPayload(payload: String, keyPair: KeyPair): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(payload.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    private fun sampleManifest(
        ytDlpVersion: String = "2026.11.15",
        ejsVersion: String = "0.8.1",
        minPythonVersion: String = "3.11",
        minQuickJsVersion: String = "2025-04-26",
        downloadUrl: String = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.15/yt-dlp",
        sha256: String = "a".repeat(64),
        sizeBytes: Long = 3_450_000L
    ): SignedEngineManifest = SignedEngineManifest(
        schemaVersion = 1,
        ytDlpVersion = ytDlpVersion,
        ejsVersion = ejsVersion,
        minPythonVersion = minPythonVersion,
        minQuickJsVersion = minQuickJsVersion,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        publishedAtIso = "2026-11-15T00:00:00Z",
        supportedFfmpegVersion = "7.1.1"
    )

    @Test
    fun `shrey semantic version comparator orders dev beta and stable correctly`() {
        assertTrue(UpdateVersionComparator.isShreyVersionNewer("0.0.2-beta", "0.0.1-beta"))
        assertTrue(UpdateVersionComparator.isShreyVersionNewer("v0.0.3-dev", "v0.0.2-beta"))
        assertTrue(UpdateVersionComparator.isShreyVersionNewer("0.0.3", "0.0.3-dev"))
        assertTrue(UpdateVersionComparator.isShreyVersionNewer("0.0.3", "0.0.3-beta"))
        assertTrue(UpdateVersionComparator.isShreyVersionNewer("1.0.0", "0.9.9"))
        assertFalse(UpdateVersionComparator.isShreyVersionNewer("0.0.2-beta", "0.0.3-dev"))
        assertFalse(UpdateVersionComparator.isShreyVersionNewer("0.0.3-dev", "0.0.3-dev"))
        assertEquals("0.0.3-dev", UpdateVersionComparator.normalizeVersionLabel("v0.0.3-dev"))
    }

    @Test
    fun `yt-dlp date version and runtime compatibility comparators work deterministically`() {
        assertTrue(UpdateVersionComparator.isYtDlpVersionNewer("2026.10.26", "2025.11.12"))
        assertTrue(UpdateVersionComparator.isYtDlpVersionNewer("2026.11.01", "2026.10.26"))
        assertTrue(UpdateVersionComparator.isYtDlpVersionNewer("2026.10.26.1", "2026.10.26"))
        assertFalse(UpdateVersionComparator.isYtDlpVersionNewer("2026.10.26", "2026.10.26"))
        assertFalse(UpdateVersionComparator.isYtDlpVersionNewer("2025.11.12", "2026.10.26"))

        assertTrue(UpdateVersionComparator.isPythonCompatible("3.12.11", "3.11"))
        assertTrue(UpdateVersionComparator.isPythonCompatible("3.12.11", "3.12.11"))
        assertFalse(UpdateVersionComparator.isPythonCompatible("3.12.11", "3.13"))
        assertFalse(UpdateVersionComparator.isPythonCompatible("3.8.10", "3.11"))

        assertTrue(UpdateVersionComparator.isQuickJsCompatible("2025-04-26", "2025-04-26"))
        assertTrue(UpdateVersionComparator.isQuickJsCompatible("2025-09-01", "2025-04-26"))
        assertFalse(UpdateVersionComparator.isQuickJsCompatible("2025-04-26", "2026-01-01"))
    }

    @Test
    fun `valid ECDSA-SHA256 signed manifest passes verification`() {
        val keyPair = generateTestEcKeyPair()
        val pubSpkiBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val payload = json.encodeToString(sampleManifest())
        val envelope = SignedManifestEnvelope(
            keyId = "maintainer-2026-p256",
            manifestJson = payload,
            signatureBase64 = signPayload(payload, keyPair)
        )

        val verifier = UpdateManifestVerifier(
            publicKeySpkiBase64 = pubSpkiBase64,
            installedPythonVersion = "3.12.11",
            installedQuickJsVersion = "2025-04-26"
        )

        val result = verifier.verify(envelope, currentInstalledYtDlpVersion = "2026.10.26")
        assertTrue("Expected Verified but got $result", result is ManifestVerificationResult.Verified)
        val verified = (result as ManifestVerificationResult.Verified).manifest
        assertEquals("2026.11.15", verified.ytDlpVersion)
        assertEquals("0.8.1", verified.ejsVersion)
    }

    @Test
    fun `tampered payload, bad signature, wrong key, and blank key are all rejected`() {
        val keyPair = generateTestEcKeyPair()
        val otherKeyPair = generateTestEcKeyPair()
        val pubSpkiBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val payload = json.encodeToString(sampleManifest())
        val signature = signPayload(payload, keyPair)

        val verifier = UpdateManifestVerifier(publicKeySpkiBase64 = pubSpkiBase64)

        // Tampered payload
        val tampered = SignedManifestEnvelope("k1", payload.replace("2026.11.15", "2026.12.31"), signature)
        assertTrue(verifier.verify(tampered, "2026.10.26") is ManifestVerificationResult.Rejected)

        // Invalid Base64 signature
        val badB64 = SignedManifestEnvelope("k1", payload, "%%%not-base64%%%")
        assertTrue(verifier.verify(badB64, "2026.10.26") is ManifestVerificationResult.Rejected)

        // Signed with wrong private key
        val wrongKeySig = SignedManifestEnvelope("k1", payload, signPayload(payload, otherKeyPair))
        assertTrue(verifier.verify(wrongKeySig, "2026.10.26") is ManifestVerificationResult.Rejected)

        // Unconfigured blank trust root (default production posture until maintainer configures key)
        val unconfiguredVerifier = UpdateManifestVerifier(publicKeySpkiBase64 = "")
        val validEnvelope = SignedManifestEnvelope("k1", payload, signature)
        assertFalse(unconfiguredVerifier.isTrustRootConfigured)
        assertTrue(unconfiguredVerifier.verify(validEnvelope, "2026.10.26") is ManifestVerificationResult.Rejected)
    }

    @Test
    fun `manifest verifier rejects downgrade, untrusted url, and incompatible python or quickjs`() {
        val keyPair = generateTestEcKeyPair()
        val pubSpkiBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val verifier = UpdateManifestVerifier(
            publicKeySpkiBase64 = pubSpkiBase64,
            installedPythonVersion = "3.12.11",
            installedQuickJsVersion = "2025-04-26"
        )

        fun verifyManifest(manifest: SignedEngineManifest): ManifestVerificationResult {
            val payload = json.encodeToString(manifest)
            return verifier.verify(
                SignedManifestEnvelope("k1", payload, signPayload(payload, keyPair)),
                currentInstalledYtDlpVersion = "2026.10.26"
            )
        }

        // Downgrade
        assertTrue(verifyManifest(sampleManifest(ytDlpVersion = "2026.10.26")) is ManifestVerificationResult.Rejected)
        assertTrue(verifyManifest(sampleManifest(ytDlpVersion = "2025.11.12")) is ManifestVerificationResult.Rejected)

        // Plain HTTP or untrusted mirror
        assertTrue(
            verifyManifest(sampleManifest(downloadUrl = "http://github.com/yt-dlp/yt-dlp/releases/download/2026.11.15/yt-dlp"))
                is ManifestVerificationResult.Rejected
        )
        assertTrue(
            verifyManifest(sampleManifest(downloadUrl = "https://evil.example.com/yt-dlp"))
                is ManifestVerificationResult.Rejected
        )

        // Incompatible Python (requires 3.13 while embedded is 3.12.11)
        val pyRejected = verifyManifest(sampleManifest(minPythonVersion = "3.13"))
        assertTrue(pyRejected is ManifestVerificationResult.Rejected)
        assertTrue((pyRejected as ManifestVerificationResult.Rejected).reason.contains("Python"))

        // Incompatible QuickJS
        val qjsRejected = verifyManifest(sampleManifest(minQuickJsVersion = "2026-01-01"))
        assertTrue(qjsRejected is ManifestVerificationResult.Rejected)
        assertTrue((qjsRejected as ManifestVerificationResult.Rejected).reason.contains("QuickJS"))
    }

    @Test
    fun `apk update verifier enforces package id, newer versionCode, minSdk, abi, and signing certificate`() {
        val verifier = ApkUpdateVerifier(expectedPackageName = "com.shrey.downloader")
        val trustedCert = "AB".repeat(32)
        val validMetadata = ParsedApkMetadata(
            packageName = "com.shrey.downloader",
            versionName = "0.0.4",
            versionCode = 4L,
            minSdkVersion = 29,
            bundledAbis = setOf("arm64-v8a", "armeabi-v7a"),
            signingCertSha256Digests = setOf(trustedCert)
        )

        val ok = verifier.verifyMetadata(
            metadata = validMetadata,
            installedVersionCode = 3L,
            deviceSdkInt = 35,
            deviceSupportedAbis = setOf("arm64-v8a"),
            trustedSigningCertSha256Digests = setOf(trustedCert),
            apkSha256 = "f".repeat(64)
        )
        assertTrue(ok is ApkVerificationResult.Verified)

        // Wrong package name
        val wrongPkg = verifier.verifyMetadata(
            metadata = validMetadata.copy(packageName = "com.spoofed.downloader"),
            installedVersionCode = 3L,
            deviceSdkInt = 35,
            deviceSupportedAbis = setOf("arm64-v8a"),
            trustedSigningCertSha256Digests = setOf(trustedCert),
            apkSha256 = "f".repeat(64)
        )
        assertTrue(wrongPkg is ApkVerificationResult.Rejected)

        // VersionCode downgrade
        val downgrade = verifier.verifyMetadata(
            metadata = validMetadata.copy(versionCode = 3L),
            installedVersionCode = 3L,
            deviceSdkInt = 35,
            deviceSupportedAbis = setOf("arm64-v8a"),
            trustedSigningCertSha256Digests = setOf(trustedCert),
            apkSha256 = "f".repeat(64)
        )
        assertTrue(downgrade is ApkVerificationResult.Rejected)

        // Unsupported minSdk
        val highMinSdk = verifier.verifyMetadata(
            metadata = validMetadata.copy(minSdkVersion = 36),
            installedVersionCode = 3L,
            deviceSdkInt = 35,
            deviceSupportedAbis = setOf("arm64-v8a"),
            trustedSigningCertSha256Digests = setOf(trustedCert),
            apkSha256 = "f".repeat(64)
        )
        assertTrue(highMinSdk is ApkVerificationResult.Rejected)

        // Incompatible ABI
        val wrongAbi = verifier.verifyMetadata(
            metadata = validMetadata.copy(bundledAbis = setOf("x86_64")),
            installedVersionCode = 3L,
            deviceSdkInt = 35,
            deviceSupportedAbis = setOf("arm64-v8a"),
            trustedSigningCertSha256Digests = setOf(trustedCert),
            apkSha256 = "f".repeat(64)
        )
        assertTrue(wrongAbi is ApkVerificationResult.Rejected)

        // Mismatched signing certificate must NEVER be silently trusted
        val wrongSigner = verifier.verifyMetadata(
            metadata = validMetadata.copy(signingCertSha256Digests = setOf("CD".repeat(32))),
            installedVersionCode = 3L,
            deviceSdkInt = 35,
            deviceSupportedAbis = setOf("arm64-v8a"),
            trustedSigningCertSha256Digests = setOf(trustedCert),
            apkSha256 = "f".repeat(64)
        )
        assertTrue(wrongSigner is ApkVerificationResult.Rejected)

        // Missing trusted signer set must reject safely
        val noTrustedSigner = verifier.verifyMetadata(
            metadata = validMetadata,
            installedVersionCode = 3L,
            deviceSdkInt = 35,
            deviceSupportedAbis = setOf("arm64-v8a"),
            trustedSigningCertSha256Digests = emptySet(),
            apkSha256 = "f".repeat(64)
        )
        assertTrue(noTrustedSigner is ApkVerificationResult.Rejected)
    }
}

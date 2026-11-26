package com.shrey.downloader.core.update

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class ParsedApkMetadata(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdkVersion: Int,
    val bundledAbis: Set<String>,
    val signingCertSha256Digests: Set<String>
)

sealed interface ApkVerificationResult {
    data class Verified(
        val metadata: ParsedApkMetadata,
        val apkSha256: String
    ) : ApkVerificationResult

    data class Rejected(
        val reason: String
    ) : ApkVerificationResult
}

/**
 * Verifies downloaded Shrey APK files before presenting them for user-confirmed installation.
 *
 * Security invariants:
 * - Never silently trusts a new or mismatched signing certificate.
 * - Rejects wrong package names, downgrades, unsupported minSdk, incompatible ABIs, and corrupt ZIPs.
 * - Does not request REQUEST_INSTALL_PACKAGES or perform silent installation.
 */
class ApkUpdateVerifier(
    private val expectedPackageName: String = UpdateTrustConfig.EXPECTED_PACKAGE_NAME,
    private val pinnedSigningCertSha256: String = UpdateTrustConfig.PINNED_RELEASE_SIGNING_CERT_SHA256
) {

    fun verifyMetadata(
        metadata: ParsedApkMetadata,
        installedVersionCode: Long,
        deviceSdkInt: Int,
        deviceSupportedAbis: Set<String>,
        trustedSigningCertSha256Digests: Set<String>,
        apkSha256: String
    ): ApkVerificationResult {
        if (metadata.packageName != expectedPackageName) {
            return ApkVerificationResult.Rejected(
                "APK package name '${metadata.packageName}' does not match '$expectedPackageName'."
            )
        }

        if (metadata.versionCode <= installedVersionCode) {
            return ApkVerificationResult.Rejected(
                "APK versionCode (${metadata.versionCode}) is not newer than installed versionCode ($installedVersionCode)."
            )
        }

        if (metadata.minSdkVersion > deviceSdkInt) {
            return ApkVerificationResult.Rejected(
                "APK requires Android API ${metadata.minSdkVersion}, which exceeds this device (API $deviceSdkInt)."
            )
        }

        if (metadata.bundledAbis.isNotEmpty() && metadata.bundledAbis.intersect(deviceSupportedAbis).isEmpty()) {
            return ApkVerificationResult.Rejected(
                "APK ABIs (${metadata.bundledAbis.joinToString()}) do not match this device (${deviceSupportedAbis.joinToString()})."
            )
        }

        val expectedCerts = buildSet {
            if (pinnedSigningCertSha256.isNotBlank()) {
                add(pinnedSigningCertSha256.uppercase().replace(":", "").trim())
            } else {
                addAll(trustedSigningCertSha256Digests.map { it.uppercase().replace(":", "").trim() })
            }
        }

        if (expectedCerts.isEmpty()) {
            return ApkVerificationResult.Rejected(
                "Unable to verify APK signing identity because no trusted signing certificate is available."
            )
        }

        val apkCerts = metadata.signingCertSha256Digests.map { it.uppercase().replace(":", "").trim() }.toSet()
        if (apkCerts.isEmpty() || apkCerts.intersect(expectedCerts).isEmpty()) {
            return ApkVerificationResult.Rejected(
                "APK signing certificate does not match the trusted Shrey signing identity. Installation refused to protect user data."
            )
        }

        return ApkVerificationResult.Verified(metadata = metadata, apkSha256 = apkSha256)
    }

    fun verifyApkFile(
        context: Context,
        apkFile: File,
        installedVersionCode: Long
    ): ApkVerificationResult {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
            return ApkVerificationResult.Rejected("Downloaded APK file is missing or empty.")
        }

        val bundledAbis = inspectApkZipIntegrityAndAbis(apkFile)
            ?: return ApkVerificationResult.Rejected("Downloaded APK failed ZIP integrity verification.")

        val archiveInfo = readPackageArchiveInfo(context.packageManager, apkFile.absolutePath)
            ?: return ApkVerificationResult.Rejected("Android PackageManager could not parse downloaded APK metadata.")

        val appInfo = archiveInfo.applicationInfo
            ?: return ApkVerificationResult.Rejected("Downloaded APK is missing application metadata.")

        val archiveCerts = extractSigningCertSha256Digests(archiveInfo)
        val installedCerts = readInstalledSigningCertSha256Digests(context)

        val metadata = ParsedApkMetadata(
            packageName = archiveInfo.packageName.orEmpty(),
            versionName = archiveInfo.versionName,
            versionCode = archiveInfo.longVersionCode,
            minSdkVersion = appInfo.minSdkVersion,
            bundledAbis = bundledAbis,
            signingCertSha256Digests = archiveCerts
        )

        val sha256 = SafeEngineUpdater.computeSha256(apkFile)
        return verifyMetadata(
            metadata = metadata,
            installedVersionCode = installedVersionCode,
            deviceSdkInt = Build.VERSION.SDK_INT,
            deviceSupportedAbis = Build.SUPPORTED_ABIS.toSet(),
            trustedSigningCertSha256Digests = installedCerts,
            apkSha256 = sha256
        )
    }

    data class ApkQuickVerificationReport(
        val isValid: Boolean,
        val packageMatches: Boolean,
        val signatureMatches: Boolean,
        val sha256Matches: Boolean,
        val reason: String? = null
    )

    fun verifyDownloadedApk(
        context: Context,
        apkFile: File,
        expectedSha256Hex: String? = null,
        installedVersionCode: Long = 0L
    ): ApkQuickVerificationReport {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
            return ApkQuickVerificationReport(
                isValid = false,
                packageMatches = false,
                signatureMatches = false,
                sha256Matches = false,
                reason = "Downloaded APK file is missing or empty."
            )
        }
        val actualSha256 = SafeEngineUpdater.computeSha256(apkFile)
        val shaMatches = expectedSha256Hex.isNullOrBlank() ||
            actualSha256.equals(expectedSha256Hex.trim(), ignoreCase = true)

        return when (val result = verifyApkFile(context, apkFile, installedVersionCode)) {
            is ApkVerificationResult.Verified -> ApkQuickVerificationReport(
                isValid = shaMatches,
                packageMatches = true,
                signatureMatches = true,
                sha256Matches = shaMatches,
                reason = if (shaMatches) null else "APK SHA-256 checksum mismatch."
            )
            is ApkVerificationResult.Rejected -> ApkQuickVerificationReport(
                isValid = false,
                packageMatches = false,
                signatureMatches = false,
                sha256Matches = shaMatches,
                reason = result.reason
            )
        }
    }

    /**
     * Publishes a verified APK into the user's Downloads/Shrey folder via MediaStore (zero extra permissions)
     * so the user can inspect or install it through Android's standard system confirmation flow.
     */
    fun exportVerifiedApkToDownloads(
        context: Context,
        verifiedApkFile: File,
        displayName: String
    ): Result<Uri> = runCatching {
        val sanitizedName = displayName.ifBlank { "Shrey-update.apk" }.let {
            if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, sanitizedName)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Shrey")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create MediaStore Downloads entry for verified APK.")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                verifiedApkFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: error("Could not open MediaStore output stream for verified APK.")

            val doneValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, doneValues, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    fun buildReleasePageIntent(url: String): Intent {
        val safeUrl = if (UpdateManifestVerifier.isAllowedDownloadUrl(url, setOf("github.com"))) {
            url
        } else {
            UpdateTrustConfig.SHREY_RELEASES_WEB_URL
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildOpenVerifiedApkIntent(uriString: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriString), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun inspectApkZipIntegrityAndAbis(apkFile: File): Set<String>? {
        return runCatching {
            ZipFile(apkFile).use { zip ->
                var hasManifest = false
                var hasDex = false
                val abis = mutableSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name == "AndroidManifest.xml") hasManifest = true
                    if (name.endsWith(".dex") && !name.contains('/')) hasDex = true
                    if (name.startsWith("lib/") && name.count { it == '/' } >= 2) {
                        val abi = name.removePrefix("lib/").substringBefore('/')
                        if (abi.isNotBlank()) abis.add(abi)
                    }
                }
                if (!hasManifest || !hasDex) null else abis
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun readPackageArchiveInfo(pm: PackageManager, path: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                path,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    @Suppress("DEPRECATION")
    private fun readInstalledSigningCertSha256Digests(context: Context): Set<String> {
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            extractSigningCertSha256Digests(info)
        }.getOrDefault(emptySet())
    }

    private fun extractSigningCertSha256Digests(info: PackageInfo): Set<String> {
        val signingInfo = info.signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
        }.toSet()
    }
}

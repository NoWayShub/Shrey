package com.shrey.downloader.core.engine

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class YtDlpArchiveInfo(
    val ytDlpVersion: String?,
    val ejsVersion: String?,
    val hasEjsSolverScripts: Boolean = false
)

/**
 * Inspects a yt-dlp zipimport archive on disk (the format used by both upstream yt-dlp releases
 * and youtubedl-android's R.raw.ytdlp) without spawning a Python process.
 */
object YtDlpArchiveInspector {

    private val ytDlpVersionRegex = Regex("""__version__\s*=\s*['"]([^'"]+)['"]""")
    private val ejsVersionRegex = Regex("""__version__\s*=\s*(?:version\s*=\s*)?['"]([^'"]+)['"]""")

    fun inspect(archiveFile: File): YtDlpArchiveInfo {
        if (!archiveFile.exists() || !archiveFile.isFile || archiveFile.length() <= 0L) {
            return YtDlpArchiveInfo(ytDlpVersion = null, ejsVersion = null, hasEjsSolverScripts = false)
        }
        return runCatching {
            ZipFile(archiveFile).use { zip ->
                val ytDlpVersion = zip.getEntry("yt_dlp/version.py")?.let { entry ->
                    val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    ytDlpVersionRegex.find(text)?.groupValues?.getOrNull(1)?.trim()
                }
                val ejsVersion = zip.getEntry("yt_dlp_ejs/_version.py")?.let { entry ->
                    val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    ejsVersionRegex.find(text)?.groupValues?.getOrNull(1)?.trim()
                }
                val coreEntry = zip.getEntry("yt_dlp_ejs/yt/solver/core.min.js")
                val libEntry = zip.getEntry("yt_dlp_ejs/yt/solver/lib.min.js")
                val hasSolvers = coreEntry != null && coreEntry.size > 0L &&
                    libEntry != null && libEntry.size > 0L
                YtDlpArchiveInfo(
                    ytDlpVersion = ytDlpVersion,
                    ejsVersion = ejsVersion,
                    hasEjsSolverScripts = hasSolvers
                )
            }
        }.getOrDefault(YtDlpArchiveInfo(ytDlpVersion = null, ejsVersion = null, hasEjsSolverScripts = false))
    }

    /**
     * Compares date-based yt-dlp version strings (e.g., "2025.11.12" vs "2026.10.26" or
     * "2026.11.20.232810"). Returns true if [current] is missing, unparseable, or older than [minimum].
     */
    fun isVersionOlderThan(current: String?, minimum: String): Boolean {
        val currentParts = parseVersionParts(current, minComponents = 3) ?: return true
        val minParts = parseVersionParts(minimum, minComponents = 3) ?: return false

        val maxLen = maxOf(currentParts.size, minParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val m = minParts.getOrElse(i) { 0 }
            if (c != m) return c < m
        }
        return false
    }

    /**
     * Compares dot-separated semver strings (e.g. EJS "0.3.1" vs "0.8.0").
     * Returns true if [current] is missing, unparseable, or older than [minimum].
     */
    fun isSemverOlderThan(current: String?, minimum: String): Boolean {
        val currentParts = parseVersionParts(current, minComponents = 2) ?: return true
        val minParts = parseVersionParts(minimum, minComponents = 2) ?: return false

        val maxLen = maxOf(currentParts.size, minParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val m = minParts.getOrElse(i) { 0 }
            if (c != m) return c < m
        }
        return false
    }

    fun computeSha256(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            file.inputStream().use { computeSha256(it) }
        }.getOrNull()
    }

    fun computeSha256(input: InputStream): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun parseVersionParts(version: String?, minComponents: Int): List<Int>? {
        if (version.isNullOrBlank()) return null
        val cleaned = version.trim().substringBefore('-').substringBefore('+')
        val parts = cleaned.split('.').mapNotNull { it.toIntOrNull() }
        return if (parts.size >= minComponents) parts else null
    }
}

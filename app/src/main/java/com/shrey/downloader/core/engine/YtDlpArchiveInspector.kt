package com.shrey.downloader.core.engine

import java.io.File
import java.util.zip.ZipFile

data class YtDlpArchiveInfo(
    val ytDlpVersion: String?,
    val ejsVersion: String?
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
            return YtDlpArchiveInfo(ytDlpVersion = null, ejsVersion = null)
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
                YtDlpArchiveInfo(ytDlpVersion = ytDlpVersion, ejsVersion = ejsVersion)
            }
        }.getOrDefault(YtDlpArchiveInfo(ytDlpVersion = null, ejsVersion = null))
    }

    /**
     * Compares date-based yt-dlp version strings (e.g., "2025.11.12" vs "2026.10.26" or
     * "2026.11.20.232810"). Returns true if [current] is missing, unparseable, or older than [minimum].
     */
    fun isVersionOlderThan(current: String?, minimum: String): Boolean {
        val currentParts = parseVersionParts(current) ?: return true
        val minParts = parseVersionParts(minimum) ?: return false

        val maxLen = maxOf(currentParts.size, minParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val m = minParts.getOrElse(i) { 0 }
            if (c != m) return c < m
        }
        return false
    }

    private fun parseVersionParts(version: String?): List<Int>? {
        if (version.isNullOrBlank()) return null
        val cleaned = version.trim().substringBefore('-').substringBefore('+')
        val parts = cleaned.split('.').mapNotNull { it.toIntOrNull() }
        return if (parts.size >= 3) parts else null
    }
}

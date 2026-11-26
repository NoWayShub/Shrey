package com.shrey.downloader.core.update

/**
 * Pure, deterministic version comparison utilities for:
 * - Shrey SemVer releases (e.g., "0.0.1-beta", "v0.0.2-beta", "0.0.3-dev", "0.0.3", "1.0.0")
 * - yt-dlp date-based releases (e.g., "2025.11.12", "2026.10.26", "2026.11.20.232810")
 * - Python runtime versions (e.g., "3.12.11" >= "3.11")
 * - QuickJS date versions (e.g., "2025-04-26" >= "2025-04-26")
 */
object UpdateVersionComparator {

    private data class ParsedSemVer(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val isPrerelease: Boolean,
        val prereleaseRank: Int,
        val prereleaseNum: Int,
        val buildMetadata: String?
    )

    private val semVerRegex = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([a-zA-Z]+)(?:\.?(\d+))?)?(?:\+([a-zA-Z0-9.-]+))?$""")
    private val dateDashRegex = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val dotPartsRegex = Regex("""\d+""")

    /**
     * Returns >0 if [candidate] is newer than [current], 0 if equal, <0 if older, or null if invalid.
     * Always compares numeric (major, minor, patch) strictly first before prerelease precedence.
     */
    fun compareShreyVersions(candidate: String?, current: String?): Int? {
        val a = parseSemVer(candidate) ?: return null
        val b = parseSemVer(current) ?: return null
        val core = compareNumericCore(a, b)
        if (core != 0) return core
        if (a.prereleaseRank != b.prereleaseRank) return a.prereleaseRank.compareTo(b.prereleaseRank)
        return a.prereleaseNum.compareTo(b.prereleaseNum)
    }

    fun isShreyVersionNewer(candidate: String?, current: String?): Boolean {
        return (compareShreyVersions(candidate, current) ?: -1) > 0
    }

    /**
     * Authoritative check for whether a remote Shrey release should be offered as an app update
     * over the installed build:
     * 1. Compares numeric (major, minor, patch) first — an older numeric version is NEVER an update.
     * 2. If candidate numeric version is higher, requires candidateVersionCode > installedVersionCode
     *    whenever both versionCodes are known.
     * 3. If candidate numeric version is equal:
     *    - When reliable candidateVersionCode metadata is present, requires candidateVersionCode > installedVersionCode
     *      and candidate SemVer >= installed SemVer (same versionCode is NEVER an update).
     *    - When candidateVersionCode metadata is absent, does NOT offer same-version prerelease-to-prerelease
     *      (e.g., 0.0.4-dev vs 0.0.4-beta) or stable-to-stable builds; only allows prerelease -> stable graduation.
     */
    fun isShreyAppUpdateAvailable(
        candidateVersion: String?,
        installedVersion: String?,
        candidateVersionCode: Long? = null,
        installedVersionCode: Long? = null
    ): Boolean {
        val candidate = parseSemVer(candidateVersion) ?: return false
        val installed = parseSemVer(installedVersion) ?: return false

        val effectiveCandidateCode = candidateVersionCode
            ?: candidate.buildMetadata?.toLongOrNull()?.takeIf { it > 0L }
        val effectiveInstalledCode = installedVersionCode?.takeIf { it > 0L }

        val numericCore = compareNumericCore(candidate, installed)
        if (numericCore < 0) return false

        if (numericCore > 0) {
            if (effectiveCandidateCode != null && effectiveInstalledCode != null &&
                effectiveCandidateCode <= effectiveInstalledCode
            ) {
                return false
            }
            return true
        }

        // Same numeric major.minor.patch
        val semVerCmp = compareShreyVersions(candidateVersion, installedVersion) ?: return false
        if (semVerCmp < 0) return false

        if (effectiveCandidateCode != null && effectiveInstalledCode != null) {
            return effectiveCandidateCode > effectiveInstalledCode
        }

        // Without a verified higher versionCode, never offer a same-version prerelease or duplicate stable build.
        return installed.isPrerelease && !candidate.isPrerelease
    }

    fun isPrereleaseVersion(raw: String?): Boolean {
        val parsed = parseSemVer(raw)
        if (parsed != null) return parsed.isPrerelease
        val lower = raw.orEmpty().lowercase()
        return lower.contains("-beta") || lower.contains("-alpha") ||
            lower.contains("-dev") || lower.contains("-rc")
    }

    fun extractNumericBuildMetadata(raw: String?): Long? {
        val parsed = parseSemVer(raw) ?: return null
        return parsed.buildMetadata?.toLongOrNull()?.takeIf { it > 0L }
    }

    fun normalizeVersionLabel(rawTag: String): String {
        return rawTag.trim().removePrefix("v").removePrefix("V").substringBefore('+')
    }

    /**
     * Compares date-based yt-dlp versions (e.g., "2026.10.26" vs "2026.11.05").
     * Returns >0 if [candidate] > [current], 0 if equal, <0 if older, null if unparseable.
     */
    fun compareYtDlpVersions(candidate: String?, current: String?): Int? {
        val a = parseDotNumbers(candidate, minParts = 3) ?: return null
        val b = parseDotNumbers(current, minParts = 3) ?: return null
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    fun isYtDlpVersionNewer(candidate: String?, current: String?): Boolean {
        return (compareYtDlpVersions(candidate, current) ?: -1) > 0
    }

    /**
     * Validates embedded Python version compatibility (e.g., "3.12.11" >= "3.11").
     */
    fun isPythonCompatible(installedPython: String?, minRequiredPython: String?): Boolean {
        if (minRequiredPython.isNullOrBlank()) return true
        val installed = parseDotNumbers(installedPython, minParts = 2) ?: return false
        val required = parseDotNumbers(minRequiredPython, minParts = 2) ?: return false
        val maxLen = maxOf(installed.size, required.size)
        for (i in 0 until maxLen) {
            val iv = installed.getOrElse(i) { 0 }
            val rv = required.getOrElse(i) { 0 }
            if (iv != rv) return iv > rv
        }
        return true
    }

    /**
     * Validates QuickJS date version compatibility (e.g., "2025-04-26" >= "2025-04-26").
     */
    fun isQuickJsCompatible(installedQuickJs: String?, minRequiredQuickJs: String?): Boolean {
        if (minRequiredQuickJs.isNullOrBlank()) return true
        val installed = parseDashDate(installedQuickJs) ?: return false
        val required = parseDashDate(minRequiredQuickJs) ?: return false
        for (i in 0 until 3) {
            if (installed[i] != required[i]) return installed[i] > required[i]
        }
        return true
    }

    private fun compareNumericCore(a: ParsedSemVer, b: ParsedSemVer): Int {
        if (a.major != b.major) return a.major.compareTo(b.major)
        if (a.minor != b.minor) return a.minor.compareTo(b.minor)
        return a.patch.compareTo(b.patch)
    }

    private fun parseSemVer(raw: String?): ParsedSemVer? {
        if (raw.isNullOrBlank()) return null
        val match = semVerRegex.matchEntire(raw.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null
        val preLabel = match.groupValues[4].lowercase()
        val preNum = match.groupValues[5].toIntOrNull() ?: 0
        val buildMeta = match.groupValues[6].takeIf { it.isNotBlank() }
        val isPre = preLabel.isNotEmpty()
        val rank = when (preLabel) {
            "" -> 100 // Stable release ranks highest
            "rc" -> 40
            "beta" -> 30
            "alpha" -> 20
            "dev" -> 10
            else -> 5
        }
        return ParsedSemVer(
            major = major,
            minor = minor,
            patch = patch,
            isPrerelease = isPre,
            prereleaseRank = rank,
            prereleaseNum = preNum,
            buildMetadata = buildMeta
        )
    }

    private fun parseDotNumbers(raw: String?, minParts: Int): List<Int>? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().removePrefix("Python").removePrefix("python").trim()
        val core = cleaned.substringBefore('-').substringBefore('+').substringBefore(' ')
        if (!core.all { it.isDigit() || it == '.' }) return null
        val parts = dotPartsRegex.findAll(core).mapNotNull { it.value.toIntOrNull() }.toList()
        return if (parts.size >= minParts) parts else null
    }

    private fun parseDashDate(raw: String?): List<Int>? {
        if (raw.isNullOrBlank()) return null
        val match = dateDashRegex.matchEntire(raw.trim()) ?: return null
        return listOf(
            match.groupValues[1].toIntOrNull() ?: return null,
            match.groupValues[2].toIntOrNull() ?: return null,
            match.groupValues[3].toIntOrNull() ?: return null
        )
    }
}

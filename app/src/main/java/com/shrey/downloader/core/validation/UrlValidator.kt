package com.shrey.downloader.core.validation

import com.shrey.downloader.domain.model.ShreyError
import java.net.InetAddress
import java.net.URI

sealed interface UrlValidationResult {
    data class Valid(val normalizedUrl: String, val host: String) : UrlValidationResult
    data class Invalid(val error: ShreyError) : UrlValidationResult
}

object UrlValidator {

    private val urlInSharedTextRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val ipv4Regex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun extractFirstUrlFromSharedText(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        val trimmed = sharedText.trim()
        val match = urlInSharedTextRegex.find(trimmed)
        return match?.value?.trimEnd('.', ',', ';', ')', ']', '!')
    }

    fun normalizeAndValidate(rawInput: String): UrlValidationResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return UrlValidationResult.Invalid(ShreyError.InvalidUrl("Empty URL input"))
        }

        // Prevent CLI option injection or control characters
        if (trimmed.startsWith("-") || trimmed.any { it.code < 0x20 || it.code == 0x7F }) {
            return UrlValidationResult.Invalid(
                ShreyError.InvalidUrl("Input contains disallowed control characters or leading flag prefix")
            )
        }

        val candidate = extractFirstUrlFromSharedText(trimmed) ?: trimmed

        val uri = try {
            URI(candidate)
        } catch (e: Exception) {
            return UrlValidationResult.Invalid(ShreyError.InvalidUrl("Malformed URI syntax"))
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlValidationResult.Invalid(
                ShreyError.InvalidUrl("Disallowed URI scheme: ${scheme ?: "missing"}")
            )
        }

        // Reject embedded userinfo credentials (e.g. https://user:pass@host)
        if (!uri.rawUserInfo.isNullOrEmpty()) {
            return UrlValidationResult.Invalid(
                ShreyError.InvalidUrl("URLs containing embedded credentials are not allowed")
            )
        }

        val host = uri.host?.lowercase()?.trimEnd('.')
        if (host.isNullOrBlank() || !host.contains('.')) {
            return UrlValidationResult.Invalid(
                ShreyError.InvalidUrl("URL must specify a valid public domain host")
            )
        }

        if (isPrivateOrLoopbackHost(host)) {
            return UrlValidationResult.Invalid(
                ShreyError.InvalidUrl("Local, loopback, and private network hosts are blocked for security")
            )
        }

        // Rebuild canonical URL cleanly without whitespace
        val normalized = uri.toASCIIString()
        if (normalized.length > 2048) {
            return UrlValidationResult.Invalid(
                ShreyError.InvalidUrl("URL exceeds maximum safe length")
            )
        }

        return UrlValidationResult.Valid(normalizedUrl = normalized, host = host)
    }

    internal fun isPrivateOrLoopbackHost(host: String): Boolean {
        val cleanHost = host.removePrefix("[").removeSuffix("]").lowercase()
        if (cleanHost == "localhost" ||
            cleanHost.endsWith(".localhost") ||
            cleanHost.endsWith(".local") ||
            cleanHost.endsWith(".internal") ||
            cleanHost == "0.0.0.0" ||
            cleanHost == "::1" ||
            cleanHost == "::"
        ) {
            return true
        }

        // Check IPv4 numeric literal ranges without triggering DNS lookups on main thread
        val ipv4Match = ipv4Regex.matchEntire(cleanHost)
        if (ipv4Match != null) {
            val octets = ipv4Match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return true
            val (a, b, _, _) = octets
            return a == 0 ||
                a == 10 ||
                a == 127 ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 100 && b in 64..127)
        }

        // Check IPv6 literals (contains colon)
        if (cleanHost.contains(':')) {
            return try {
                val addr = InetAddress.getByName(cleanHost)
                addr.isAnyLocalAddress ||
                    addr.isLoopbackAddress ||
                    addr.isLinkLocalAddress ||
                    addr.isSiteLocalAddress ||
                    cleanHost.startsWith("fc") ||
                    cleanHost.startsWith("fd") ||
                    cleanHost.startsWith("fe8") ||
                    cleanHost.startsWith("fe9") ||
                    cleanHost.startsWith("fea") ||
                    cleanHost.startsWith("feb")
            } catch (_: Exception) {
                true
            }
        }

        return false
    }

    /**
     * Strict allowlist for yt-dlp format selector strings so untrusted values
     * cannot smuggle CLI arguments or shell metacharacters.
     */
    fun isSafeFormatSelector(selector: String): Boolean {
        if (selector.isBlank() || selector.length > 160) return false
        return selector.all { ch ->
            ch.isLetterOrDigit() || ch in setOf('+', '/', '-', '_', '.', '[', ']', '=', '<', '>', ',')
        }
    }
}

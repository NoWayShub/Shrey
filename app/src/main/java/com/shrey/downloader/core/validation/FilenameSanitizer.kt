package com.shrey.downloader.core.validation

object FilenameSanitizer {

    private const val MAX_BASE_LENGTH = 110
    private val illegalCharsRegex = Regex("""[<>:"/\\|?*\u0000-\u001F\u007F]""")
    private val multiSpaceRegex = Regex("""\s+""")
    private val reservedNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun sanitizeBaseName(rawTitle: String?, fallback: String = "shrey_media"): String {
        val input = rawTitle.orEmpty()
            .replace("..", " ")
            .replace(illegalCharsRegex, " ")
            .replace(multiSpaceRegex, " ")
            .trim()
            .trim('.')

        if (input.isBlank()) return fallback

        val truncated = if (input.length > MAX_BASE_LENGTH) {
            input.substring(0, MAX_BASE_LENGTH).trim().trimEnd('.')
        } else {
            input
        }

        if (truncated.isBlank()) return fallback

        val upper = truncated.uppercase()
        return if (upper in reservedNames) {
            "${truncated}_media"
        } else {
            truncated
        }
    }

    fun sanitizeExtension(rawExtension: String?, defaultExtension: String = "mp4"): String {
        val cleaned = rawExtension.orEmpty()
            .trim()
            .trimStart('.')
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(10)
        return cleaned.ifBlank { defaultExtension }
    }

    fun buildFilename(rawTitle: String?, rawExtension: String?): String {
        val base = sanitizeBaseName(rawTitle)
        val ext = sanitizeExtension(rawExtension)
        return "$base.$ext"
    }

    fun deduplicateFilename(
        baseFilename: String,
        existingNames: Set<String>
    ): String {
        if (baseFilename !in existingNames) return baseFilename
        val dotIndex = baseFilename.lastIndexOf('.')
        val stem = if (dotIndex > 0) baseFilename.substring(0, dotIndex) else baseFilename
        val ext = if (dotIndex > 0) baseFilename.substring(dotIndex) else ""

        var counter = 1
        while (counter < 10_000) {
            val candidate = "$stem ($counter)$ext"
            if (candidate !in existingNames) return candidate
            counter++
        }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }
}

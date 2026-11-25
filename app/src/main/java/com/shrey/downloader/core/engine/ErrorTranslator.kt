package com.shrey.downloader.core.engine

import com.shrey.downloader.core.utilities.Formatters
import com.shrey.downloader.domain.model.ShreyError
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object ErrorTranslator {

    fun translate(throwable: Throwable): ShreyError {
        if (throwable is ShreyError) return throwable

        val rawMessage = buildString {
            throwable.message?.let { append(it) }
            throwable.cause?.message?.let {
                if (isNotEmpty()) append("\n")
                append(it)
            }
        }
        return translateMessage(rawMessage = rawMessage, throwable = throwable)
    }

    fun translateMessage(rawMessage: String, throwable: Throwable? = null): ShreyError {
        val lower = rawMessage.lowercase()
        val redacted = Formatters.redactTechnicalDiagnostics(rawMessage)

        return when {
            throwable is TimeoutCancellationException ||
                throwable is SocketTimeoutException ||
                lower.contains("timed out") ||
                lower.contains("read timeout") -> {
                ShreyError.ExtractionTimeout(redacted)
            }

            throwable is UnknownHostException ||
                lower.contains("unable to resolve host") ||
                lower.contains("failed to establish a new connection") ||
                lower.contains("network is unreachable") ||
                lower.contains("temporary failure in name resolution") -> {
                ShreyError.NetworkUnavailable(redacted)
            }

            // Explicit media-request HTTP 403 Forbidden (distinct from generic extraction failures)
            lower.contains("http error 403") ||
                lower.contains("403: forbidden") ||
                lower.contains("403 forbidden") ||
                (lower.contains("unable to download video data") && lower.contains("403")) -> {
                ShreyError.MediaRequestForbidden(redacted)
            }

            // Explicit HTTP 429 rate limiting
            lower.contains("http error 429") ||
                lower.contains("429: too many requests") ||
                lower.contains("too many requests") -> {
                ShreyError.RateLimited(redacted)
            }

            lower.contains("no supported javascript runtime") ||
                lower.contains("n challenge solving failed") ||
                lower.contains("js challenge provider") ||
                (lower.contains("yt-dlp-ejs") && !lower.contains("http error 403")) ||
                (lower.contains("ejs") && lower.contains("runtime")) -> {
                ShreyError.MissingRuntimeDependency(redacted)
            }

            lower.contains("drm") ||
                lower.contains("widevine") ||
                lower.contains("fairplay") ||
                lower.contains("playready") ||
                (lower.contains("protected") && lower.contains("encrypted")) -> {
                ShreyError.ContentInaccessible(redacted)
            }

            lower.contains("sign in to confirm") ||
                lower.contains("login required") ||
                lower.contains("authentication") ||
                (lower.contains("cookies") && lower.contains("required")) ||
                lower.contains("private video") ||
                lower.contains("members-only") -> {
                ShreyError.AuthenticationRequired(redacted)
            }

            lower.contains("geo-restricted") ||
                lower.contains("not available in your country") ||
                lower.contains("uploader has not made this video available in your country") -> {
                ShreyError.GeoRestricted(redacted)
            }

            lower.contains("video unavailable") ||
                lower.contains("has been removed") ||
                lower.contains("http error 404") ||
                lower.contains("404: not found") ||
                lower.contains("account associated with this video has been terminated") -> {
                ShreyError.ContentUnavailable(redacted)
            }

            // FFmpeg / post-processing / muxing / codec errors
            lower.contains("ffmpeg") ||
                lower.contains("ffprobe") ||
                lower.contains("postprocessing") ||
                lower.contains("conversion failed") ||
                lower.contains("muxing") ||
                lower.contains("error while opening encoder") -> {
                ShreyError.ConversionFailed(redacted)
            }

            // Storage / disk space / file permission failures
            lower.contains("no space left on device") ||
                lower.contains("enospc") -> {
                ShreyError.InsufficientStorage(redacted)
            }

            lower.contains("permission denied") ||
                lower.contains("eacces") ||
                lower.contains("read-only file system") ||
                lower.contains("erofs") ||
                lower.contains("could not write") ||
                lower.contains("failed to create mediastore") ||
                lower.contains("documentfile") -> {
                ShreyError.StorageWriteFailed(redacted)
            }

            // Extractor compatibility / SABR-only / signature mismatch issues
            lower.contains("forcing sabr streaming") ||
                lower.contains("sabr streaming") ||
                lower.contains("unable to extract") ||
                lower.contains("signature extraction failed") ||
                (lower.contains("older than 90 days") && lower.contains("missing a url")) ||
                (lower.contains("unsupported") && lower.contains("python")) -> {
                ShreyError.ExtractorCompatibilityError(redacted)
            }

            lower.contains("unsupported url") ||
                (lower.contains("no video formats found") && lower.contains("generic")) -> {
                ShreyError.UnsupportedLink(redacted)
            }

            lower.contains("requested format is not available") ||
                lower.contains("no video formats found") -> {
                ShreyError.MissingFormats(redacted)
            }

            lower.contains("is_live") || lower.contains("live event will begin") -> {
                ShreyError.LiveContentUnsupported(redacted)
            }

            throwable is IOException -> {
                ShreyError.DownloadInterrupted(redacted)
            }

            else -> {
                ShreyError.ExtractorFailure(
                    message = "Unable to process this link with the local extractor.",
                    details = redacted
                )
            }
        }
    }
}

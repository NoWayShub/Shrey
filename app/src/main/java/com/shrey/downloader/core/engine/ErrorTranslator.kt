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
        val lower = rawMessage.lowercase()
        val redacted = Formatters.redactTechnicalDiagnostics(rawMessage)

        return when {
            throwable is TimeoutCancellationException || throwable is SocketTimeoutException -> {
                ShreyError.ExtractionTimeout(redacted)
            }

            throwable is UnknownHostException ||
                lower.contains("unable to resolve host") ||
                lower.contains("failed to establish a new connection") ||
                lower.contains("network is unreachable") ||
                lower.contains("temporary failure in name resolution") -> {
                ShreyError.NetworkUnavailable(redacted)
            }

            lower.contains("no supported javascript runtime") ||
                lower.contains("n challenge solving failed") ||
                lower.contains("yt-dlp-ejs") ||
                lower.contains("ejs") && lower.contains("runtime") -> {
                ShreyError.MissingRuntimeDependency(redacted)
            }

            lower.contains("drm") ||
                lower.contains("widevine") ||
                lower.contains("fairplay") ||
                lower.contains("playready") ||
                lower.contains("protected") && lower.contains("encrypted") -> {
                ShreyError.ContentInaccessible(redacted)
            }

            lower.contains("sign in to confirm") ||
                lower.contains("login required") ||
                lower.contains("authentication") ||
                lower.contains("cookies") && lower.contains("required") ||
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
                lower.contains("404") ||
                lower.contains("not found") ||
                lower.contains("account associated with this video has been terminated") -> {
                ShreyError.ContentUnavailable(redacted)
            }

            lower.contains("unsupported url") ||
                lower.contains("no video formats found") && lower.contains("generic") -> {
                ShreyError.UnsupportedLink(redacted)
            }

            lower.contains("requested format is not available") ||
                lower.contains("no video formats found") -> {
                ShreyError.MissingFormats(redacted)
            }

            lower.contains("ffmpeg") ||
                lower.contains("postprocessing") ||
                lower.contains("conversion failed") ||
                lower.contains("muxing") -> {
                ShreyError.ConversionFailed(redacted)
            }

            lower.contains("no space left on device") ||
                lower.contains("enospc") -> {
                ShreyError.InsufficientStorage(redacted)
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

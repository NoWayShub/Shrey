package com.shrey.downloader.core.engine

import com.shrey.downloader.core.validation.UrlValidationResult
import com.shrey.downloader.core.validation.UrlValidator
import com.shrey.downloader.domain.model.MediaInfo
import com.shrey.downloader.domain.model.ShreyError
import com.shrey.downloader.domain.repository.MediaExtractor
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class YtDlpMediaExtractor(
    private val engineCoordinator: EngineCoordinator
) : MediaExtractor {

    private val activeExtractionIds = ConcurrentHashMap.newKeySet<String>()

    override suspend fun extract(url: String): Result<MediaInfo> {
        val validated = when (val result = UrlValidator.normalizeAndValidate(url)) {
            is UrlValidationResult.Invalid -> return Result.failure(result.error)
            is UrlValidationResult.Valid -> result
        }

        val initResult = engineCoordinator.ensureInitialized()
        if (initResult.isFailure) {
            val err = initResult.exceptionOrNull() as? ShreyError
                ?: ShreyError.EngineInitializationFailed(initResult.exceptionOrNull()?.message)
            return Result.failure(err)
        }

        val processId = "extract_${UUID.randomUUID()}"
        activeExtractionIds.add(processId)

        return try {
            withTimeout(EXTRACTION_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val cacheDir = engineCoordinator.getYtDlpCacheDir()
                    val request = YoutubeDLRequest(validated.normalizedUrl).apply {
                        addOption("--dump-single-json")
                        addOption("--no-playlist")
                        addOption("--skip-download")
                        addOption("--no-exec")
                        addOption("--cache-dir", cacheDir.absolutePath)
                        addOption("--socket-timeout", "25")
                    }

                    val response = runInterruptible {
                        YoutubeDL.getInstance().execute(request, processId, null)
                    }

                    val stdout = response.out.orEmpty()
                    val stderr = response.err.orEmpty()

                    val warnings = extractUserWarnings(stderr)
                    val mediaInfo = YtDlpJsonParser.parseMediaInfo(
                        rawJson = stdout,
                        originalUrl = validated.normalizedUrl,
                        warnings = warnings
                    )

                    if (mediaInfo.isLive) {
                        return@withContext Result.failure(
                            ShreyError.LiveContentUnsupported(
                                "Live stream detected for ${mediaInfo.platform}"
                            )
                        )
                    }

                    if (mediaInfo.formats.isEmpty() && mediaInfo.thumbnailUrl.isNullOrBlank()) {
                        return@withContext Result.failure(
                            ShreyError.MissingFormats(
                                "Extractor returned metadata with no downloadable streams"
                            )
                        )
                    }

                    Result.success(mediaInfo)
                }
            }
        } catch (ce: CancellationException) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw ce
        } catch (t: Throwable) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            Result.failure(ErrorTranslator.translate(t))
        } finally {
            activeExtractionIds.remove(processId)
        }
    }

    override suspend fun cancelExtraction(requestId: String) {
        withContext(Dispatchers.IO) {
            if (requestId.isBlank()) {
                activeExtractionIds.forEach { id ->
                    runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
                }
                activeExtractionIds.clear()
            } else {
                runCatching { YoutubeDL.getInstance().destroyProcessById(requestId) }
                activeExtractionIds.remove(requestId)
            }
        }
    }

    companion object {
        private const val EXTRACTION_TIMEOUT_MS = 60_000L

        internal fun extractUserWarnings(stderr: String): List<String> {
            if (stderr.isBlank()) return emptyList()
            val warnings = mutableListOf<String>()
            val lower = stderr.lowercase()

            val missingRuntime = lower.contains("no supported javascript runtime")
            val missingSolverScript = lower.contains("no usable challenge solver") ||
                (lower.contains("remote component") && lower.contains("ejs"))
            val quickJsExecutionError = lower.contains("error running quickjs process")
            val challengeFailed = lower.contains("n challenge solving failed") ||
                lower.contains("signature solving failed")

            when {
                missingRuntime -> {
                    warnings += "Some high-resolution formats may be unavailable because no supported local JavaScript runtime was detected."
                }
                missingSolverScript -> {
                    warnings += "Some high-resolution formats may be unavailable because the local EJS challenge solver scripts could not be loaded."
                }
                quickJsExecutionError -> {
                    warnings += "Some high-resolution formats may be unavailable because the local QuickJS challenge solver encountered an execution error."
                }
                challengeFailed -> {
                    warnings += "Some high-resolution formats may be unavailable because a JavaScript challenge could not be solved locally."
                }
            }

            if (lower.contains("forcing sabr streaming") ||
                (lower.contains("sabr") && lower.contains("missing a url"))
            ) {
                warnings += "Some formats were skipped because the host is enforcing SABR streaming for that client."
            }
            return warnings
        }
    }
}

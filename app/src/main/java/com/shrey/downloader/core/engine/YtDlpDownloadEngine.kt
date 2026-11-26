package com.shrey.downloader.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.core.validation.UrlValidationResult
import com.shrey.downloader.core.validation.UrlValidator
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.DownloadProgress
import com.shrey.downloader.domain.model.DownloadRequest
import com.shrey.downloader.domain.model.DownloadResult
import com.shrey.downloader.domain.model.DownloadStage
import com.shrey.downloader.domain.model.ShreyError
import com.shrey.downloader.domain.model.ThumbnailOutputFormat
import com.shrey.downloader.domain.repository.DownloadEngine
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class YtDlpDownloadEngine(
    private val context: Context,
    private val engineCoordinator: EngineCoordinator,
    private val storagePublisher: MediaStoragePublisher,
    private val httpClient: OkHttpClient = defaultHttpClient()
) : DownloadEngine {

    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val activeHttpCalls = ConcurrentHashMap<String, okhttp3.Call>()

    override suspend fun download(
        request: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        cancelledIds.remove(request.downloadId)

        val workDir = File(context.cacheDir, "shrey_work/${request.downloadId}")
        if (workDir.exists()) {
            workDir.deleteRecursively()
        }
        workDir.mkdirs()

        try {
            onProgress(
                DownloadProgress(
                    downloadId = request.downloadId,
                    stage = DownloadStage.PREPARING,
                    progressPercent = 0f,
                    message = "Preparing local download workspace…"
                )
            )

            if (request.mode == DownloadMode.THUMBNAIL_ONLY) {
                executeThumbnailDownload(request, workDir, onProgress)
            } else {
                executeMediaDownload(request, workDir, onProgress)
            }
        } catch (ce: CancellationException) {
            cancelledIds.add(request.downloadId)
            runCatching { YoutubeDL.getInstance().destroyProcessById(request.downloadId) }
            activeHttpCalls.remove(request.downloadId)?.cancel()
            DownloadResult.Cancelled
        } catch (t: Throwable) {
            if (cancelledIds.contains(request.downloadId)) {
                DownloadResult.Cancelled
            } else {
                DownloadResult.Failure(ErrorTranslator.translate(t))
            }
        } finally {
            activeHttpCalls.remove(request.downloadId)
            runCatching { workDir.deleteRecursively() }
        }
    }

    override suspend fun cancel(downloadId: String) {
        cancelledIds.add(downloadId)
        withContext(Dispatchers.IO) {
            activeHttpCalls.remove(downloadId)?.cancel()
            runCatching { YoutubeDL.getInstance().destroyProcessById(downloadId) }
        }
    }

    private suspend fun executeMediaDownload(
        request: DownloadRequest,
        workDir: File,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult {
        val validatedUrl = when (val check = UrlValidator.normalizeAndValidate(request.sourceUrl)) {
            is UrlValidationResult.Invalid -> return DownloadResult.Failure(check.error)
            is UrlValidationResult.Valid -> check.normalizedUrl
        }

        if (!UrlValidator.isSafeFormatSelector(request.formatSelector)) {
            return DownloadResult.Failure(
                ShreyError.MissingFormats("Unsafe or malformed format selector")
            )
        }

        val initResult = engineCoordinator.ensureInitialized()
        if (initResult.isFailure) {
            val err = initResult.exceptionOrNull() as? ShreyError
                ?: ShreyError.EngineInitializationFailed(initResult.exceptionOrNull()?.message)
            return DownloadResult.Failure(err)
        }
        val diagnostics = initResult.getOrNull()

        if ((request.requiresMerge ||
                (request.mode == DownloadMode.AUDIO_ONLY && request.audioOutputFormat != AudioOutputFormat.ORIGINAL)) &&
            diagnostics?.ffmpegAvailable != true
        ) {
            return DownloadResult.Failure(
                ShreyError.ConversionFailed("FFmpeg is unavailable for merging or transcoding")
            )
        }

        val outputTemplate = File(workDir, "media_output.%(ext)s").absolutePath
        val cacheDir = engineCoordinator.getYtDlpCacheDir()
        val ytRequest = YoutubeDLRequest(validatedUrl).apply {
            addOption("-f", request.formatSelector)
            addOption("--no-playlist")
            addOption("--no-exec")
            addOption("--no-mtime")
            addOption("--cache-dir", cacheDir.absolutePath)
            addOption("--socket-timeout", "30")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
            addOption("-o", outputTemplate)

            when (request.mode) {
                DownloadMode.VIDEO_AUDIO -> {
                    if (request.requiresMerge && request.outputExtension.isNotBlank()) {
                        addOption("--merge-output-format", request.outputExtension)
                    }
                }
                DownloadMode.AUDIO_ONLY -> {
                    val targetAudio = request.audioOutputFormat.ytDlpAudioFormat
                    if (targetAudio != null) {
                        addOption("-x")
                        addOption("--audio-format", targetAudio)
                        addOption("--audio-quality", "0")
                        addOption("--add-metadata")
                    }
                }
                DownloadMode.VIDEO_ONLY -> {
                    // Strictly preserve video-only stream without attaching audio
                }
                DownloadMode.THUMBNAIL_ONLY -> Unit
            }
        }

        var currentStage = DownloadStage.EXTRACTING
        onProgress(
            DownloadProgress(
                downloadId = request.downloadId,
                stage = currentStage,
                progressPercent = 0f,
                message = "Connecting to media stream…"
            )
        )

        val recentDiagnosticLines = ArrayDeque<String>(24)
        try {
            runInterruptible {
                YoutubeDL.getInstance().execute(ytRequest, request.downloadId) { progress, etaInSeconds, line ->
                    if (cancelledIds.contains(request.downloadId)) return@execute
                    if (!line.isNullOrBlank()) {
                        synchronized(recentDiagnosticLines) {
                            if (recentDiagnosticLines.size >= 24) {
                                recentDiagnosticLines.removeFirst()
                            }
                            recentDiagnosticLines.addLast(line.trim())
                        }
                    }
                    val parsed = ProgressParser.parseCallback(
                        downloadId = request.downloadId,
                        progressFloat = progress,
                        etaSeconds = etaInSeconds,
                        line = line,
                        previousStage = currentStage
                    )
                    currentStage = parsed.stage
                    onProgress(parsed)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (cancelledIds.contains(request.downloadId)) {
                return DownloadResult.Cancelled
            }
            val combinedDiagnostics = buildString {
                t.message?.takeIf { it.isNotBlank() }?.let { append(it) }
                val tail = synchronized(recentDiagnosticLines) { recentDiagnosticLines.joinToString("\n") }
                if (tail.isNotBlank() && !this.contains(tail)) {
                    if (isNotEmpty()) append("\n")
                    append(tail)
                }
            }
            return DownloadResult.Failure(
                ErrorTranslator.translateMessage(combinedDiagnostics, t)
            )
        }

        if (cancelledIds.contains(request.downloadId)) {
            return DownloadResult.Cancelled
        }

        val producedFile = findProducedMediaFile(workDir)
            ?: return DownloadResult.Failure(
                ShreyError.StorageWriteFailed("yt-dlp completed without producing a valid output file")
            )

        if (producedFile.length() <= 0L) {
            return DownloadResult.Failure(
                ShreyError.StorageWriteFailed("Produced media file is empty")
            )
        }

        onProgress(
            DownloadProgress(
                downloadId = request.downloadId,
                stage = DownloadStage.SAVING,
                downloadedBytes = producedFile.length(),
                totalBytes = producedFile.length(),
                progressPercent = 98f,
                message = "Saving file to device storage…"
            )
        )

        val actualExtension = producedFile.extension.ifBlank { request.outputExtension }
        val published = try {
            storagePublisher.publishFile(
                sourceFile = producedFile,
                desiredTitle = request.mediaTitle,
                extension = actualExtension,
                mode = request.mode,
                customTreeUriString = request.destinationTreeUri,
                relativeSubfolder = request.relativeSubfolder
            )
        } catch (t: Throwable) {
            return DownloadResult.Failure(translateStorageFailure(t))
        }

        return DownloadResult.Success(
            destinationUri = published.uriString,
            displayName = published.displayName,
            mimeType = published.mimeType,
            fileSizeBytes = published.sizeBytes
        )
    }

    private fun executeThumbnailDownload(
        request: DownloadRequest,
        workDir: File,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult {
        val thumbUrlRaw = request.formatSelector.ifBlank { request.thumbnailUrl.orEmpty() }
        val validatedUrl = when (val check = UrlValidator.normalizeAndValidate(thumbUrlRaw)) {
            is UrlValidationResult.Invalid -> return DownloadResult.Failure(check.error)
            is UrlValidationResult.Valid -> check.normalizedUrl
        }

        onProgress(
            DownloadProgress(
                downloadId = request.downloadId,
                stage = DownloadStage.DOWNLOADING,
                progressPercent = 10f,
                message = "Downloading thumbnail image…"
            )
        )

        val httpRequest = Request.Builder()
            .url(validatedUrl)
            .get()
            .build()

        val call = httpClient.newCall(httpRequest)
        activeHttpCalls[request.downloadId] = call

        val rawFile = File(workDir, "raw_thumbnail")
        var contentTypeExt: String? = null

        call.execute().use { response ->
            if (!response.isSuccessful) {
                val reason = response.message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                throw IOException("Thumbnail HTTP Error ${response.code}$reason")
            }
            // Validate final redirected URL as well to block SSRF redirects
            val finalUrl = response.request.url.toString()
            if (UrlValidator.normalizeAndValidate(finalUrl) is UrlValidationResult.Invalid) {
                throw ShreyError.InvalidUrl("Thumbnail redirect target blocked for security")
            }

            val body = response.body ?: throw IOException("Empty thumbnail response")
            val totalLength = body.contentLength().takeIf { it > 0L }
            if (totalLength != null && totalLength > MAX_THUMBNAIL_BYTES) {
                throw ShreyError.StorageWriteFailed("Thumbnail exceeds 25 MB safety limit")
            }

            contentTypeExt = when (body.contentType()?.subtype?.lowercase()) {
                "jpeg", "jpg" -> "jpg"
                "png" -> "png"
                "webp" -> "webp"
                else -> null
            }

            body.byteStream().use { input ->
                FileOutputStream(rawFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    while (true) {
                        if (cancelledIds.contains(request.downloadId)) {
                            return DownloadResult.Cancelled
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        downloaded += read
                        if (downloaded > MAX_THUMBNAIL_BYTES) {
                            throw ShreyError.StorageWriteFailed("Thumbnail exceeded safety size limit")
                        }
                        output.write(buffer, 0, read)
                        val pct = if (totalLength != null) {
                            ((downloaded.toFloat() / totalLength.toFloat()) * 80f).coerceIn(10f, 85f)
                        } else {
                            50f
                        }
                        onProgress(
                            DownloadProgress(
                                downloadId = request.downloadId,
                                stage = DownloadStage.DOWNLOADING,
                                downloadedBytes = downloaded,
                                totalBytes = totalLength,
                                progressPercent = pct,
                                message = "Downloading thumbnail…"
                            )
                        )
                    }
                    output.flush()
                }
            }
        }

        if (cancelledIds.contains(request.downloadId)) {
            return DownloadResult.Cancelled
        }

        val (finalFile, finalExt) = when (request.thumbnailOutputFormat) {
            ThumbnailOutputFormat.ORIGINAL -> {
                rawFile to (contentTypeExt ?: request.outputExtension.ifBlank { "jpg" })
            }
            ThumbnailOutputFormat.JPG -> {
                onProgress(
                    DownloadProgress(
                        downloadId = request.downloadId,
                        stage = DownloadStage.CONVERTING,
                        progressPercent = 90f,
                        message = "Encoding thumbnail as JPG…"
                    )
                )
                val bitmap = BitmapFactory.decodeFile(rawFile.absolutePath)
                    ?: throw ShreyError.ConversionFailed("Could not decode source thumbnail for JPG conversion")
                val outFile = File(workDir, "converted.jpg")
                try {
                    FileOutputStream(outFile).use { fos ->
                        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                        if (!ok) throw ShreyError.ConversionFailed("JPEG compression failed")
                    }
                } finally {
                    bitmap.recycle()
                }
                outFile to "jpg"
            }
            ThumbnailOutputFormat.PNG -> {
                onProgress(
                    DownloadProgress(
                        downloadId = request.downloadId,
                        stage = DownloadStage.CONVERTING,
                        progressPercent = 90f,
                        message = "Encoding thumbnail as PNG…"
                    )
                )
                val bitmap = BitmapFactory.decodeFile(rawFile.absolutePath)
                    ?: throw ShreyError.ConversionFailed("Could not decode source thumbnail for PNG conversion")
                val outFile = File(workDir, "converted.png")
                try {
                    FileOutputStream(outFile).use { fos ->
                        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                        if (!ok) throw ShreyError.ConversionFailed("PNG compression failed")
                    }
                } finally {
                    bitmap.recycle()
                }
                outFile to "png"
            }
        }

        onProgress(
            DownloadProgress(
                downloadId = request.downloadId,
                stage = DownloadStage.SAVING,
                downloadedBytes = finalFile.length(),
                totalBytes = finalFile.length(),
                progressPercent = 98f,
                message = "Saving thumbnail to device…"
            )
        )

        val published = try {
            storagePublisher.publishFile(
                sourceFile = finalFile,
                desiredTitle = request.mediaTitle,
                extension = finalExt,
                mode = DownloadMode.THUMBNAIL_ONLY,
                customTreeUriString = request.destinationTreeUri
            )
        } catch (t: Throwable) {
            return DownloadResult.Failure(translateStorageFailure(t))
        }

        return DownloadResult.Success(
            destinationUri = published.uriString,
            displayName = published.displayName,
            mimeType = published.mimeType,
            fileSizeBytes = published.sizeBytes
        )
    }

    private fun translateStorageFailure(t: Throwable): ShreyError {
        if (t is ShreyError) return t
        val msg = t.message.orEmpty()
        val lower = msg.lowercase()
        return if (lower.contains("enospc") || lower.contains("no space left")) {
            ShreyError.InsufficientStorage(msg)
        } else {
            ShreyError.StorageWriteFailed(msg.ifBlank { t::class.java.simpleName })
        }
    }

    private fun findProducedMediaFile(workDir: File): File? {
        val ignoredExtensions = setOf("part", "ytdl", "temp", "tmp")
        return workDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.length() > 0L &&
                    file.extension.lowercase() !in ignoredExtensions &&
                    !file.name.endsWith(".part", ignoreCase = true)
            }
            ?.maxByOrNull { it.length() }
    }

    companion object {
        private const val MAX_THUMBNAIL_BYTES = 25L * 1024L * 1024L

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

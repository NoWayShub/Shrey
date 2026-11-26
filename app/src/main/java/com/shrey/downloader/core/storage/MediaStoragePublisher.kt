package com.shrey.downloader.core.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.shrey.downloader.core.utilities.Formatters
import com.shrey.downloader.core.validation.FilenameSanitizer
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.ShreyError
import java.io.File
import java.io.FileInputStream
import java.io.IOException

data class PublishedMedia(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

class MediaStoragePublisher(private val context: Context) {

    fun availableStorageBytes(): Long {
        return try {
            val stat = StatFs(context.cacheDir.absolutePath)
            if (stat.totalBytes <= 0L) 0L else stat.availableBytes
        } catch (_: Throwable) {
            0L
        }
    }

    fun checkAvailableSpace(requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(context.cacheDir.absolutePath)
            if (stat.totalBytes <= 0L) return true
            val bufferBytes = 16L * 1024L * 1024L
            stat.availableBytes > (requiredBytes + bufferBytes)
        } catch (_: Throwable) {
            true
        }
    }

    fun publishFile(
        sourceFile: File,
        desiredTitle: String,
        extension: String,
        mode: DownloadMode,
        customTreeUriString: String?,
        relativeSubfolder: String? = null
    ): PublishedMedia {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            throw ShreyError.StorageWriteFailed("Output file is missing or empty")
        }

        val fileSize = sourceFile.length()
        if (!checkAvailableSpace(fileSize)) {
            throw ShreyError.InsufficientStorage("Not enough free storage for ${Formatters.formatBytes(fileSize)}")
        }

        val safeFilename = FilenameSanitizer.buildFilename(desiredTitle, extension)
        val mimeType = Formatters.mimeTypeForExtension(extension)

        val safeSegments = FilenameSanitizer.sanitizeSubfolderSegments(relativeSubfolder)

        if (!customTreeUriString.isNullOrBlank()) {
            return publishToSafTree(
                sourceFile = sourceFile,
                treeUriString = customTreeUriString,
                safeFilename = safeFilename,
                mimeType = mimeType,
                sizeBytes = fileSize,
                subfolderSegments = safeSegments
            )
        }

        return publishToMediaStore(
            sourceFile = sourceFile,
            safeFilename = safeFilename,
            mimeType = mimeType,
            mode = mode,
            sizeBytes = fileSize,
            subfolderSegments = safeSegments
        )
    }

    private fun publishToMediaStore(
        sourceFile: File,
        safeFilename: String,
        mimeType: String,
        mode: DownloadMode,
        sizeBytes: Long,
        subfolderSegments: List<String>
    ): PublishedMedia {
        val resolver = context.contentResolver
        val (collectionUri, baseRelativePath) = when (mode) {
            DownloadMode.VIDEO_AUDIO, DownloadMode.VIDEO_ONLY -> {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_MOVIES}/Shrey"
            }
            DownloadMode.AUDIO_ONLY -> {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_MUSIC}/Shrey"
            }
            DownloadMode.THUMBNAIL_ONLY -> {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                    "${Environment.DIRECTORY_PICTURES}/Shrey"
            }
        }

        val relativePath = if (subfolderSegments.isEmpty()) {
            baseRelativePath
        } else {
            "$baseRelativePath/${subfolderSegments.joinToString("/")}"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeFilename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(collectionUri, values)
            ?: throw ShreyError.StorageWriteFailed("MediaStore rejected creation of $safeFilename")

        try {
            resolver.openOutputStream(itemUri, "w")?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: throw IOException("Could not open MediaStore output stream")

            val finalizeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(itemUri, finalizeValues, null, null)

            return PublishedMedia(
                uriString = itemUri.toString(),
                displayName = safeFilename,
                mimeType = mimeType,
                sizeBytes = sizeBytes
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            if (t is ShreyError) throw t
            throw ShreyError.StorageWriteFailed("MediaStore write failed: ${t.message}")
        }
    }

    private fun publishToSafTree(
        sourceFile: File,
        treeUriString: String,
        safeFilename: String,
        mimeType: String,
        sizeBytes: Long,
        subfolderSegments: List<String>
    ): PublishedMedia {
        val treeUri = Uri.parse(treeUriString)
        val rootDir = DocumentFile.fromTreeUri(context, treeUri)
        if (rootDir == null || !rootDir.exists() || !rootDir.canWrite()) {
            throw ShreyError.StorageWriteFailed("Custom destination folder permission is unavailable or revoked")
        }

        var targetDir: DocumentFile = rootDir
        for (segment in subfolderSegments) {
            val existingChild = targetDir.findFile(segment)?.takeIf { it.isDirectory }
            targetDir = existingChild ?: targetDir.createDirectory(segment)
                ?: throw ShreyError.StorageWriteFailed("Could not create folder '$segment' in selected destination")
        }

        val existingNames = targetDir.listFiles().mapNotNull { it.name }.toSet()
        val uniqueFilename = FilenameSanitizer.deduplicateFilename(safeFilename, existingNames)

        val targetDoc = targetDir.createFile(mimeType, uniqueFilename)
            ?: throw ShreyError.StorageWriteFailed("Could not create document in selected folder")

        try {
            context.contentResolver.openOutputStream(targetDoc.uri, "w")?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: throw IOException("Could not open SAF output stream")

            return PublishedMedia(
                uriString = targetDoc.uri.toString(),
                displayName = targetDoc.name ?: uniqueFilename,
                mimeType = mimeType,
                sizeBytes = sizeBytes
            )
        } catch (t: Throwable) {
            runCatching { targetDoc.delete() }
            if (t is ShreyError) throw t
            throw ShreyError.StorageWriteFailed("Custom folder write failed: ${t.message}")
        }
    }

    fun isUriAccessible(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    fun deletePublishedUri(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            val doc = DocumentFile.fromSingleUri(context, uri)
            if (doc != null && doc.exists()) {
                doc.delete()
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun buildOpenIntent(uriString: String, mimeType: String?): Intent {
        val uri = Uri.parse(uriString)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildShareIntent(uriString: String, mimeType: String?, title: String): Intent {
        val uri = Uri.parse(uriString)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share $title").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

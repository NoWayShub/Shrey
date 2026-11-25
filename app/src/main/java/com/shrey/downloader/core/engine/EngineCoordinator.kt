package com.shrey.downloader.core.engine

import android.content.Context
import com.shrey.downloader.domain.model.EngineDiagnostics
import com.shrey.downloader.domain.model.EngineState
import com.shrey.downloader.domain.model.ShreyError
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class EngineCoordinator(private val appContext: Context) {

    private val initMutex = Mutex()
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    suspend fun ensureInitialized(): Result<EngineDiagnostics> = initMutex.withLock {
        val current = _state.value
        if (current is EngineState.Ready) {
            return@withLock Result.success(current.diagnostics)
        }

        _state.value = EngineState.Initializing

        return@withLock withContext(Dispatchers.IO) {
            try {
                // Recover cleanly if a previous transactional engine activation was interrupted.
                val activeBinary = getExtractedYtDlpFile(appContext)
                com.shrey.downloader.core.update.SafeEngineUpdater(
                    activeEngineFile = activeBinary,
                    stagingDir = File(appContext.noBackupFilesDir, "shrey-engine-staging"),
                    backupDir = File(appContext.noBackupFilesDir, "shrey-engine-backup"),
                    isDownloadActive = { false },
                    downloadPayload = { _, _ -> },
                    initializationProbe = { true }
                ).recoverInterruptedUpdateIfNeeded()

                // youtubedl-android's init_ytdlp() only extracts R.raw.ytdlp when the destination file
                // does not already exist. Remove stale extracted binaries (e.g. 2025.11.12 from earlier
                // installs) before init() so the SHA-256-verified bundled 2026.10.26 archive is unpacked.
                // Note: Automatic runtime YoutubeDL.updateYoutubeDL() is intentionally NOT enabled here
                // because upstream YoutubeDLUpdater downloads payloads without SHA-256 checksum checks.
                refreshBundledYtDlpIfStale(appContext)

                YoutubeDL.getInstance().init(appContext)
                var ffmpegReady = false
                try {
                    FFmpeg.getInstance().init(appContext)
                    ffmpegReady = true
                } catch (_: Throwable) {
                    ffmpegReady = false
                }

                val extractedBinary = getExtractedYtDlpFile(appContext)
                val archiveInfo = YtDlpArchiveInspector.inspect(extractedBinary)
                val resolvedVersion = archiveInfo.ytDlpVersion
                    ?: runCatching { YoutubeDL.getInstance().version(appContext) }.getOrNull()
                    ?: BUNDLED_YT_DLP_VERSION

                val pythonVersion = detectEmbeddedPythonVersion(appContext)
                val jsRuntime = detectJsRuntime(appContext)
                val displayVersion = buildString {
                    append(resolvedVersion)
                    archiveInfo.ejsVersion?.let { append(" · EJS $it") }
                }

                val diagnostics = EngineDiagnostics(
                    ytDlpVersion = displayVersion,
                    ffmpegAvailable = ffmpegReady,
                    jsRuntimeName = jsRuntime,
                    jsRuntimeNote = buildRuntimeNote(
                        jsRuntime = jsRuntime,
                        pythonVersion = pythonVersion,
                        ejsVersion = archiveInfo.ejsVersion
                    ),
                    pythonVersion = pythonVersion,
                    ejsVersion = archiveInfo.ejsVersion,
                    ffmpegVersion = BUNDLED_FFMPEG_VERSION
                )
                _state.value = EngineState.Ready(diagnostics)
                Result.success(diagnostics)
            } catch (t: Throwable) {
                val err = ShreyError.EngineInitializationFailed(
                    details = t.message ?: t::class.java.simpleName
                )
                _state.value = EngineState.Failed(err)
                Result.failure(err)
            }
        }
    }

    suspend fun retryInitialization(): Result<EngineDiagnostics> {
        initMutex.withLock {
            if (_state.value is EngineState.Failed) {
                _state.value = EngineState.Idle
            }
        }
        return ensureInitialized()
    }

    private fun refreshBundledYtDlpIfStale(context: Context) {
        val extractedFile = getExtractedYtDlpFile(context)
        if (!extractedFile.exists()) return
        val existingInfo = YtDlpArchiveInspector.inspect(extractedFile)
        if (YtDlpArchiveInspector.isVersionOlderThan(existingInfo.ytDlpVersion, BUNDLED_YT_DLP_VERSION)) {
            extractedFile.delete()
        }
    }

    private fun getExtractedYtDlpFile(context: Context): File {
        return File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
    }

    private fun detectEmbeddedPythonVersion(context: Context): String? {
        val libDir = File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib")
        val pyDir = libDir.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("python3.") }
            ?.name
        return when {
            pyDir != null -> "Python ${pyDir.removePrefix("python")}"
            File(context.applicationInfo?.nativeLibraryDir.orEmpty(), "libpython.so").exists() -> "Python 3.12"
            else -> null
        }
    }

    private fun detectJsRuntime(context: Context): String? {
        val nativeLibDir = context.applicationInfo?.nativeLibraryDir?.let { File(it) }
        val candidates = listOf(
            "libqjs.so" to "QuickJS (libqjs.so)",
            "libquickjs.so" to "QuickJS",
            "libdeno.so" to "Deno",
            "libnode.so" to "Node.js"
        )
        if (nativeLibDir != null && nativeLibDir.exists()) {
            for ((filename, name) in candidates) {
                if (File(nativeLibDir, filename).exists()) {
                    return name
                }
            }
        }
        return null
    }

    private fun buildRuntimeNote(
        jsRuntime: String?,
        pythonVersion: String?,
        ejsVersion: String?
    ): String {
        val pyPart = pythonVersion ?: "Embedded Python"
        return if (jsRuntime != null) {
            val ejsPart = ejsVersion?.let { " with yt-dlp-ejs $it" }.orEmpty()
            "Running locally on $pyPart with $jsRuntime$ejsPart wired automatically by youtubedl-android."
        } else {
            "Running locally on $pyPart. No external JS runtime detected in nativeLibraryDir; some sites requiring JS challenge solving may restrict formats."
        }
    }

    companion object {
        const val BUNDLED_YT_DLP_VERSION = "2026.10.26"
        const val BUNDLED_FFMPEG_VERSION = "7.1.1"
    }
}

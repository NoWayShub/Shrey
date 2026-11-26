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
import java.util.concurrent.TimeUnit

class EngineCoordinator(private val appContext: Context) {

    private val initMutex = Mutex()
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    fun getYtDlpCacheDir(): File {
        val dir = File(appContext.cacheDir, YT_DLP_CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun ensureInitialized(): Result<EngineDiagnostics> = initMutex.withLock {
        val current = _state.value
        if (current is EngineState.Ready) {
            return@withLock Result.success(current.diagnostics)
        }

        _state.value = EngineState.Initializing

        return@withLock withContext(Dispatchers.IO) {
            try {
                // Ensure cacheDir (TMPDIR for QuickJS temp scripts) and yt-dlp cache dir exist.
                appContext.cacheDir.mkdirs()
                getYtDlpCacheDir()

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
                // does not already exist. Remove stale or unpatched extracted binaries (including earlier
                // 2026.10.26 builds lacking our Android QuickJS/EJS fixes) before init() so the
                // bundled archive is unpacked cleanly, while preserving any newer verified engine update.
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
                val jsProbe = probeJsRuntime(appContext)
                val displayVersion = buildString {
                    append(resolvedVersion)
                    archiveInfo.ejsVersion?.let { append(" · EJS $it") }
                }

                val diagnostics = EngineDiagnostics(
                    ytDlpVersion = displayVersion,
                    ffmpegAvailable = ffmpegReady,
                    jsRuntimeName = jsProbe.displayName,
                    jsRuntimeNote = buildRuntimeNote(
                        jsProbe = jsProbe,
                        pythonVersion = pythonVersion,
                        ejsVersion = archiveInfo.ejsVersion,
                        hasEjsSolverScripts = archiveInfo.hasEjsSolverScripts
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
        val isOlderYtDlp = YtDlpArchiveInspector.isVersionOlderThan(
            existingInfo.ytDlpVersion,
            BUNDLED_YT_DLP_VERSION
        )
        if (isOlderYtDlp) {
            extractedFile.delete()
            return
        }

        // Preserve genuinely newer verified engine versions (> BUNDLED_YT_DLP_VERSION) unless
        // their EJS solver scripts are missing or corrupt.
        val isSameAsBundledVersion = existingInfo.ytDlpVersion == BUNDLED_YT_DLP_VERSION
        val isEjsIncomplete = !existingInfo.hasEjsSolverScripts ||
            YtDlpArchiveInspector.isSemverOlderThan(existingInfo.ejsVersion, BUNDLED_EJS_VERSION)

        if (isEjsIncomplete) {
            extractedFile.delete()
            return
        }

        if (isSameAsBundledVersion) {
            val bundledHash = runCatching {
                context.resources
                    .openRawResource(com.yausername.youtubedl_android.R.raw.ytdlp)
                    .use { YtDlpArchiveInspector.computeSha256(it) }
            }.getOrNull()
            val extractedHash = YtDlpArchiveInspector.computeSha256(extractedFile)
            if (bundledHash != null && extractedHash != null && !bundledHash.equals(extractedHash, ignoreCase = true)) {
                extractedFile.delete()
            }
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

    private data class JsRuntimeProbeResult(
        val displayName: String?,
        val executableVerified: Boolean,
        val failureReason: String? = null
    )

    private fun probeJsRuntime(context: Context): JsRuntimeProbeResult {
        val nativeLibDir = context.applicationInfo?.nativeLibraryDir?.let { File(it) }
        if (nativeLibDir == null || !nativeLibDir.exists()) {
            return JsRuntimeProbeResult(
                displayName = null,
                executableVerified = false,
                failureReason = "nativeLibraryDir is unavailable"
            )
        }

        val quickJsFile = File(nativeLibDir, "libqjs.so")
            .takeIf { it.exists() }
            ?: File(nativeLibDir, "libquickjs.so").takeIf { it.exists() }

        if (quickJsFile != null) {
            if (!quickJsFile.canExecute()) {
                return JsRuntimeProbeResult(
                    displayName = "QuickJS (${quickJsFile.name} · not executable)",
                    executableVerified = false,
                    failureReason = "${quickJsFile.name} is present but lacks execute permission"
                )
            }
            val probeOutput = runQuickJsVersionProbe(quickJsFile)
            val parsedVersion = probeOutput?.let { parseQuickJsVersion(it) }
            return if (parsedVersion != null) {
                JsRuntimeProbeResult(
                    displayName = "QuickJS $parsedVersion (${quickJsFile.name})",
                    executableVerified = true
                )
            } else {
                JsRuntimeProbeResult(
                    displayName = "QuickJS (${quickJsFile.name})",
                    executableVerified = false,
                    failureReason = "QuickJS version probe did not return an expected banner"
                )
            }
        }

        val fallbackCandidates = listOf(
            "libdeno.so" to "Deno",
            "libnode.so" to "Node.js"
        )
        for ((filename, name) in fallbackCandidates) {
            val file = File(nativeLibDir, filename)
            if (file.exists()) {
                return JsRuntimeProbeResult(
                    displayName = name,
                    executableVerified = file.canExecute()
                )
            }
        }

        return JsRuntimeProbeResult(
            displayName = null,
            executableVerified = false,
            failureReason = "No JS runtime binary found in nativeLibraryDir"
        )
    }

    private fun runQuickJsVersionProbe(binary: File): String? {
        return runCatching {
            val pb = ProcessBuilder(binary.absolutePath, "--help").apply {
                redirectErrorStream(true)
                // Strip LD_LIBRARY_PATH so Android linker64 does not warn or load Python/FFmpeg libs.
                environment().remove("LD_LIBRARY_PATH")
            }
            val process = pb.start()
            val finished = process.waitFor(QUICKJS_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching null
            }
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun buildRuntimeNote(
        jsProbe: JsRuntimeProbeResult,
        pythonVersion: String?,
        ejsVersion: String?,
        hasEjsSolverScripts: Boolean
    ): String {
        val pyPart = pythonVersion ?: "Embedded Python"
        val ejsPart = when {
            ejsVersion != null && hasEjsSolverScripts -> " with yt-dlp-ejs $ejsVersion"
            ejsVersion != null -> " (yt-dlp-ejs $ejsVersion solver scripts incomplete)"
            else -> " (yt-dlp-ejs solver scripts missing)"
        }
        return when {
            jsProbe.displayName != null && jsProbe.executableVerified && hasEjsSolverScripts ->
                "Running locally on $pyPart with verified ${jsProbe.displayName}$ejsPart."
            jsProbe.displayName != null -> {
                val reason = jsProbe.failureReason?.let { " ($it)" }.orEmpty()
                "Running locally on $pyPart with ${jsProbe.displayName}$ejsPart$reason."
            }
            else ->
                "Running locally on $pyPart. No external JS runtime detected in nativeLibraryDir; some sites requiring JS challenge solving may restrict formats."
        }
    }

    companion object {
        const val BUNDLED_YT_DLP_VERSION = "2026.10.26"
        const val BUNDLED_EJS_VERSION = "0.8.0"
        const val BUNDLED_FFMPEG_VERSION = "7.1.1"
        private const val YT_DLP_CACHE_DIR_NAME = "yt-dlp-cache"
        private const val QUICKJS_PROBE_TIMEOUT_SECONDS = 3L
        private val QUICKJS_VERSION_REGEX = Regex("""(?m)^QuickJS(?:-ng)?\s+version\s+(\S+)""")

        internal fun parseQuickJsVersion(helpOutput: String): String? {
            return QUICKJS_VERSION_REGEX.find(helpOutput)?.groupValues?.getOrNull(1)?.trim()
        }
    }
}

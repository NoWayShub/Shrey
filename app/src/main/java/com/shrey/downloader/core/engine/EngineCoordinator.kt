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
                YoutubeDL.getInstance().init(appContext)
                var ffmpegReady = false
                try {
                    FFmpeg.getInstance().init(appContext)
                    ffmpegReady = true
                } catch (_: Throwable) {
                    ffmpegReady = false
                }

                val version = runCatching {
                    YoutubeDL.getInstance().version(appContext)
                }.getOrNull()

                val jsRuntime = detectJsRuntime(appContext)
                val diagnostics = EngineDiagnostics(
                    ytDlpVersion = version ?: "Bundled (youtubedl-android 0.18.1)",
                    ffmpegAvailable = ffmpegReady,
                    jsRuntimeName = jsRuntime,
                    jsRuntimeNote = if (jsRuntime != null) {
                        "Detected local JS runtime ($jsRuntime) for yt-dlp-ejs challenges."
                    } else {
                        "No external JS runtime (Deno/QuickJS) bundled in v0.0.1. Standard extractors work locally; some YouTube high-resolution signatures requiring yt-dlp-ejs may be restricted."
                    }
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

    private fun detectJsRuntime(context: Context): String? {
        val nativeLibDir = context.applicationInfo?.nativeLibraryDir?.let { File(it) }
        val candidates = listOf(
            "libdeno.so" to "Deno",
            "libqjs.so" to "QuickJS",
            "libquickjs.so" to "QuickJS",
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
}

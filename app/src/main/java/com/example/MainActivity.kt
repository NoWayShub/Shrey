package com.example

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.downloader.ShreyApplication
import com.shrey.downloader.core.design.ShreyTheme
import com.shrey.downloader.domain.model.ThemeMode
import com.shrey.downloader.feature.downloads.DownloadsViewModel
import com.shrey.downloader.feature.home.HomeViewModel
import com.shrey.downloader.feature.library.LibraryViewModel
import com.shrey.downloader.feature.settings.SettingsViewModel
import com.shrey.downloader.navigation.ShreyAppRoot

class MainActivity : ComponentActivity() {

    private val appContainer by lazy { (application as ShreyApplication).container }

    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(
            mediaExtractor = appContainer.mediaExtractor,
            engineCoordinator = appContainer.engineCoordinator,
            downloadQueueManager = appContainer.downloadQueueManager,
            settingsDataStore = appContainer.settingsDataStore,
            updateManager = appContainer.updateManager,
            collectionExtractor = appContainer.collectionExtractor,
            batchCoordinator = appContainer.batchCoordinator,
            storagePublisher = appContainer.storagePublisher
        )
    }

    private val downloadsViewModel: DownloadsViewModel by viewModels {
        DownloadsViewModel.Factory(
            queueManager = appContainer.downloadQueueManager,
            storagePublisher = appContainer.storagePublisher,
            batchCoordinator = appContainer.batchCoordinator
        )
    }

    private val libraryViewModel: LibraryViewModel by viewModels {
        LibraryViewModel.Factory(
            queueManager = appContainer.downloadQueueManager,
            storagePublisher = appContainer.storagePublisher
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(
            settingsDataStore = appContainer.settingsDataStore,
            engineCoordinator = appContainer.engineCoordinator,
            updateManager = appContainer.updateManager,
            apkUpdateVerifier = appContainer.apkUpdateVerifier
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleIncomingShareIntent(intent)
        }

        setContent {
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val useDark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            LaunchedEffect(useDark) {
                val barStyle = if (useDark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle
                )
            }

            ShreyTheme(
                themeMode = settings.themeMode,
                glassEffectMode = settings.glassEffectMode,
                hapticsEnabled = settings.hapticsEnabled
            ) {
                ShreyAppRoot(
                    homeViewModel = homeViewModel,
                    downloadsViewModel = downloadsViewModel,
                    libraryViewModel = libraryViewModel,
                    settingsViewModel = settingsViewModel
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                homeViewModel.onSharedTextReceived(sharedText)
            }
        }
    }
}

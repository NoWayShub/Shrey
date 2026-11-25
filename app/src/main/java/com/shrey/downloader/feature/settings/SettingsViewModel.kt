package com.shrey.downloader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shrey.downloader.core.engine.EngineCoordinator
import com.shrey.downloader.core.update.ApkUpdateVerifier
import com.shrey.downloader.core.update.UpdateChannel
import com.shrey.downloader.core.update.UpdateManager
import com.shrey.downloader.core.update.UpdateSnapshot
import com.shrey.downloader.data.local.SettingsDataStore
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DefaultVideoQuality
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.EngineState
import com.shrey.downloader.domain.model.GlassEffectMode
import com.shrey.downloader.domain.model.ShreySettings
import com.shrey.downloader.domain.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsDataStore: SettingsDataStore,
    private val engineCoordinator: EngineCoordinator,
    private val updateManager: UpdateManager,
    val apkUpdateVerifier: ApkUpdateVerifier
) : ViewModel() {

    val settings: StateFlow<ShreySettings> = settingsDataStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShreySettings()
    )

    val engineState: StateFlow<EngineState> = engineCoordinator.state

    val updateSnapshot: StateFlow<UpdateSnapshot> = updateManager.snapshot

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsDataStore.setThemeMode(mode) }
    }

    fun setGlassEffectMode(mode: GlassEffectMode) {
        viewModelScope.launch { settingsDataStore.setGlassEffectMode(mode) }
    }

    fun setDefaultVideoQuality(quality: DefaultVideoQuality) {
        viewModelScope.launch { settingsDataStore.setDefaultVideoQuality(quality) }
    }

    fun setDefaultAudioFormat(format: AudioOutputFormat) {
        viewModelScope.launch { settingsDataStore.setDefaultAudioFormat(format) }
    }

    fun setDefaultDownloadMode(mode: DownloadMode) {
        viewModelScope.launch { settingsDataStore.setDefaultDownloadMode(mode) }
    }

    fun setCustomDestination(uri: String?, label: String?) {
        viewModelScope.launch { settingsDataStore.setCustomDestination(uri, label) }
    }

    fun setAllowMobileData(allow: Boolean) {
        viewModelScope.launch { settingsDataStore.setAllowMobileData(allow) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setHapticsEnabled(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setNotificationsEnabled(enabled) }
    }

    fun retryEngineInit() {
        viewModelScope.launch { engineCoordinator.retryInitialization() }
    }

    fun checkForUpdatesNow() {
        viewModelScope.launch {
            updateManager.checkForUpdates(forceManual = true, notifyIfAvailable = false)
        }
    }

    fun downloadAndVerifyAppUpdate() {
        viewModelScope.launch {
            updateManager.downloadAndVerifyAppUpdate()
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch { updateManager.setAutoCheckEnabled(enabled) }
    }

    fun setAutoInstallEngineUpdates(enabled: Boolean) {
        viewModelScope.launch { updateManager.setAutoInstallEngineUpdates(enabled) }
    }

    fun setNotifyOnUpdates(enabled: Boolean) {
        viewModelScope.launch { updateManager.setNotifyOnUpdates(enabled) }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        viewModelScope.launch { updateManager.setUpdateChannel(channel) }
    }

    class Factory(
        private val settingsDataStore: SettingsDataStore,
        private val engineCoordinator: EngineCoordinator,
        private val updateManager: UpdateManager,
        private val apkUpdateVerifier: ApkUpdateVerifier
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                settingsDataStore = settingsDataStore,
                engineCoordinator = engineCoordinator,
                updateManager = updateManager,
                apkUpdateVerifier = apkUpdateVerifier
            ) as T
        }
    }
}

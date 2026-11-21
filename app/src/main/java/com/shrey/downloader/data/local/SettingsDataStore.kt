package com.shrey.downloader.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DefaultVideoQuality
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.GlassEffectMode
import com.shrey.downloader.domain.model.ShreySettings
import com.shrey.downloader.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.shreyPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shrey_settings"
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val GLASS_MODE = stringPreferencesKey("glass_effect_mode")
        val DEFAULT_VIDEO_QUALITY = stringPreferencesKey("default_video_quality")
        val DEFAULT_AUDIO_FORMAT = stringPreferencesKey("default_audio_format")
        val DEFAULT_DOWNLOAD_MODE = stringPreferencesKey("default_download_mode")
        val CUSTOM_DESTINATION_URI = stringPreferencesKey("custom_destination_uri")
        val CUSTOM_DESTINATION_LABEL = stringPreferencesKey("custom_destination_label")
        val ALLOW_MOBILE_DATA = booleanPreferencesKey("allow_mobile_data")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    }

    val settingsFlow: Flow<ShreySettings> = context.shreyPreferencesDataStore.data.map { prefs ->
        ShreySettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.DARK,
            glassEffectMode = prefs[Keys.GLASS_MODE]?.let { runCatching { GlassEffectMode.valueOf(it) }.getOrNull() }
                ?: GlassEffectMode.FULL,
            defaultVideoQuality = prefs[Keys.DEFAULT_VIDEO_QUALITY]?.let {
                runCatching { DefaultVideoQuality.valueOf(it) }.getOrNull()
            } ?: DefaultVideoQuality.BEST,
            defaultAudioFormat = prefs[Keys.DEFAULT_AUDIO_FORMAT]?.let {
                runCatching { AudioOutputFormat.valueOf(it) }.getOrNull()
            } ?: AudioOutputFormat.ORIGINAL,
            defaultDownloadMode = prefs[Keys.DEFAULT_DOWNLOAD_MODE]?.let {
                runCatching { DownloadMode.valueOf(it) }.getOrNull()
            } ?: DownloadMode.VIDEO_AUDIO,
            customDestinationUri = prefs[Keys.CUSTOM_DESTINATION_URI],
            customDestinationLabel = prefs[Keys.CUSTOM_DESTINATION_LABEL],
            allowMobileData = prefs[Keys.ALLOW_MOBILE_DATA] ?: true,
            hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.shreyPreferencesDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setGlassEffectMode(mode: GlassEffectMode) {
        context.shreyPreferencesDataStore.edit { it[Keys.GLASS_MODE] = mode.name }
    }

    suspend fun setDefaultVideoQuality(quality: DefaultVideoQuality) {
        context.shreyPreferencesDataStore.edit { it[Keys.DEFAULT_VIDEO_QUALITY] = quality.name }
    }

    suspend fun setDefaultAudioFormat(format: AudioOutputFormat) {
        context.shreyPreferencesDataStore.edit { it[Keys.DEFAULT_AUDIO_FORMAT] = format.name }
    }

    suspend fun setDefaultDownloadMode(mode: DownloadMode) {
        context.shreyPreferencesDataStore.edit { it[Keys.DEFAULT_DOWNLOAD_MODE] = mode.name }
    }

    suspend fun setCustomDestination(uri: String?, label: String?) {
        context.shreyPreferencesDataStore.edit { prefs ->
            if (uri.isNullOrBlank()) {
                prefs.remove(Keys.CUSTOM_DESTINATION_URI)
                prefs.remove(Keys.CUSTOM_DESTINATION_LABEL)
            } else {
                prefs[Keys.CUSTOM_DESTINATION_URI] = uri
                prefs[Keys.CUSTOM_DESTINATION_LABEL] = label ?: "Custom folder"
            }
        }
    }

    suspend fun setAllowMobileData(allow: Boolean) {
        context.shreyPreferencesDataStore.edit { it[Keys.ALLOW_MOBILE_DATA] = allow }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.shreyPreferencesDataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.shreyPreferencesDataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }
}

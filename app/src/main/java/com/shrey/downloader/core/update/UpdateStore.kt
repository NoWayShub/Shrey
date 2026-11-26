package com.shrey.downloader.core.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shrey.downloader.core.engine.EngineCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.shreyUpdateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shrey_update_state"
)

data class PersistedUpdateRecord(
    val preferences: UpdatePreferences = UpdatePreferences(),
    val lastCheckedAtMillis: Long? = null,
    val checkState: UpdateCheckState = UpdateCheckState.IDLE,
    val lastErrorMessage: String? = null,
    val installedEngineVersion: String = EngineCoordinator.BUNDLED_YT_DLP_VERSION,
    val lastKnownGoodEngineVersion: String = EngineCoordinator.BUNDLED_YT_DLP_VERSION,
    val availableYtDlpVersion: String? = null,
    val availableYtDlpUrl: String? = null,
    val latestAppVersion: String? = null,
    val latestAppVersionCode: Long? = null,
    val latestAppTag: String? = null,
    val latestAppTitle: String? = null,
    val latestAppPublishedAt: String? = null,
    val latestAppIsPrerelease: Boolean = false,
    val latestAppNotes: String? = null,
    val latestAppReleaseUrl: String? = null,
    val latestAppApkUrl: String? = null,
    val latestAppApkName: String? = null,
    val latestAppApkSize: Long? = null,
    val shreyReleasesEtag: String? = null,
    val lastNotifiedAppVersion: String? = null,
    val lastNotifiedYtDlpVersion: String? = null
)

class UpdateStore(private val context: Context) {

    private object Keys {
        val AUTO_CHECK_ENABLED = booleanPreferencesKey("auto_check_enabled")
        val AUTO_INSTALL_ENGINE = booleanPreferencesKey("auto_install_engine_updates")
        val NOTIFY_ON_UPDATES = booleanPreferencesKey("notify_on_updates")
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")

        val LAST_CHECKED_AT = longPreferencesKey("last_checked_at_millis")
        val LAST_CHECK_STATE = stringPreferencesKey("last_check_state")
        val LAST_ERROR_MESSAGE = stringPreferencesKey("last_error_message")

        val INSTALLED_ENGINE_VERSION = stringPreferencesKey("installed_engine_version")
        val LAST_KNOWN_GOOD_ENGINE_VERSION = stringPreferencesKey("last_known_good_engine_version")
        val AVAILABLE_YTDLP_VERSION = stringPreferencesKey("available_ytdlp_version")
        val AVAILABLE_YTDLP_URL = stringPreferencesKey("available_ytdlp_url")

        val LATEST_APP_VERSION = stringPreferencesKey("latest_app_version")
        val LATEST_APP_VERSION_CODE = longPreferencesKey("latest_app_version_code")
        val LATEST_APP_TAG = stringPreferencesKey("latest_app_tag")
        val LATEST_APP_TITLE = stringPreferencesKey("latest_app_title")
        val LATEST_APP_PUBLISHED_AT = stringPreferencesKey("latest_app_published_at")
        val LATEST_APP_PRERELEASE = booleanPreferencesKey("latest_app_prerelease")
        val LATEST_APP_NOTES = stringPreferencesKey("latest_app_notes")
        val LATEST_APP_RELEASE_URL = stringPreferencesKey("latest_app_release_url")
        val LATEST_APP_APK_URL = stringPreferencesKey("latest_app_apk_url")
        val LATEST_APP_APK_NAME = stringPreferencesKey("latest_app_apk_name")
        val LATEST_APP_APK_SIZE = longPreferencesKey("latest_app_apk_size")
        val SHREY_RELEASES_ETAG = stringPreferencesKey("shrey_releases_etag")

        val LAST_NOTIFIED_APP_VERSION = stringPreferencesKey("last_notified_app_version")
        val LAST_NOTIFIED_YTDLP_VERSION = stringPreferencesKey("last_notified_ytdlp_version")
    }

    val recordFlow: Flow<PersistedUpdateRecord> = context.shreyUpdateDataStore.data.map { prefs ->
        val channel = prefs[Keys.UPDATE_CHANNEL]
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE

        val checkState = prefs[Keys.LAST_CHECK_STATE]
            ?.let { runCatching { UpdateCheckState.valueOf(it) }.getOrNull() }
            ?: UpdateCheckState.IDLE

        PersistedUpdateRecord(
            preferences = UpdatePreferences(
                autoCheckEnabled = prefs[Keys.AUTO_CHECK_ENABLED] ?: true,
                autoInstallEngineUpdates = prefs[Keys.AUTO_INSTALL_ENGINE] ?: false,
                notifyOnUpdates = prefs[Keys.NOTIFY_ON_UPDATES] ?: true,
                channel = channel
            ),
            lastCheckedAtMillis = prefs[Keys.LAST_CHECKED_AT],
            checkState = checkState,
            lastErrorMessage = prefs[Keys.LAST_ERROR_MESSAGE],
            installedEngineVersion = prefs[Keys.INSTALLED_ENGINE_VERSION]
                ?: EngineCoordinator.BUNDLED_YT_DLP_VERSION,
            lastKnownGoodEngineVersion = prefs[Keys.LAST_KNOWN_GOOD_ENGINE_VERSION]
                ?: EngineCoordinator.BUNDLED_YT_DLP_VERSION,
            availableYtDlpVersion = prefs[Keys.AVAILABLE_YTDLP_VERSION],
            availableYtDlpUrl = prefs[Keys.AVAILABLE_YTDLP_URL],
            latestAppVersion = prefs[Keys.LATEST_APP_VERSION],
            latestAppVersionCode = prefs[Keys.LATEST_APP_VERSION_CODE],
            latestAppTag = prefs[Keys.LATEST_APP_TAG],
            latestAppTitle = prefs[Keys.LATEST_APP_TITLE],
            latestAppPublishedAt = prefs[Keys.LATEST_APP_PUBLISHED_AT],
            latestAppIsPrerelease = prefs[Keys.LATEST_APP_PRERELEASE] ?: false,
            latestAppNotes = prefs[Keys.LATEST_APP_NOTES],
            latestAppReleaseUrl = prefs[Keys.LATEST_APP_RELEASE_URL],
            latestAppApkUrl = prefs[Keys.LATEST_APP_APK_URL],
            latestAppApkName = prefs[Keys.LATEST_APP_APK_NAME],
            latestAppApkSize = prefs[Keys.LATEST_APP_APK_SIZE],
            shreyReleasesEtag = prefs[Keys.SHREY_RELEASES_ETAG],
            lastNotifiedAppVersion = prefs[Keys.LAST_NOTIFIED_APP_VERSION],
            lastNotifiedYtDlpVersion = prefs[Keys.LAST_NOTIFIED_YTDLP_VERSION]
        )
    }

    suspend fun setAutoCheckEnabled(enabled: Boolean) {
        context.shreyUpdateDataStore.edit { it[Keys.AUTO_CHECK_ENABLED] = enabled }
    }

    suspend fun setAutoInstallEngineUpdates(enabled: Boolean) {
        context.shreyUpdateDataStore.edit { it[Keys.AUTO_INSTALL_ENGINE] = enabled }
    }

    suspend fun setNotifyOnUpdates(enabled: Boolean) {
        context.shreyUpdateDataStore.edit { it[Keys.NOTIFY_ON_UPDATES] = enabled }
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        context.shreyUpdateDataStore.edit { prefs ->
            prefs[Keys.UPDATE_CHANNEL] = channel.name
            prefs.remove(Keys.SHREY_RELEASES_ETAG)
            prefs.remove(Keys.LATEST_APP_VERSION)
            prefs.remove(Keys.LATEST_APP_VERSION_CODE)
            prefs.remove(Keys.LATEST_APP_TAG)
            prefs.remove(Keys.LATEST_APP_TITLE)
            prefs.remove(Keys.LATEST_APP_PUBLISHED_AT)
            prefs.remove(Keys.LATEST_APP_PRERELEASE)
            prefs.remove(Keys.LATEST_APP_NOTES)
            prefs.remove(Keys.LATEST_APP_RELEASE_URL)
            prefs.remove(Keys.LATEST_APP_APK_URL)
            prefs.remove(Keys.LATEST_APP_APK_NAME)
            prefs.remove(Keys.LATEST_APP_APK_SIZE)
            prefs.remove(Keys.LAST_ERROR_MESSAGE)
            if (prefs[Keys.LAST_CHECK_STATE] == UpdateCheckState.UPDATE_AVAILABLE.name) {
                prefs[Keys.LAST_CHECK_STATE] = UpdateCheckState.IDLE.name
            }
        }
    }

    suspend fun recordEngineVersions(installed: String, lastKnownGood: String) {
        context.shreyUpdateDataStore.edit { prefs ->
            prefs[Keys.INSTALLED_ENGINE_VERSION] = installed
            prefs[Keys.LAST_KNOWN_GOOD_ENGINE_VERSION] = lastKnownGood
        }
    }

    suspend fun saveSuccessfulCheck(
        checkedAtMillis: Long,
        state: UpdateCheckState,
        availableYtDlpVersion: String?,
        availableYtDlpUrl: String?,
        latestAppRelease: GitHubReleaseInfo?,
        apkAsset: GitHubReleaseAsset?,
        shreyReleasesEtag: String? = null,
        preserveCachedAppRelease: Boolean = false
    ) {
        context.shreyUpdateDataStore.edit { prefs ->
            prefs[Keys.LAST_CHECKED_AT] = checkedAtMillis
            prefs[Keys.LAST_CHECK_STATE] = state.name
            prefs.remove(Keys.LAST_ERROR_MESSAGE)

            if (availableYtDlpVersion != null) {
                prefs[Keys.AVAILABLE_YTDLP_VERSION] = availableYtDlpVersion
            } else {
                prefs.remove(Keys.AVAILABLE_YTDLP_VERSION)
            }
            if (availableYtDlpUrl != null) {
                prefs[Keys.AVAILABLE_YTDLP_URL] = availableYtDlpUrl
            } else {
                prefs.remove(Keys.AVAILABLE_YTDLP_URL)
            }
            if (!shreyReleasesEtag.isNullOrBlank()) {
                prefs[Keys.SHREY_RELEASES_ETAG] = shreyReleasesEtag
            } else if (!preserveCachedAppRelease) {
                prefs.remove(Keys.SHREY_RELEASES_ETAG)
            }

            if (!preserveCachedAppRelease) {
                if (latestAppRelease != null) {
                    prefs[Keys.LATEST_APP_VERSION] = UpdateVersionComparator.normalizeVersionLabel(latestAppRelease.tagName)
                    if (latestAppRelease.versionCode != null && latestAppRelease.versionCode > 0L) {
                        prefs[Keys.LATEST_APP_VERSION_CODE] = latestAppRelease.versionCode
                    } else {
                        prefs.remove(Keys.LATEST_APP_VERSION_CODE)
                    }
                    prefs[Keys.LATEST_APP_TAG] = latestAppRelease.tagName
                    prefs[Keys.LATEST_APP_TITLE] = latestAppRelease.name
                    if (!latestAppRelease.publishedAt.isNullOrBlank()) {
                        prefs[Keys.LATEST_APP_PUBLISHED_AT] = latestAppRelease.publishedAt
                    } else {
                        prefs.remove(Keys.LATEST_APP_PUBLISHED_AT)
                    }
                    prefs[Keys.LATEST_APP_PRERELEASE] = latestAppRelease.isPrerelease
                    prefs[Keys.LATEST_APP_NOTES] = latestAppRelease.body
                    prefs[Keys.LATEST_APP_RELEASE_URL] = latestAppRelease.htmlUrl
                } else {
                    prefs.remove(Keys.LATEST_APP_VERSION)
                    prefs.remove(Keys.LATEST_APP_VERSION_CODE)
                    prefs.remove(Keys.LATEST_APP_TAG)
                    prefs.remove(Keys.LATEST_APP_TITLE)
                    prefs.remove(Keys.LATEST_APP_PUBLISHED_AT)
                    prefs.remove(Keys.LATEST_APP_PRERELEASE)
                    prefs.remove(Keys.LATEST_APP_NOTES)
                    prefs.remove(Keys.LATEST_APP_RELEASE_URL)
                }

                if (apkAsset != null) {
                    prefs[Keys.LATEST_APP_APK_URL] = apkAsset.downloadUrl
                    prefs[Keys.LATEST_APP_APK_NAME] = apkAsset.name
                    prefs[Keys.LATEST_APP_APK_SIZE] = apkAsset.sizeBytes
                } else {
                    prefs.remove(Keys.LATEST_APP_APK_URL)
                    prefs.remove(Keys.LATEST_APP_APK_NAME)
                    prefs.remove(Keys.LATEST_APP_APK_SIZE)
                }
            }
        }
    }

    suspend fun saveFailedCheck(errorMessage: String) {
        context.shreyUpdateDataStore.edit { prefs ->
            prefs[Keys.LAST_CHECK_STATE] = UpdateCheckState.FAILED.name
            prefs[Keys.LAST_ERROR_MESSAGE] = errorMessage
        }
    }

    suspend fun markNotified(appVersion: String?, ytDlpVersion: String?) {
        context.shreyUpdateDataStore.edit { prefs ->
            if (!appVersion.isNullOrBlank()) prefs[Keys.LAST_NOTIFIED_APP_VERSION] = appVersion
            if (!ytDlpVersion.isNullOrBlank()) prefs[Keys.LAST_NOTIFIED_YTDLP_VERSION] = ytDlpVersion
        }
    }
}

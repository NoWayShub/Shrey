package com.shrey.downloader.core.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shrey.downloader.core.engine.EngineCoordinator
import com.shrey.downloader.core.notifications.DownloadNotificationManager
import com.shrey.downloader.data.local.SettingsDataStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateCheckerV004Test {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun buildManagerWithOkHttp(
        interceptor: Interceptor,
        installedVersion: String = "0.0.4-dev",
        installedCode: Long = 4L
    ): UpdateManager {
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        val source = OkHttpReleaseMetadataSource(client = client)
        return UpdateManager(
            context = context,
            updateStore = UpdateStore(context),
            settingsDataStore = SettingsDataStore(context),
            metadataSource = source,
            manifestVerifier = UpdateManifestVerifier(publicKeySpkiBase64 = ""),
            apkVerifier = ApkUpdateVerifier(),
            notificationManager = DownloadNotificationManager(context),
            engineCoordinator = EngineCoordinator(context),
            installedAppVersionName = installedVersion,
            installedAppVersionCode = installedCode
        )
    }

    private fun jsonResponse(
        chain: Interceptor.Chain,
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val builder = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else if (code == 304) "Not Modified" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.build()
    }

    @Test
    fun `ignores drafts malformed tags and never flags v0_0_3_beta as upgrade to 0_0_4_dev`() = runBlocking {
        val shreyJson = """
            [
              {
                "tag_name": "v9.9.9",
                "name": "Draft Release",
                "draft": true,
                "prerelease": false,
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v9.9.9",
                "assets": []
              },
              {
                "tag_name": "nightly-not-semver",
                "name": "Malformed Tag",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/nightly",
                "assets": []
              },
              {
                "tag_name": "v0.0.3-beta",
                "name": "Shrey v0.0.3 Beta",
                "body": "Older beta",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-11-18T10:00:00Z",
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.3-beta",
                "assets": []
              }
            ]
        """.trimIndent()
        val ytDlpJson = """
            {
              "tag_name": "2026.10.26",
              "name": "yt-dlp 2026.10.26",
              "body": "",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26",
              "assets": []
            }
        """.trimIndent()

        val manager = buildManagerWithOkHttp(
            installedVersion = "0.0.4-dev",
            interceptor = Interceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("NoWayShub/Shrey")) {
                    jsonResponse(chain, 200, shreyJson, mapOf("ETag" to "\"etag-v1\""))
                } else {
                    jsonResponse(chain, 200, ytDlpJson)
                }
            }
        )

        manager.setUpdateChannel(UpdateChannel.BETA)
        val snap = manager.checkForUpdates(forceManual = true).getOrThrow()
        assertEquals(UpdateCheckState.UP_TO_DATE, snap.checkState)
        assertFalse(snap.app.updateAvailable)
        assertEquals("0.0.3-beta", snap.app.latestVersionName)
    }

    @Test
    fun `detects stable and beta updates from official NoWayShub Shrey releases api with metadata and etag 304`() = runBlocking {
        val capturedIfNoneMatch = AtomicReference<String?>(null)
        var callIndex = 0
        val shreyJson = """
            [
              {
                "tag_name": "v0.0.5-beta.1",
                "name": "Shrey v0.0.5 Beta 1",
                "body": "Preview channel features",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-11-22T12:00:00Z",
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.5-beta.1",
                "assets": [
                  {
                    "name": "Shrey-v0.0.5-beta.1.apk",
                    "browser_download_url": "https://github.com/NoWayShub/Shrey/releases/download/v0.0.5-beta.1/Shrey-v0.0.5-beta.1.apk",
                    "size": 255000000
                  }
                ]
              },
              {
                "tag_name": "v0.0.4",
                "name": "Shrey v0.0.4 Stable",
                "body": "Bulk playlist & channel downloading",
                "draft": false,
                "prerelease": false,
                "published_at": "2026-11-21T18:30:00Z",
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.4",
                "assets": [
                  {
                    "name": "Shrey-v0.0.4.apk",
                    "browser_download_url": "https://github.com/NoWayShub/Shrey/releases/download/v0.0.4/Shrey-v0.0.4.apk",
                    "size": 252000000
                  }
                ]
              }
            ]
        """.trimIndent()
        val ytDlpJson = """{"tag_name": "2026.10.26", "name": "2026.10.26", "html_url": "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26", "assets": []}"""

        val manager = buildManagerWithOkHttp(
            installedVersion = "0.0.4-dev",
            interceptor = Interceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("NoWayShub/Shrey")) {
                    assertEquals(UpdateTrustConfig.SHREY_RELEASES_API_URL, url)
                    capturedIfNoneMatch.set(chain.request().header("If-None-Match"))
                    callIndex++
                    if (callIndex == 1) {
                        jsonResponse(chain, 200, shreyJson, mapOf("ETag" to "\"shrey-etag-42\""))
                    } else {
                        jsonResponse(chain, 304, "", mapOf("ETag" to "\"shrey-etag-42\""))
                    }
                } else {
                    jsonResponse(chain, 200, ytDlpJson)
                }
            }
        )

        manager.setUpdateChannel(UpdateChannel.STABLE)
        val first = manager.checkForUpdates(forceManual = true).getOrThrow()
        assertEquals(UpdateCheckState.UPDATE_AVAILABLE, first.checkState)
        assertTrue(first.app.updateAvailable)
        assertEquals("0.0.4", first.app.latestVersionName)
        assertEquals("Shrey v0.0.4 Stable", first.app.latestReleaseTitle)
        assertEquals("2026-11-21T18:30:00Z", first.app.latestPublishedAt)
        assertEquals(252_000_000L, first.app.apkSizeBytes)

        // Second check sends If-None-Match ETag and handles 304 Not Modified without losing cached metadata
        val second = manager.checkForUpdates(forceManual = true).getOrThrow()
        assertEquals("\"shrey-etag-42\"", capturedIfNoneMatch.get())
        assertEquals(UpdateCheckState.UPDATE_AVAILABLE, second.checkState)
        assertEquals("0.0.4", second.app.latestVersionName)
        assertEquals("Shrey v0.0.4 Stable", second.app.latestReleaseTitle)
    }

    @Test
    fun `handles github http 403 rate limit cleanly and never claims up to date`() = runBlocking {
        val manager = buildManagerWithOkHttp(
            interceptor = Interceptor { chain ->
                jsonResponse(
                    chain,
                    403,
                    """{"message":"API rate limit exceeded"}""",
                    mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1800000000")
                )
            }
        )

        val result = manager.checkForUpdates(forceManual = true)
        assertTrue(result.isFailure)
        assertEquals(UpdateCheckState.FAILED, manager.snapshot.value.checkState)
        assertTrue(manager.snapshot.value.lastError.orEmpty().contains("rate limit", ignoreCase = true))
    }

    @Test
    fun `handles timeout and offline failures cleanly without claiming up to date`() = runBlocking {
        val timeoutManager = buildManagerWithOkHttp(
            interceptor = Interceptor { throw SocketTimeoutException("Read timed out") }
        )
        val timeoutRes = timeoutManager.checkForUpdates(forceManual = true)
        assertTrue(timeoutRes.isFailure)
        assertEquals(UpdateCheckState.FAILED, timeoutManager.snapshot.value.checkState)

        val offlineManager = buildManagerWithOkHttp(
            interceptor = Interceptor { throw UnknownHostException("api.github.com") }
        )
        val offlineRes = offlineManager.checkForUpdates(forceManual = true)
        assertTrue(offlineRes.isFailure)
        assertEquals(UpdateCheckState.FAILED, offlineManager.snapshot.value.checkState)
        assertNotNull(offlineManager.snapshot.value.lastError)
    }

    @Test
    fun `apk verifier rejects wrong package id and mismatched signing certificate`() {
        val verifier = ApkUpdateVerifier()
        val dummyApk = File.createTempFile("shrey_bad_test_", ".apk", context.cacheDir).apply {
            writeText("not-a-valid-apk")
        }
        try {
            val res = verifier.verifyDownloadedApk(
                context = context,
                apkFile = dummyApk,
                expectedSha256Hex = null
            )
            assertFalse(res.isValid)
            assertFalse(res.packageMatches)
            assertFalse(res.signatureMatches)
        } finally {
            dummyApk.delete()
        }
    }

    @Test
    fun `version comparison matrix enforces strict numeric ordering and versionCode rules`() {
        // Installed 0.0.4-dev (code 4) + remote 0.0.2-beta = NO UPDATE
        assertFalse(
            UpdateVersionComparator.isShreyAppUpdateAvailable(
                candidateVersion = "0.0.2-beta",
                installedVersion = "0.0.4-dev",
                candidateVersionCode = 2L,
                installedVersionCode = 4L
            )
        )
        assertFalse(
            UpdateVersionComparator.isShreyAppUpdateAvailable(
                candidateVersion = "v0.0.2-beta",
                installedVersion = "0.0.4-dev",
                candidateVersionCode = 99L, // older numeric SemVer is NEVER an update
                installedVersionCode = 4L
            )
        )

        // Installed 0.0.4-dev (code 4) + remote 0.0.3-beta = NO UPDATE
        assertFalse(
            UpdateVersionComparator.isShreyAppUpdateAvailable(
                candidateVersion = "0.0.3-beta",
                installedVersion = "0.0.4-dev",
                candidateVersionCode = 3L,
                installedVersionCode = 4L
            )
        )

        // Installed 0.0.4-dev (code 4) + remote 0.0.4-beta with equal or absent versionCode = NO UPDATE
        assertFalse(
            UpdateVersionComparator.isShreyAppUpdateAvailable(
                candidateVersion = "0.0.4-beta",
                installedVersion = "0.0.4-dev",
                candidateVersionCode = 4L,
                installedVersionCode = 4L
            )
        )
        assertFalse(
            UpdateVersionComparator.isShreyAppUpdateAvailable(
                candidateVersion = "0.0.4-beta",
                installedVersion = "0.0.4-dev",
                candidateVersionCode = null,
                installedVersionCode = 4L
            )
        )

        // Installed 0.0.4-dev (code 4) + remote 0.0.4-beta with verified higher versionCode = UPDATE AVAILABLE
        assertTrue(
            UpdateVersionComparator.isShreyAppUpdateAvailable(
                candidateVersion = "0.0.4-beta",
                installedVersion = "0.0.4-dev",
                candidateVersionCode = 5L,
                installedVersionCode = 4L
            )
        )

        // Installed 0.0.4-dev (code 4) + remote 0.0.5-beta = UPDATE AVAILABLE
        assertTrue(
            UpdateVersionComparator.isShreyAppUpdateAvailable(
                candidateVersion = "0.0.5-beta",
                installedVersion = "0.0.4-dev",
                candidateVersionCode = 5L,
                installedVersionCode = 4L
            )
        )

        // Installed 0.0.3 (code 3) + remote 0.0.4-beta = UPDATE AVAILABLE
        assertTrue(
            UpdateVersionComparator.isShreyAppUpdateAvailable(
                candidateVersion = "0.0.4-beta",
                installedVersion = "0.0.3",
                candidateVersionCode = 4L,
                installedVersionCode = 3L
            )
        )
    }

    @Test
    fun `installed 0_0_4_dev never flags remote 0_0_2_beta or equal versionCode 0_0_4_beta as app update`() = runBlocking {
        val shreyJson = """
            [
              {
                "tag_name": "v0.0.2-beta",
                "name": "Shrey v0.0.2 Beta",
                "body": "Published later on GitHub but strictly older SemVer",
                "draft": false,
                "prerelease": true,
                "published_at": "2027-01-01T00:00:00Z",
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.2-beta",
                "assets": [
                  {
                    "name": "Shrey-v0.0.2-beta.apk",
                    "browser_download_url": "https://github.com/NoWayShub/Shrey/releases/download/v0.0.2-beta/Shrey-v0.0.2-beta.apk",
                    "size": 240000000
                  }
                ]
              },
              {
                "tag_name": "v0.0.4-beta",
                "name": "Shrey v0.0.4 Beta",
                "body": "versionCode: 4",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-11-20T00:00:00Z",
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.4-beta",
                "assets": [
                  {
                    "name": "Shrey-v0.0.4-beta.apk",
                    "browser_download_url": "https://github.com/NoWayShub/Shrey/releases/download/v0.0.4-beta/Shrey-v0.0.4-beta.apk",
                    "size": 245000000
                  }
                ]
              }
            ]
        """.trimIndent()
        val ytDlpSameJson = """{"tag_name": "2026.10.26", "name": "2026.10.26", "html_url": "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26", "assets": []}"""

        val manager = buildManagerWithOkHttp(
            installedVersion = "0.0.4-dev",
            installedCode = 4L,
            interceptor = Interceptor { chain ->
                if (chain.request().url.toString().contains("NoWayShub/Shrey")) {
                    jsonResponse(chain, 200, shreyJson)
                } else {
                    jsonResponse(chain, 200, ytDlpSameJson)
                }
            }
        )

        manager.setUpdateChannel(UpdateChannel.BETA)
        val snap = manager.checkForUpdates(forceManual = true).getOrThrow()
        assertEquals(UpdateCheckState.UP_TO_DATE, snap.checkState)
        assertFalse(snap.app.updateAvailable)
        assertEquals("0.0.4-beta", snap.app.latestVersionName)
        assertEquals(4L, snap.app.latestVersionCode)
        assertEquals("Up to date", snap.statusBadgeLabel)
    }

    @Test
    fun `separates yt-dlp engine update from Shrey app update and never claims Shrey app update when remote is 0_0_2_beta`() = runBlocking {
        val shreyJson = """
            [
              {
                "tag_name": "v0.0.2-beta",
                "name": "Shrey v0.0.2 Beta",
                "body": "",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-11-15T00:00:00Z",
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.2-beta",
                "assets": [
                  {
                    "name": "Shrey-v0.0.2-beta.apk",
                    "browser_download_url": "https://github.com/NoWayShub/Shrey/releases/download/v0.0.2-beta/Shrey-v0.0.2-beta.apk",
                    "size": 240000000
                  }
                ]
              }
            ]
        """.trimIndent()
        val ytDlpNewerJson = """{"tag_name": "2026.11.20", "name": "2026.11.20", "html_url": "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.11.20", "assets": []}"""

        val manager = buildManagerWithOkHttp(
            installedVersion = "0.0.4-dev",
            installedCode = 4L,
            interceptor = Interceptor { chain ->
                if (chain.request().url.toString().contains("NoWayShub/Shrey")) {
                    jsonResponse(chain, 200, shreyJson)
                } else {
                    jsonResponse(chain, 200, ytDlpNewerJson)
                }
            }
        )

        manager.setUpdateChannel(UpdateChannel.BETA)
        val snap = manager.checkForUpdates(forceManual = true).getOrThrow()
        assertFalse(snap.app.updateAvailable)
        assertTrue(snap.engine.updateAvailable)
        assertEquals(UpdateCheckState.UPDATE_AVAILABLE, snap.checkState)
        assertEquals("yt-dlp update available", snap.statusBadgeLabel)
    }

    @Test
    fun `detects compatible 0_0_5_beta on beta channel, excludes incompatible minSdk or abi, and supports 0_0_3 to 0_0_4_beta`() = runBlocking {
        val shreyJson = """
            [
              {
                "tag_name": "v0.0.6-beta",
                "name": "Incompatible minSdk release",
                "body": "minSdk: 99\nversionCode: 6",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.6-beta",
                "assets": [
                  {
                    "name": "Shrey-v0.0.6-beta.apk",
                    "browser_download_url": "https://github.com/NoWayShub/Shrey/releases/download/v0.0.6-beta/Shrey-v0.0.6-beta.apk",
                    "size": 260000000
                  }
                ]
              },
              {
                "tag_name": "v0.0.5-beta",
                "name": "Shrey v0.0.5 Beta",
                "body": "versionCode: 5",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.5-beta",
                "assets": [
                  {
                    "name": "Shrey-v0.0.5-beta-arm64-v8a.apk",
                    "browser_download_url": "https://github.com/NoWayShub/Shrey/releases/download/v0.0.5-beta/Shrey-v0.0.5-beta-arm64-v8a.apk",
                    "size": 255000000
                  }
                ]
              }
            ]
        """.trimIndent()
        val ytDlpSameJson = """{"tag_name": "2026.10.26", "name": "2026.10.26", "html_url": "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26", "assets": []}"""

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (chain.request().url.toString().contains("NoWayShub/Shrey")) {
                    jsonResponse(chain, 200, shreyJson)
                } else {
                    jsonResponse(chain, 200, ytDlpSameJson)
                }
            }
            .build()
        val source = OkHttpReleaseMetadataSource(client = client)

        // arm64-v8a device on 0.0.4-dev picks compatible v0.0.5-beta (skipping minSdk 99 v0.0.6-beta)
        val armManager = UpdateManager(
            context = context,
            updateStore = UpdateStore(context),
            settingsDataStore = SettingsDataStore(context),
            metadataSource = source,
            manifestVerifier = UpdateManifestVerifier(publicKeySpkiBase64 = ""),
            apkVerifier = ApkUpdateVerifier(),
            notificationManager = DownloadNotificationManager(context),
            engineCoordinator = EngineCoordinator(context),
            installedAppVersionName = "0.0.4-dev",
            installedAppVersionCode = 4L,
            deviceSdkInt = 35,
            deviceSupportedAbis = setOf("arm64-v8a")
        )
        armManager.setUpdateChannel(UpdateChannel.BETA)
        val armBeta = armManager.checkForUpdates(forceManual = true).getOrThrow()
        assertTrue(armBeta.app.updateAvailable)
        assertEquals("0.0.5-beta", armBeta.app.latestVersionName)
        assertEquals("Shrey app update available", armBeta.statusBadgeLabel)

        // Switching to Stable clears cached Beta update immediately and reports no Stable update
        armManager.setUpdateChannel(UpdateChannel.STABLE)
        val armStable = armManager.checkForUpdates(forceManual = true).getOrThrow()
        assertFalse(armStable.app.updateAvailable)
        assertEquals(UpdateCheckState.UP_TO_DATE, armStable.checkState)

        // x86_64-only device rejects arm64-v8a-only APK as incompatible
        val x86Manager = UpdateManager(
            context = context,
            updateStore = UpdateStore(context),
            settingsDataStore = SettingsDataStore(context),
            metadataSource = source,
            manifestVerifier = UpdateManifestVerifier(publicKeySpkiBase64 = ""),
            apkVerifier = ApkUpdateVerifier(),
            notificationManager = DownloadNotificationManager(context),
            engineCoordinator = EngineCoordinator(context),
            installedAppVersionName = "0.0.4-dev",
            installedAppVersionCode = 4L,
            deviceSdkInt = 35,
            deviceSupportedAbis = setOf("x86_64")
        )
        x86Manager.setUpdateChannel(UpdateChannel.BETA)
        val x86Snap = x86Manager.checkForUpdates(forceManual = true).getOrThrow()
        assertFalse(x86Snap.app.updateAvailable)
        assertEquals(UpdateCheckState.UP_TO_DATE, x86Snap.checkState)

        // Installed 0.0.3 (code 3) + remote 0.0.4-beta on Beta channel = UPDATE AVAILABLE
        val v004BetaJson = """
            [
              {
                "tag_name": "v0.0.4-beta",
                "name": "Shrey v0.0.4 Beta",
                "body": "versionCode: 4",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.4-beta",
                "assets": [
                  {
                    "name": "Shrey-v0.0.4-beta.apk",
                    "browser_download_url": "https://github.com/NoWayShub/Shrey/releases/download/v0.0.4-beta/Shrey-v0.0.4-beta.apk",
                    "size": 250000000
                  }
                ]
              }
            ]
        """.trimIndent()
        val v003Manager = buildManagerWithOkHttp(
            installedVersion = "0.0.3",
            installedCode = 3L,
            interceptor = Interceptor { chain ->
                if (chain.request().url.toString().contains("NoWayShub/Shrey")) {
                    jsonResponse(chain, 200, v004BetaJson)
                } else {
                    jsonResponse(chain, 200, ytDlpSameJson)
                }
            }
        )
        v003Manager.setUpdateChannel(UpdateChannel.BETA)
        val v003Snap = v003Manager.checkForUpdates(forceManual = true).getOrThrow()
        assertTrue(v003Snap.app.updateAvailable)
        assertEquals("0.0.4-beta", v003Snap.app.latestVersionName)
        assertEquals("Shrey app update available", v003Snap.statusBadgeLabel)
    }

    @Test
    fun `clears stale update available after upgrade on 304 and reports failed check when offline`() = runBlocking {
        val shreyV004BetaJson = """
            [
              {
                "tag_name": "v0.0.4-beta",
                "name": "Shrey v0.0.4 Beta",
                "body": "versionCode: 4",
                "draft": false,
                "prerelease": true,
                "html_url": "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.4-beta",
                "assets": [
                  {
                    "name": "Shrey-v0.0.4-beta.apk",
                    "browser_download_url": "https://github.com/NoWayShub/Shrey/releases/download/v0.0.4-beta/Shrey-v0.0.4-beta.apk",
                    "size": 250000000
                  }
                ]
              }
            ]
        """.trimIndent()
        val ytDlpSameJson = """{"tag_name": "2026.10.26", "name": "2026.10.26", "html_url": "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26", "assets": []}"""

        val sharedStore = UpdateStore(context)
        val sharedSettings = SettingsDataStore(context)

        // Step 1: On 0.0.3, check finds v0.0.4-beta and persists UPDATE_AVAILABLE + ETag
        val oldClient = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.toString().contains("NoWayShub/Shrey")) {
                jsonResponse(chain, 200, shreyV004BetaJson, mapOf("ETag" to "\"etag-v004\""))
            } else {
                jsonResponse(chain, 200, ytDlpSameJson)
            }
        }.build()
        val oldManager = UpdateManager(
            context = context,
            updateStore = sharedStore,
            settingsDataStore = sharedSettings,
            metadataSource = OkHttpReleaseMetadataSource(client = oldClient),
            manifestVerifier = UpdateManifestVerifier(publicKeySpkiBase64 = ""),
            apkVerifier = ApkUpdateVerifier(),
            notificationManager = DownloadNotificationManager(context),
            engineCoordinator = EngineCoordinator(context),
            installedAppVersionName = "0.0.3",
            installedAppVersionCode = 3L
        )
        oldManager.setUpdateChannel(UpdateChannel.BETA)
        val beforeUpgrade = oldManager.checkForUpdates(forceManual = true).getOrThrow()
        assertTrue(beforeUpgrade.app.updateAvailable)
        assertEquals(UpdateCheckState.UPDATE_AVAILABLE, beforeUpgrade.checkState)

        // Step 2: App is upgraded to 0.0.4-dev (code 4) and GitHub returns HTTP 304 Not Modified.
        // Status MUST recompute and clear stale UPDATE_AVAILABLE to UP_TO_DATE.
        val notModifiedClient = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.toString().contains("NoWayShub/Shrey")) {
                jsonResponse(chain, 304, "", mapOf("ETag" to "\"etag-v004\""))
            } else {
                jsonResponse(chain, 200, ytDlpSameJson)
            }
        }.build()
        val upgradedManager = UpdateManager(
            context = context,
            updateStore = sharedStore,
            settingsDataStore = sharedSettings,
            metadataSource = OkHttpReleaseMetadataSource(client = notModifiedClient),
            manifestVerifier = UpdateManifestVerifier(publicKeySpkiBase64 = ""),
            apkVerifier = ApkUpdateVerifier(),
            notificationManager = DownloadNotificationManager(context),
            engineCoordinator = EngineCoordinator(context),
            installedAppVersionName = "0.0.4-dev",
            installedAppVersionCode = 4L
        )
        val after304 = upgradedManager.checkForUpdates(forceManual = true).getOrThrow()
        assertFalse(after304.app.updateAvailable)
        assertEquals(UpdateCheckState.UP_TO_DATE, after304.checkState)
        assertEquals("Up to date", after304.statusBadgeLabel)

        // Step 3: Subsequent non-manual offline check MUST report FAILED rather than claiming an update
        val offlineClient = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.toString().contains("NoWayShub/Shrey")) {
                throw UnknownHostException("api.github.com")
            } else {
                jsonResponse(chain, 200, ytDlpSameJson)
            }
        }.build()
        val offlineManager = UpdateManager(
            context = context,
            updateStore = sharedStore,
            settingsDataStore = sharedSettings,
            metadataSource = OkHttpReleaseMetadataSource(client = offlineClient),
            manifestVerifier = UpdateManifestVerifier(publicKeySpkiBase64 = ""),
            apkVerifier = ApkUpdateVerifier(),
            notificationManager = DownloadNotificationManager(context),
            engineCoordinator = EngineCoordinator(context),
            installedAppVersionName = "0.0.4-dev",
            installedAppVersionCode = 4L,
            nowMillisProvider = { System.currentTimeMillis() + 60L * 60L * 1000L }
        )
        val offlineResult = offlineManager.checkForUpdates(forceManual = false)
        assertTrue(offlineResult.isFailure)
        assertEquals(UpdateCheckState.FAILED, offlineManager.snapshot.value.checkState)
        assertFalse(offlineManager.snapshot.value.app.updateAvailable)
        assertEquals("Check failed", offlineManager.snapshot.value.statusBadgeLabel)
    }
}

package com.shrey.downloader.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class GitHubRateLimitException(
    message: String,
    val resetEpochSeconds: Long? = null
) : IOException(message)

class GitHubOfflineException(message: String) : IOException(message)

interface ReleaseMetadataSource {
    suspend fun fetchLatestYtDlpRelease(): Result<GitHubReleaseInfo>
    suspend fun fetchShreyReleases(): Result<List<GitHubReleaseInfo>>

    suspend fun fetchShreyReleasesConditional(ifNoneMatch: String?): Result<ConditionalReleasesResponse> {
        return fetchShreyReleases().map { releases ->
            ConditionalReleasesResponse(releases = releases, etag = null, notModified = false)
        }
    }

    suspend fun downloadFile(
        url: String,
        allowedHosts: Set<String>,
        destination: File,
        onProgress: (Float) -> Unit
    ): Result<File>
}

class OkHttpReleaseMetadataSource(
    private val userAgent: String = "Shrey-Android-Updater/0.0.4",
    private val shreyReleasesApiUrl: String = UpdateTrustConfig.SHREY_RELEASES_API_URL,
    private val ytDlpLatestApiUrl: String = UpdateTrustConfig.YTDLP_LATEST_RELEASE_API_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) : ReleaseMetadataSource {

    override suspend fun fetchLatestYtDlpRelease(): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val result = executeJsonRequestWithRetry(ytDlpLatestApiUrl, ifNoneMatch = null)
            parseReleaseObject(JSONObject(result.body.orEmpty()))
        }
    }

    override suspend fun fetchShreyReleases(): Result<List<GitHubReleaseInfo>> =
        fetchShreyReleasesConditional(ifNoneMatch = null).map { it.releases }

    override suspend fun fetchShreyReleasesConditional(
        ifNoneMatch: String?
    ): Result<ConditionalReleasesResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val result = executeJsonRequestWithRetry(shreyReleasesApiUrl, ifNoneMatch = ifNoneMatch)
            if (result.notModified) {
                return@runCatching ConditionalReleasesResponse(
                    releases = emptyList(),
                    etag = result.etag ?: ifNoneMatch,
                    notModified = true
                )
            }
            val array = JSONArray(result.body.orEmpty())
            val releases = buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (obj.optBoolean("draft", false)) continue
                    add(parseReleaseObject(obj))
                }
            }
            ConditionalReleasesResponse(
                releases = releases,
                etag = result.etag,
                notModified = false
            )
        }
    }

    override suspend fun downloadFile(
        url: String,
        allowedHosts: Set<String>,
        destination: File,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(UpdateManifestVerifier.isAllowedDownloadUrl(url, allowedHosts)) {
                "Download URL is not on an approved HTTPS host."
            }
            destination.parentFile?.mkdirs()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    throw translateHttpError(response)
                }
                // Verify final redirected host is also allowlisted and HTTPS
                val finalUrl = response.request.url.toString()
                require(UpdateManifestVerifier.isAllowedDownloadUrl(finalUrl, allowedHosts)) {
                    "Redirected download host is not in the trusted allowlist."
                }
                val body = response.body ?: throw IOException("Empty download body")
                val totalBytes = body.contentLength()
                var downloaded = 0L

                destination.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0L) {
                                onProgress((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                    out.fd.sync()
                }
                onProgress(1f)
                destination
            }
        }
    }

    private data class JsonFetchResult(
        val body: String?,
        val etag: String?,
        val notModified: Boolean
    )

    private suspend fun executeJsonRequestWithRetry(
        url: String,
        ifNoneMatch: String?
    ): JsonFetchResult {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= MAX_TRANSIENT_RETRIES) {
            try {
                return fetchJsonOnce(url, ifNoneMatch)
            } catch (rateLimit: GitHubRateLimitException) {
                throw rateLimit
            } catch (offline: GitHubOfflineException) {
                throw offline
            } catch (timeout: SocketTimeoutException) {
                lastError = IOException("GitHub API request timed out. Check your connection and try again.", timeout)
            } catch (io: IOException) {
                lastError = io
            }
            attempt++
            if (attempt <= MAX_TRANSIENT_RETRIES) {
                delay(RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IOException("Unable to reach GitHub API.")
    }

    private fun fetchJsonOnce(url: String, ifNoneMatch: String?): JsonFetchResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (!ifNoneMatch.isNullOrBlank()) {
            builder.header("If-None-Match", ifNoneMatch)
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                val etag = response.header("ETag")?.takeIf { it.isNotBlank() }
                if (response.code == 304) {
                    return JsonFetchResult(body = null, etag = etag ?: ifNoneMatch, notModified = true)
                }
                if (!response.isSuccessful) {
                    throw translateHttpError(response)
                }
                val text = response.body?.string() ?: throw IOException("Empty GitHub API response")
                JsonFetchResult(body = text, etag = etag, notModified = false)
            }
        } catch (uhe: UnknownHostException) {
            throw GitHubOfflineException("Offline — could not resolve GitHub release server.")
        }
    }

    private fun translateHttpError(response: Response): IOException {
        val code = response.code
        val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
        val resetEpoch = response.header("X-RateLimit-Reset")?.toLongOrNull()
        if (code == 429 || (code == 403 && remaining == 0)) {
            val waitHint = resetEpoch?.let { reset ->
                val mins = ((reset - (System.currentTimeMillis() / 1000L)) / 60L).coerceAtLeast(1L)
                " Try again in about $mins min."
            }.orEmpty()
            return GitHubRateLimitException(
                message = "GitHub API rate limit reached (HTTP $code).$waitHint",
                resetEpochSeconds = resetEpoch
            )
        }
        if (code in 500..599) {
            return IOException("GitHub servers temporarily unavailable (HTTP $code).")
        }
        return IOException("GitHub API returned HTTP $code.")
    }

    private fun parseReleaseObject(obj: JSONObject): GitHubReleaseInfo {
        val assetsArray = obj.optJSONArray("assets") ?: JSONArray()
        val assets = buildList {
            for (i in 0 until assetsArray.length()) {
                val a = assetsArray.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                val downloadUrl = a.optString("browser_download_url", "")
                if (name.isNotBlank() && downloadUrl.isNotBlank()) {
                    add(
                        GitHubReleaseAsset(
                            name = name,
                            downloadUrl = downloadUrl,
                            sizeBytes = a.optLong("size", 0L),
                            contentType = a.optString("content_type", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
        val tagName = obj.optString("tag_name", "").trim()
        val body = obj.optString("body", "").trim()
        val parsedVersionCode = parseExplicitVersionCode(tagName = tagName, body = body)
        val parsedMinSdk = parseExplicitMinSdk(body = body)
        return GitHubReleaseInfo(
            tagName = tagName,
            name = obj.optString("name", "").trim().ifBlank { tagName },
            body = body,
            htmlUrl = obj.optString("html_url", "").trim(),
            isPrerelease = obj.optBoolean("prerelease", false),
            publishedAt = obj.optString("published_at", "").takeIf { it.isNotBlank() },
            assets = assets,
            isDraft = obj.optBoolean("draft", false),
            versionCode = parsedVersionCode,
            minSdkVersion = parsedMinSdk
        )
    }

    private fun parseExplicitVersionCode(tagName: String, body: String): Long? {
        UpdateVersionComparator.extractNumericBuildMetadata(tagName)?.let { return it }
        val match = VERSION_CODE_BODY_REGEX.find(body) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
    }

    private fun parseExplicitMinSdk(body: String): Int? {
        val match = MIN_SDK_BODY_REGEX.find(body) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 21..99 }
    }

    companion object {
        private const val MAX_TRANSIENT_RETRIES = 1
        private const val RETRY_DELAY_MS = 450L
        private val VERSION_CODE_BODY_REGEX = Regex(
            pattern = """(?im)(?:^|\b)version[_ ]?code\s*[:=]\s*(\d+)\b"""
        )
        private val MIN_SDK_BODY_REGEX = Regex(
            pattern = """(?im)(?:^|\b)min[_ ]?sdk(?:[_ ]?version)?\s*[:=]\s*(\d+)\b"""
        )
    }
}

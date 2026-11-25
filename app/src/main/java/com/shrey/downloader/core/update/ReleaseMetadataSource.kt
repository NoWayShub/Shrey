package com.shrey.downloader.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

interface ReleaseMetadataSource {
    suspend fun fetchLatestYtDlpRelease(): Result<GitHubReleaseInfo>
    suspend fun fetchShreyReleases(): Result<List<GitHubReleaseInfo>>
    suspend fun downloadFile(
        url: String,
        allowedHosts: Set<String>,
        destination: File,
        onProgress: (Float) -> Unit
    ): Result<File>
}

class OkHttpReleaseMetadataSource(
    private val userAgent: String = "Shrey-Android-Updater",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) : ReleaseMetadataSource {

    override suspend fun fetchLatestYtDlpRelease(): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val body = fetchJson(UpdateTrustConfig.YTDLP_LATEST_RELEASE_API_URL)
            parseReleaseObject(JSONObject(body))
        }
    }

    override suspend fun fetchShreyReleases(): Result<List<GitHubReleaseInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = fetchJson(UpdateTrustConfig.SHREY_RELEASES_API_URL)
            val array = JSONArray(body)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (obj.optBoolean("draft", false)) continue
                    add(parseReleaseObject(obj))
                }
            }
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
                    throw IOException("HTTP Error ${response.code}: ${response.message}")
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

    private fun fetchJson(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub API returned HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("Empty GitHub API response")
        }
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
        return GitHubReleaseInfo(
            tagName = obj.optString("tag_name", "").trim(),
            name = obj.optString("name", "").trim().ifBlank { obj.optString("tag_name", "").trim() },
            body = obj.optString("body", "").trim(),
            htmlUrl = obj.optString("html_url", "").trim(),
            isPrerelease = obj.optBoolean("prerelease", false),
            publishedAt = obj.optString("published_at", "").takeIf { it.isNotBlank() },
            assets = assets
        )
    }
}

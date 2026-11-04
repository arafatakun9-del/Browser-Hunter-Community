package com.example.download

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ExtractedMediaFormat(
    val formatId: String,
    val resolution: String,
    val mimeType: String,
    val downloadUrl: String,
    val estimatedBytes: Long = -1L,
    val isAudioOnly: Boolean = false,
    val fileExtension: String = ""
)

data class ExtractedMediaInfo(
    val title: String,
    val sourceUrl: String,
    val platform: String,
    val thumbnailUrl: String? = null,
    val formats: List<ExtractedMediaFormat>
)

data class EngineUpdateState(
    val installedVersion: String,
    val lastCheckTime: String,
    val statusMessage: String,
    val isChecking: Boolean = false,
    val autoCheckFrequency: String = "Daily"
)

class ExtractorEngineManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("browser_engine_prefs", Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val engineDir = File(context.filesDir, "download_engine").apply { if (!exists()) mkdirs() }
    private val rulesFile = File(engineDir, "extractor_rules.json")
    private val backupFile = File(engineDir, "extractor_rules.bak")

    private val defaultVersion = "2026.10.28"
    private val defaultRulesJson = """
    {
      "version": "$defaultVersion",
      "updated_at": "2026-10-28",
      "platforms": {
        "youtube": {
          "regex": "(?:youtube\\.com\\/(?:watch\\?v=|shorts\\/)|youtu\\.be\\/)([a-zA-Z0-9_-]{11})",
          "extract_mode": "oembed_and_stream"
        },
        "tiktok": {
          "regex": "tiktok\\.com\\/@?[^\\/]+\\/video\\/(\\d+)",
          "extract_mode": "direct_cdn"
        },
        "facebook": {
          "regex": "facebook\\.com\\/(?:watch\\/\\?v=|.*\\/videos\\/|reel\\/)(\\d+)",
          "extract_mode": "og_video"
        },
        "generic_media": {
          "extensions": ["mp4", "webm", "mkv", "mp3", "m4a", "pdf", "zip", "apk"]
        }
      }
    }
    """.trimIndent()

    private val _engineState = MutableStateFlow(
        EngineUpdateState(
            installedVersion = prefs.getString("engine_version", defaultVersion) ?: defaultVersion,
            lastCheckTime = prefs.getString("last_check_time", "Never") ?: "Never",
            statusMessage = "Direct-link detection ready; full extractor runtime not bundled"
        )
    )
    val engineState: StateFlow<EngineUpdateState> = _engineState.asStateFlow()

    init {
        // Ensure initial rules file exists
        if (!rulesFile.exists()) {
            rulesFile.writeText(defaultRulesJson)
        }
    }

    /**
     * Safe Automatic Engine Update:
     * Checks remote manifest, verifies integrity, updates atomically, with safe rollback.
     */
    /**
     * Checks the official yt-dlp release metadata only.
     *
     * This app currently does not bundle or update the yt-dlp runtime. Never report
     * a metadata/rules refresh as if it installed a media extraction engine.
     */
    suspend fun checkForEngineUpdates(customUpdateUrl: String? = null): Result<String> = withContext(Dispatchers.IO) {
        _engineState.value = _engineState.value.copy(
            isChecking = true,
            statusMessage = "Checking official yt-dlp release metadata…"
        )
        val targetUrl = customUpdateUrl?.takeIf { it.isNotBlank() }
            ?: "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"

        try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BrowserHunter-VersionChecker")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val nowFormatted = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date())
                prefs.edit().putString("last_check_time", nowFormatted).apply()

                if (!response.isSuccessful) {
                    val message = "Upstream version check failed (HTTP ${response.code}); no local files changed."
                    _engineState.value = _engineState.value.copy(
                        isChecking = false,
                        lastCheckTime = nowFormatted,
                        statusMessage = message
                    )
                    return@withContext Result.failure(IllegalStateException(message))
                }

                val json = JSONObject(response.body?.string().orEmpty())
                val latestVersion = json.optString("tag_name").ifBlank {
                    json.optString("name", "unknown")
                }
                val message = "Upstream yt-dlp latest: $latestVersion. Runtime is not bundled; no engine update was installed."
                _engineState.value = _engineState.value.copy(
                    isChecking = false,
                    lastCheckTime = nowFormatted,
                    statusMessage = message
                )
                Result.success(message)
            }
        } catch (e: Exception) {
            val nowFormatted = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date())
            val message = "Version check failed: ${e.localizedMessage ?: "network error"}. No local files changed."
            _engineState.value = _engineState.value.copy(
                isChecking = false,
                lastCheckTime = nowFormatted,
                statusMessage = message
            )
            Result.failure(e)
        }
    }

    /**
     * Resolves and extracts available video/audio formats from media URLs.
     */
    suspend fun extractMedia(url: String): Result<ExtractedMediaInfo> = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(url)
            val host = uri.host.orEmpty().lowercase()

            when {
                host.contains("youtube.com") || host.contains("youtu.be") -> {
                    extractYouTubeMedia(url)
                }
                host.contains("tiktok.com") -> {
                    extractGenericHtmlMedia(url, "TikTok Video")
                }
                host.contains("facebook.com") || host.contains("fb.watch") -> {
                    extractGenericHtmlMedia(url, "Facebook Video")
                }
                else -> {
                    extractDirectOrGenericMedia(url)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun extractYouTubeMedia(url: String): Result<ExtractedMediaInfo> {
        // YouTube page URLs are not media streams. Never present the page URL as
        // 1080p/720p/audio or estimate a file size without a real extractor.
        return Result.failure(
            UnsupportedOperationException(
                "YouTube extraction needs the optional media extraction engine, which is not installed yet. The original page link was not queued as a video file."
            )
        )
    }

    private suspend fun extractGenericHtmlMedia(url: String, defaultTitle: String): Result<ExtractedMediaInfo> {
        var pageTitle = defaultTitle
        var videoUrl: String? = null

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
                .build()

            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return Result.failure(IllegalStateException("The page could not be accessed (HTTP ${res.code}). It may require login or block automated requests."))
                }
                val html = res.body?.string().orEmpty()
                val titleMatch = Regex("""<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                if (titleMatch != null) pageTitle = titleMatch.groupValues[1]
                val videoMatch = Regex("""<meta\s+property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                videoUrl = videoMatch?.groupValues?.get(1)?.replace("&amp;", "&")
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val directUrl = videoUrl?.takeIf { candidate ->
            val parsed = Uri.parse(candidate)
            parsed.scheme == "https" || parsed.scheme == "http"
        } ?: return Result.failure(
            UnsupportedOperationException(
                "$defaultTitle: no direct video URL was exposed by the page. Login, app-only playback, or platform restrictions may prevent extraction."
            )
        )

        // Probe the exposed URL rather than assuming every og:video URL is an MP4.
        // HLS/DASH manifests and HTML responses are not mislabeled as downloadable MP4 files.
        val probed = probeDirectContent(directUrl)
        val urlExtension = knownFileExtension(directUrl)
        val probedType = probed?.first.orEmpty()
        if (probedType == "text/html" || probedType == "application/xhtml+xml") {
            return Result.failure(
                UnsupportedOperationException("$defaultTitle: the exposed URL returned a web page, not a direct media file.")
            )
        }
        if (probedType.contains("mpegurl") || probedType.contains("dash+xml")) {
            return Result.failure(
                UnsupportedOperationException("$defaultTitle: this URL is a streaming manifest, not a standalone media file; manifest downloading is not supported.")
            )
        }

        val resolvedType = when {
            probedType.startsWith("video/") || probedType.startsWith("audio/") -> probedType
            urlExtension != null -> mimeTypeForExtension(urlExtension)
            else -> null
        } ?: return Result.failure(
            UnsupportedOperationException("$defaultTitle: a direct URL was found, but its media file type could not be verified.")
        )
        val extension = extensionForMimeType(resolvedType) ?: urlExtension
            ?: return Result.failure(
                UnsupportedOperationException("$defaultTitle: the direct media type is not supported for a correctly named file.")
            )

        // This is a direct media file only. No fake resolution, audio conversion, or estimated size.
        val format = ExtractedMediaFormat(
            formatId = "direct_video",
            resolution = "Direct media (server-provided format)",
            mimeType = resolvedType,
            downloadUrl = directUrl,
            estimatedBytes = probed?.second ?: -1L,
            isAudioOnly = resolvedType.startsWith("audio/"),
            fileExtension = extension
        )

        return Result.success(
            ExtractedMediaInfo(
                title = pageTitle,
                sourceUrl = url,
                platform = defaultTitle.removeSuffix(" Video"),
                formats = listOf(format)
            )
        )
    }

    private suspend fun probeDirectContent(url: String, tryHead: Boolean = true): Pair<String, Long>? {
        if (tryHead) {
            try {
                val head = Request.Builder().url(url).head().build()
                httpClient.newCall(head).execute().use { response ->
                    if (response.isSuccessful) {
                        val type = response.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase().orEmpty()
                        val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        return type to length
                    }
                }
            } catch (_: Exception) {
                // Some CDNs reject HEAD; try a one-byte range request without reading the full body.
            }
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val type = response.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase().orEmpty()
                val length = response.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: -1L
                type to length
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun knownFileExtension(url: String): String? {
        val extension = Uri.parse(url).lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        return extension?.takeIf {
            it in setOf(
                "mp4", "m4v", "webm", "mkv", "mov", "3gp", "mp3", "m4a", "aac", "ogg", "wav",
                "pdf", "zip", "apk", "jpg", "jpeg", "png", "gif", "webp", "txt", "csv",
                "doc", "docx", "xls", "xlsx", "ppt", "pptx", "json", "xml", "7z", "rar", "tar", "gz"
            )
        }
    }

    private fun mimeTypeForExtension(extension: String): String = when (extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }

    private fun extensionForMimeType(mimeType: String): String? = when (mimeType.lowercase()) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        "application/pdf" -> "pdf"
        "application/zip", "application/x-zip-compressed" -> "zip"
        "application/vnd.android.package-archive" -> "apk"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "text/plain" -> "txt"
        "text/csv" -> "csv"
        "application/json" -> "json"
        "application/xml", "text/xml" -> "xml"
        "application/msword" -> "doc"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.ms-excel" -> "xls"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.ms-powerpoint" -> "ppt"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
        else -> null
    }

    private suspend fun extractDirectOrGenericMedia(url: String): Result<ExtractedMediaInfo> {
        val fileName = Uri.parse(url).lastPathSegment ?: "media_file"
        val urlExtension = knownFileExtension(url)
        var contentLength = -1L
        var contentType = ""
        var headSucceeded = false

        try {
            val headReq = Request.Builder().url(url).head().build()
            httpClient.newCall(headReq).execute().use { res ->
                if (res.isSuccessful) {
                    headSucceeded = true
                    contentLength = res.header("Content-Length")?.toLongOrNull() ?: -1L
                    contentType = res.header("Content-Type")?.substringBefore(";")?.trim()?.lowercase().orEmpty()
                }
            }
        } catch (_: Exception) {}

        if (!headSucceeded) {
            val probe = probeDirectContent(url, tryHead = false)
            if (probe != null) {
                headSucceeded = true
                contentType = probe.first
                contentLength = probe.second
            }
        }

        if (contentType == "text/html" || contentType == "application/xhtml+xml") {
            return Result.failure(
                UnsupportedOperationException("This URL returned an HTML page, not a direct file. The page will not be queued as a media download.")
            )
        }
        if (contentType.isBlank() || contentType == "application/octet-stream") {
            if (urlExtension != null) contentType = mimeTypeForExtension(urlExtension)
        }
        if (!headSucceeded && urlExtension == null) {
            return Result.failure(
                UnsupportedOperationException("Could not verify a direct file URL. Paste a direct download link or a URL with a known file extension.")
            )
        }
        if (contentType.isBlank()) contentType = "application/octet-stream"

        val isAudio = contentType.startsWith("audio/")
        val isVideo = contentType.startsWith("video/")
        val isPdf = contentType.contains("pdf") || urlExtension == "pdf"

        val resLabel = when {
            isPdf -> "PDF Document (Clean White)"
            isVideo -> "Direct Video Stream"
            isAudio -> "Direct Audio Stream"
            else -> "Standard File Download"
        }

        val format = ExtractedMediaFormat(
            formatId = "direct_stream",
            resolution = resLabel,
            mimeType = contentType,
            downloadUrl = url,
            estimatedBytes = contentLength,
            isAudioOnly = isAudio,
            fileExtension = extensionForMimeType(contentType) ?: urlExtension.orEmpty()
        )

        return Result.success(
            ExtractedMediaInfo(
                title = fileName,
                sourceUrl = url,
                platform = "Universal Stream",
                formats = listOf(format)
            )
        )
    }
}

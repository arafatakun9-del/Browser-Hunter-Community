package com.example.model

import android.graphics.Bitmap

data class BrowserTab(
    val id: String,
    val title: String,
    val url: String,
    val favicon: Bitmap? = null,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isHibernated: Boolean = false,
    val isIncognito: Boolean = false
)

enum class DownloadStatus {
    PENDING,
    CONNECTING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class DownloadChunk(
    val chunkIndex: Int,
    val startByte: Long,
    val endByte: Long,
    var downloadedBytes: Long = 0L,
    var isFinished: Boolean = false
)

data class DownloadItem(
    val id: String,
    val originalUrl: String,
    val downloadUrl: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val speedBps: Long = 0L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val threadCount: Int = 4,
    val progressPercent: Int = 0,
    val localFilePath: String? = null,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String,
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageSender {
    USER,
    ARAFAT_AI
}

enum class UserAgentOption(val label: String, val userAgentString: String?) {
    DEFAULT("Default (Realme C71)", null),
    CHROME_DESKTOP(
        "Chrome Desktop (Spoof PC)",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    ),
    SAFARI_IOS(
        "Safari (iPhone 16 Spoof)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    ),
    FIREFOX_LINUX(
        "Firefox (Stealth Linux)",
        "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
    )
}

data class ReaderArticle(
    val title: String,
    val url: String,
    val domain: String,
    val paragraphs: List<String>
)

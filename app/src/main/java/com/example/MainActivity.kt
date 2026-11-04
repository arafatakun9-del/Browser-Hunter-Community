package com.example

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.os.Build
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.room.Room
import com.example.ai.ArafatAiService
import com.example.browser.TabManager
import com.example.data.BookmarkEntity
import com.example.data.BrowserDatabase
import com.example.data.HistoryEntity
import com.example.download.DownloadForegroundService
import com.example.download.ExtractorEngineManager
import com.example.download.MultiThreadDownloader
import com.example.model.BrowserTab
import com.example.model.ChatMessage
import com.example.model.DownloadStatus
import com.example.model.MessageSender
import com.example.model.ReaderArticle
import com.example.model.UserAgentOption
import com.example.security.GhostSecurityManager
import com.example.ui.browser.ArafatAiSheet
import com.example.ui.browser.BookmarkHistorySheet
import com.example.ui.browser.BrowserBottomBar
import com.example.ui.browser.BrowserHomePage
import com.example.ui.browser.BrowserOmnibox
import com.example.ui.browser.BrowserTabsSheet
import com.example.ui.browser.DownloadManagerSheet
import com.example.ui.browser.GhostModeDialog
import com.example.ui.browser.ReaderModeView
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var tabManager: TabManager
    private lateinit var downloader: MultiThreadDownloader
    private lateinit var engineManager: ExtractorEngineManager
    private lateinit var aiService: ArafatAiService
    private lateinit var ghostSecurity: GhostSecurityManager
    private lateinit var database: BrowserDatabase

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var customVideoView: View? = null
    private var customVideoViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customVideoContainer: FrameLayout? = null

    // Activity Result Launcher for WebView File Uploads (<input type="file">)
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback != null) {
            val results: Array<Uri>? = if (result.resultCode == RESULT_OK) {
                val data = result.data
                val dataString = data?.dataString
                if (dataString != null) {
                    arrayOf(Uri.parse(dataString))
                } else if (data?.clipData != null) {
                    val count = data.clipData!!.itemCount
                    val uris = mutableListOf<Uri>()
                    for (i in 0 until count) {
                        uris.add(data.clipData!!.getItemAt(i).uri)
                    }
                    uris.toTypedArray()
                } else null
            } else null
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }
    }

    private fun activeWebView(): WebView? {
        if (!::tabManager.isInitialized) return null
        return tabManager.getLiveWebView(tabManager.activeTabId.value)
    }

    // State holders
    private val isDarkModeState = mutableStateOf(false)
    private val isGhostActiveState = mutableStateOf(false)
    private val isFlagSecureState = mutableStateOf(false)
    private val isShakeEnabledState = mutableStateOf(true)
    private val userAgentState = mutableStateOf(UserAgentOption.DEFAULT)
    private val ghostPinState = mutableStateOf<String?>(null)

    private val aiMessagesState = mutableStateOf<List<ChatMessage>>(emptyList())
    private val isAiLoadingState = mutableStateOf(false)
    private val customApiKeyState = mutableStateOf("")

    private val showAiSheetState = mutableStateOf(false)
    private val showDownloadsSheetState = mutableStateOf(false)
    private val sharedDownloadUrlState = mutableStateOf<String?>(null)
    private val showTabsSheetState = mutableStateOf(false)
    private val showBookmarksHistoryState = mutableStateOf(false)
    private val showGhostDialogState = mutableStateOf(false)
    private val showReaderModeState = mutableStateOf(false)
    private val activeReaderArticleState = mutableStateOf<ReaderArticle?>(null)
    private val showShakeDialogState = mutableStateOf(false)
    private val isStandaloneAiState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        database = Room.databaseBuilder(
            applicationContext,
            BrowserDatabase::class.java,
            "browser_hunter_v2.db"
        ).build()

        tabManager = TabManager(applicationContext)
        downloader = (application as BrowserHunterApplication).downloader
        engineManager = ExtractorEngineManager(applicationContext)
        aiService = ArafatAiService(applicationContext)

        ghostSecurity = GhostSecurityManager(this) {
            if (isShakeEnabledState.value) {
                runOnUiThread {
                    showShakeDialogState.value = true
                }
            }
        }

        // Handle incoming intent (Share to download sniper or Standalone AI)
        handleIntent(intent)

        setContent {
            MyApplicationTheme(darkTheme = true) {
                MainBrowserScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isShakeEnabledState.value) {
            ghostSecurity.startShakeListener()
        }
        activeWebView()?.onResume()
    }

    override fun onPause() {
        super.onPause()
        ghostSecurity.stopShakeListener()
        // Keep the WebView media pipeline alive while a fullscreen HTML5 video is visible,
        // so Android PiP/background transitions do not immediately pause playback.
        if (customVideoView == null) activeWebView()?.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (customVideoView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            } catch (_: Exception) {
                // PiP may be disabled by device/app settings; fullscreen playback remains available.
            }
        }
    }

    override fun onDestroy() {
        try {
            customVideoViewCallback?.onCustomViewHidden()
            customVideoContainer?.let { (window.decorView as? ViewGroup)?.removeView(it) }
            customVideoView = null
            customVideoContainer = null
            if (::tabManager.isInitialized) tabManager.destroyAllWebViews()
            ghostSecurity.stopShakeListener()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun appendAiMessage(message: ChatMessage) {
        // Keep the in-memory assistant transcript bounded on low-RAM devices.
        aiMessagesState.value = (aiMessagesState.value + message).takeLast(6)
    }

    private fun enqueueDownloadInBackground(url: String, fileName: String?): String {
        val id = downloader.enqueueDownload(url, suggestedFileName = fileName)
        if (!DownloadForegroundService.start(this)) {
            Toast.makeText(this, "Background download service could not start. Keep Browser Hunter open.", Toast.LENGTH_LONG).show()
        }
        return id
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // 1. Check Standalone PWA Shortcut Intent
        if (intent.getBooleanExtra(ArafatAiService.EXTRA_STANDALONE_AI, false)) {
            isStandaloneAiState.value = true
            showAiSheetState.value = true
            return
        }

        // 2. Check Universal "Share to Download" Sniper Intent
        if (Intent.ACTION_SEND == intent.action && "text/plain" == intent.type) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val urlRegex = Regex("""https?://[^\s]+""")
                val foundUrl = urlRegex.find(sharedText)?.value ?: sharedText.trim()
                sharedDownloadUrlState.value = foundUrl
                showDownloadsSheetState.value = true
                Toast.makeText(this, "Shared link ready. Review it in Downloader and choose extraction/download.", Toast.LENGTH_LONG).show()
            }
            return
        }

        // 3. Check Web URL View Intent
        if (Intent.ACTION_VIEW == intent.action) {
            val dataUri = intent.dataString
            if (!dataUri.isNullOrBlank()) {
                openNewUrlInActiveTab(dataUri)
            }
        }
    }

    private fun openNewUrlInActiveTab(url: String) {
        val activeTab = tabManager.getActiveTab() ?: return
        tabManager.updateTabMetadata(activeTab.id) {
            it.copy(url = url, title = if (url.isBlank()) "New Tab" else "Loading…", isLoading = url.isNotBlank())
        }
        // Resolve by tab ID: when a new blank tab is selected, the old tab's WebView
        // may still be cached for fast switching and must never receive the new URL.
        val selectedWebView = tabManager.getLiveWebView(activeTab.id)
        if (url.isBlank()) selectedWebView?.stopLoading() else selectedWebView?.loadUrl(url)
    }

    @Composable
    private fun MainBrowserScreen() {
        val tabs by tabManager.tabs.collectAsState()
        val activeTabId by tabManager.activeTabId.collectAsState()
        val engineState by engineManager.engineState.collectAsState()
        val downloads by downloader.downloads.collectAsState()

        val bookmarks by database.browserDao().getAllBookmarks().collectAsState(initial = emptyList())
        val history by database.browserDao().getRecentHistory().collectAsState(initial = emptyList())

        val isDarkMode by isDarkModeState
        val isGhostActive by isGhostActiveState
        val isFlagSecure by isFlagSecureState
        val isShakeEnabled by isShakeEnabledState
        val userAgentOption by userAgentState
        val aiMessages by aiMessagesState
        val isAiLoading by isAiLoadingState
        val customApiKey by customApiKeyState

        val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
        val currentUrl = activeTab?.url.orEmpty()
        val isPdfActive = isPdfUrl(currentUrl)
        val isCurrentBookmarked = bookmarks.any { it.url == currentUrl }
        val coroutineScope = rememberCoroutineScope()

        // Handle Back Press
        BackHandler(enabled = true) {
            when {
                showReaderModeState.value -> showReaderModeState.value = false
                showBookmarksHistoryState.value -> showBookmarksHistoryState.value = false
                showAiSheetState.value && !isStandaloneAiState.value -> showAiSheetState.value = false
                showDownloadsSheetState.value -> showDownloadsSheetState.value = false
                showTabsSheetState.value -> showTabsSheetState.value = false
                activeWebView()?.canGoBack() == true -> activeWebView()?.goBack()
                currentUrl.isNotEmpty() -> openNewUrlInActiveTab("")
                else -> finish()
            }
        }

        // Shake to Clear Confirmation Dialog
        if (showShakeDialogState.value) {
            AlertDialog(
                onDismissRequest = { showShakeDialogState.value = false },
                title = { Text("Shake Detected!", color = Color.White) },
                text = {
                    Text(
                        "Would you like to instantly purge all browsing history, cache, cookies, and active tabs?",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showShakeDialogState.value = false
                            ghostSecurity.purgeBrowsingData(activeWebView())
                            coroutineScope.launch {
                                database.browserDao().clearAllHistory()
                            }
                            tabManager.openNewTab("", isIncognito = false)
                            Toast.makeText(this@MainActivity, "Clean Purge Executed!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null)
                        Text("Purge Now")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showShakeDialogState.value = false }) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF1E293B)
            )
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                BrowserOmnibox(
                    currentUrl = currentUrl,
                    isLoading = activeTab?.isLoading == true,
                    progress = activeTab?.progress ?: 0,
                    isPdfActive = isPdfActive,
                    isDarkMode = isDarkMode,
                    isBookmarked = isCurrentBookmarked,
                    tabCount = tabs.size,
                    isGhostActive = isGhostActive,
                    onNavigate = { query -> openNewUrlInActiveTab(sanitizeQueryUrl(query)) },
                    onRefresh = { activeWebView()?.reload() },
                    onToggleBookmark = {
                        if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                            coroutineScope.launch {
                                if (isCurrentBookmarked) {
                                    database.browserDao().deleteBookmarkByUrl(currentUrl)
                                    Toast.makeText(this@MainActivity, "Bookmark removed", Toast.LENGTH_SHORT).show()
                                } else {
                                    database.browserDao().insertBookmark(
                                        BookmarkEntity(url = currentUrl, title = activeTab?.title ?: currentUrl)
                                    )
                                    Toast.makeText(this@MainActivity, "Bookmark added", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onOpenBookmarksHistory = { showBookmarksHistoryState.value = true },
                    onToggleDarkMode = {
                        val newMode = !isDarkMode
                        isDarkModeState.value = newMode
                        applyDarkModeToWebView(activeWebView(), newMode, isPdfActive)
                    },
                    onTranslateBengali = {
                        if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                            val translateUrl = "https://translate.google.com/translate?sl=auto&tl=bn&u=" +
                                    URLEncoder.encode(currentUrl, "UTF-8")
                            openNewUrlInActiveTab(translateUrl)
                            Toast.makeText(this@MainActivity, "Translating page to Bengali (বাংলা)…", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onToggleReaderMode = {
                        extractAndShowReaderMode(activeWebView(), currentUrl)
                    },
                    onOpenAi = { showAiSheetState.value = true },
                    onOpenTabs = { showTabsSheetState.value = true },
                    onOpenDownloads = { showDownloadsSheetState.value = true },
                    onOpenGhostDialog = { showGhostDialogState.value = true },
                    onNewTab = { tabManager.openNewTab(isIncognito = false) },
                    onExitBrowser = {
                        val hasActiveDownloads = downloads.any {
                            it.status == DownloadStatus.PENDING ||
                                it.status == DownloadStatus.CONNECTING ||
                                it.status == DownloadStatus.DOWNLOADING
                        }
                        if (hasActiveDownloads) {
                            if (DownloadForegroundService.start(this@MainActivity)) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Browser closed. Active downloads will continue in the background.",
                                    Toast.LENGTH_LONG
                                ).show()
                                finishAffinity()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Background service could not start. Keep Browser Hunter open until downloads finish.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            ghostSecurity.terminateProcessImmediately()
                        }
                    }
                )
            },
            bottomBar = {
                BrowserBottomBar(
                    canGoBack = activeTab?.canGoBack == true,
                    canGoForward = activeTab?.canGoForward == true,
                    isGhostActive = isGhostActive,
                    isDesktopSite = userAgentOption == UserAgentOption.CHROME_DESKTOP,
                    onToggleDesktopSite = {
                        val next = if (userAgentOption == UserAgentOption.CHROME_DESKTOP) {
                            UserAgentOption.DEFAULT
                        } else {
                            UserAgentOption.CHROME_DESKTOP
                        }
                        userAgentState.value = next
                        activeWebView()?.settings?.userAgentString = resolveUserAgent(next)
                        activeWebView()?.reload()
                        Toast.makeText(
                            this@MainActivity,
                            if (next == UserAgentOption.CHROME_DESKTOP) "Desktop site requested" else "Mobile site requested",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onBack = { activeWebView()?.goBack() },
                    onForward = { activeWebView()?.goForward() },
                    onHome = { openNewUrlInActiveTab("") },
                    onOpenDownloads = { showDownloadsSheetState.value = true },
                    onOpenTabs = { showTabsSheetState.value = true },
                    onOpenGhostDialog = { showGhostDialogState.value = true }
                )
            },
            containerColor = Color(0xFF090D16)
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (currentUrl.isBlank() || currentUrl == "about:blank") {
                    // Browser Homepage with Watermark Background
                    BrowserHomePage(
                        onNavigate = { openNewUrlInActiveTab(sanitizeQueryUrl(it)) },
                        onOpenAi = { showAiSheetState.value = true },
                        onOpenDownloads = { showDownloadsSheetState.value = true },
                        onOpenGhostDialog = { showGhostDialogState.value = true },
                        isGhostActive = isGhostActive,
                        hibernatedTabCount = tabs.count { it.isHibernated },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Give each tab its own WebView instance. Without this key, Compose can reuse
                    // one WebView for different tab IDs and the manager may later destroy the active tab.
                    key(activeTab?.id) {
                        val restoredUrlForFirstUpdate = remember { mutableStateOf<String?>(null) }
                        // Native Chromium-Based WebView View
                        AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                configureWebSettings(this, resolveUserAgent(userAgentOption))
                                if (activeTab != null) {
                                    tabManager.registerLiveWebView(activeTab.id, this)
                                    val saved = tabManager.getSavedBundle(activeTab.id)
                                    if (saved != null) {
                                        val restoredUrl = restoreState(saved)?.currentItem?.url
                                        restoredUrlForFirstUpdate.value = restoredUrl?.takeIf { it == currentUrl }
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    // Fail closed on TLS certificate errors. Never let a user or
                                    // page silently bypass an invalid/expired/mismatched certificate.
                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: android.webkit.SslErrorHandler?,
                                        error: android.net.http.SslError?
                                    ) {
                                        handler?.cancel()
                                        Toast.makeText(
                                            ctx,
                                            "Secure connection blocked: certificate error",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }

                                    // Use Android WebView's built-in Safe Browsing reputation checks.
                                    // Always return to safety for detected malware/phishing threats.
                                    override fun onSafeBrowsingHit(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        threatType: Int,
                                        callback: android.webkit.SafeBrowsingResponse?
                                    ) {
                                        callback?.backToSafety(true)
                                        Toast.makeText(
                                            ctx,
                                            "Potentially dangerous website blocked by Safe Browsing",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        if (activeTab != null) {
                                            tabManager.updateTabMetadata(activeTab.id) {
                                                it.copy(url = url.orEmpty(), isLoading = true, favicon = favicon)
                                            }
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        val pageTitle = view?.title ?: "Web Page"
                                        if (activeTab != null) {
                                            tabManager.updateTabMetadata(activeTab.id) {
                                                it.copy(
                                                    title = pageTitle,
                                                    isLoading = false,
                                                    canGoBack = view?.canGoBack() ?: false,
                                                    canGoForward = view?.canGoForward() ?: false
                                                )
                                            }
                                        }

                                        // Persist history only in non-incognito tabs
                                        if (!isGhostActiveState.value && activeTab?.isIncognito != true && !url.isNullOrBlank()) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                database.browserDao().insertHistory(
                                                    HistoryEntity(url = url, title = pageTitle)
                                                )
                                            }
                                        }

                                        // Intelligent PDF Dark Mode Bypass
                                        applyDarkModeToWebView(view, isDarkModeState.value, isPdfUrl(url.orEmpty()))
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val requestUrl = request?.url?.toString() ?: return false
                                        if (!requestUrl.startsWith("http://") && !requestUrl.startsWith("https://")) {
                                            try {
                                                val customIntent = Intent(Intent.ACTION_VIEW, Uri.parse(requestUrl))
                                                ctx.startActivity(customIntent)
                                                return true
                                            } catch (_: Exception) {
                                                return false
                                            }
                                        }
                                        return false
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                        if (view == null) return
                                        customVideoViewCallback?.onCustomViewHidden()
                                        val decor = window.decorView as? ViewGroup ?: return
                                        customVideoView = view
                                        customVideoViewCallback = callback
                                        val container = FrameLayout(this@MainActivity).apply {
                                            setBackgroundColor(android.graphics.Color.BLACK)
                                            addView(view, FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            ))
                                        }
                                        customVideoContainer = container
                                        decor.addView(container, ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        ))
                                        container.bringToFront()
                                        window.decorView.systemUiVisibility = (
                                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        )
                                    }

                                    override fun onHideCustomView() {
                                        val decor = window.decorView as? ViewGroup
                                        customVideoContainer?.let { decor?.removeView(it) }
                                        customVideoContainer = null
                                        customVideoView = null
                                        customVideoViewCallback?.onCustomViewHidden()
                                        customVideoViewCallback = null
                                        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    }

                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        if (activeTab != null) {
                                            tabManager.updateTabMetadata(activeTab.id) { it.copy(progress = newProgress) }
                                        }
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank() && activeTab != null) {
                                            tabManager.updateTabMetadata(activeTab.id) { it.copy(title = title) }
                                        }
                                    }

                                    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                                        if (icon != null && activeTab != null) {
                                            tabManager.updateTabMetadata(activeTab.id) { it.copy(favicon = icon) }
                                        }
                                    }

                                    // HTML File Upload Handling (<input type="file">)
                                    override fun onShowFileChooser(
                                        webView: WebView?,
                                        filePathCallback: ValueCallback<Array<Uri>>?,
                                        fileChooserParams: FileChooserParams?
                                    ): Boolean {
                                        fileUploadCallback?.onReceiveValue(null)
                                        fileUploadCallback = filePathCallback

                                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                            type = "*/*"
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                        }
                                        try {
                                            filePickerLauncher.launch(intent)
                                            return true
                                        } catch (e: Exception) {
                                            fileUploadCallback = null
                                            return false
                                        }
                                    }
                                }

                                // Lightweight in-page media download: long-press a directly exposed video/audio URL.
                                // This intentionally does not pretend to extract protected or hidden streams.
                                setOnLongClickListener {
                                    val hit = hitTestResult
                                    val mediaUrl = hit.extra
                                    // Android's public HitTestResult API exposes link types, not
                                    // reliable inline-media element types across WebView versions.
                                    val mediaTypes = setOf(
                                        WebView.HitTestResult.SRC_ANCHOR_TYPE,
                                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                                    )
                                    if (hit.type in mediaTypes && !mediaUrl.isNullOrBlank() && URLUtil.isNetworkUrl(mediaUrl)) {
                                        val mediaName = URLUtil.guessFileName(mediaUrl, null, null)
                                        android.app.AlertDialog.Builder(ctx)
                                            .setTitle("Download media?")
                                            .setMessage("Download this media file?\\n$mediaName")
                                            .setNegativeButton("Cancel", null)
                                            .setPositiveButton("Download") { _, _ ->
                                                enqueueDownloadInBackground(mediaUrl, mediaName)
                                                showDownloadsSheetState.value = true
                                                Toast.makeText(ctx, "Added to downloads: $mediaName", Toast.LENGTH_SHORT).show()
                                            }
                                            .show()
                                        true
                                    } else {
                                        false
                                    }
                                }

                                // Universal Download Listener
                                setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                    enqueueDownloadInBackground(url, fileName)
                                    showDownloadsSheetState.value = true
                                    Toast.makeText(ctx, "Download started: $fileName", Toast.LENGTH_SHORT).show()
                                }

                            }
                        },
                        update = { view ->
                            if (activeTab != null) {
                                tabManager.registerLiveWebView(activeTab.id, view)
                            }
                            val restoredUrl = restoredUrlForFirstUpdate.value
                            if (restoredUrl != null && restoredUrl == currentUrl) {
                                // restoreState already restored this tab's navigation stack; do not
                                // immediately load the same URL again and erase its back/forward history.
                                restoredUrlForFirstUpdate.value = null
                            } else if (view.url != currentUrl && currentUrl.isNotEmpty()) {
                                restoredUrlForFirstUpdate.value = null
                                view.loadUrl(currentUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    }
                }
            }
        }

        // Sheets & Dialogs:
        // 1. Arafat AI Assistant Sheet / Standalone PWA
        if (showAiSheetState.value) {
            ArafatAiSheet(
                messages = aiMessages,
                isLoading = isAiLoading,
                currentWebpageTitle = activeTab?.title.orEmpty(),
                currentWebpageUrl = currentUrl,
                onSendMessage = { prompt ->
                    val historyBeforeMessage = aiMessagesState.value
                    val userMsg = ChatMessage(id = UUID.randomUUID().toString(), sender = MessageSender.USER, text = prompt)
                    appendAiMessage(userMsg)
                    isAiLoadingState.value = true

                    coroutineScope.launch {
                        val result = aiService.sendQuery(
                            userPrompt = prompt,
                            history = historyBeforeMessage,
                            customApiKey = customApiKey
                        )
                        isAiLoadingState.value = false
                        result.onSuccess { reply ->
                            val aiMsg = ChatMessage(id = UUID.randomUUID().toString(), sender = MessageSender.ARAFAT_AI, text = reply)
                            appendAiMessage(aiMsg)
                        }.onFailure { error ->
                            val errorMsg = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                sender = MessageSender.ARAFAT_AI,
                                text = "Arafat AI Error: ${error.localizedMessage}"
                            )
                            appendAiMessage(errorMsg)
                        }
                    }
                },
                onSummarizePage = {
                    val prompt = "Please provide an executive summary of the web page titled '${activeTab?.title}' at $currentUrl in bullet points."
                    val historyBeforeMessage = aiMessagesState.value
                    val userMsg = ChatMessage(id = UUID.randomUUID().toString(), sender = MessageSender.USER, text = prompt)
                    appendAiMessage(userMsg)
                    isAiLoadingState.value = true

                    coroutineScope.launch {
                        val result = aiService.sendQuery(prompt, historyBeforeMessage, customApiKey)
                        isAiLoadingState.value = false
                        result.onSuccess { reply ->
                            appendAiMessage(ChatMessage(id = UUID.randomUUID().toString(), sender = MessageSender.ARAFAT_AI, text = reply))
                        }.onFailure {
                            appendAiMessage(ChatMessage(id = UUID.randomUUID().toString(), sender = MessageSender.ARAFAT_AI, text = "Could not summarize: ${it.localizedMessage}"))
                        }
                    }
                },
                onPinPwaShortcut = {
                    val success = aiService.createStandalonePwaShortcut()
                    if (success) {
                        Toast.makeText(this@MainActivity, "Added Arafat AI Standalone PWA to Home Screen!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Shortcut pinning not supported on this launcher", Toast.LENGTH_SHORT).show()
                    }
                },
                onSaveApiKey = { newKey -> customApiKeyState.value = newKey },
                currentApiKey = customApiKey,
                onDismiss = {
                    if (isStandaloneAiState.value) {
                        finish()
                    } else {
                        showAiSheetState.value = false
                    }
                },
                isStandaloneMode = isStandaloneAiState.value
            )
        }

        // 2. Sniper Multi-Threaded Download Manager Sheet with Safe Auto-Updating Engine
        if (showDownloadsSheetState.value) {
            DownloadManagerSheet(
                downloads = downloads,
                initialUrl = sharedDownloadUrlState.value,
                engineState = engineState,
                onCheckEngineUpdate = {
                    coroutineScope.launch {
                        val res = engineManager.checkForEngineUpdates()
                        res.onSuccess { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(this@MainActivity, "Engine check: ${it.localizedMessage}", Toast.LENGTH_SHORT).show() }
                    }
                },
                onExtractMedia = { url ->
                    engineManager.extractMedia(url)
                },
                onStartDownload = { url, fileName ->
                    enqueueDownloadInBackground(url, fileName)
                    Toast.makeText(this@MainActivity, "Download added to queue: $fileName", Toast.LENGTH_SHORT).show()
                },
                onPause = { downloader.pauseDownload(it) },
                onResume = { id ->
                    downloader.resumeDownload(id)
                    DownloadForegroundService.start(this@MainActivity)
                },
                onCancel = { downloader.cancelDownload(it) },
                onDismiss = {
                    showDownloadsSheetState.value = false
                    sharedDownloadUrlState.value = null
                }
            )
        }

        // 3. Tabs Manager Sheet with Genuine Tab Hibernation
        if (showTabsSheetState.value) {
            BrowserTabsSheet(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelectTab = { selectedId ->
                    tabManager.selectTab(selectedId)
                    showTabsSheetState.value = false
                },
                onCloseTab = { closeId ->
                    tabManager.closeTab(closeId)
                },
                onNewTab = { isIncognito ->
                    tabManager.openNewTab(isIncognito = isIncognito)
                    showTabsSheetState.value = false
                },
                onHibernateBackgroundTabs = {
                    tabManager.hibernateAllBackgroundTabs()
                    Toast.makeText(this@MainActivity, "Background tabs frozen & RAM freed!", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showTabsSheetState.value = false }
            )
        }

        // 4. Bookmarks & History Sheet
        if (showBookmarksHistoryState.value) {
            BookmarkHistorySheet(
                bookmarks = bookmarks,
                history = history,
                onSelectUrl = { selectedUrl ->
                    openNewUrlInActiveTab(selectedUrl)
                },
                onDeleteBookmark = { url ->
                    coroutineScope.launch { database.browserDao().deleteBookmarkByUrl(url) }
                },
                onDeleteHistoryItem = { id ->
                    coroutineScope.launch { database.browserDao().deleteHistoryById(id) }
                },
                onClearAllHistory = {
                    coroutineScope.launch {
                        database.browserDao().clearAllHistory()
                        Toast.makeText(this@MainActivity, "History cleared", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { showBookmarksHistoryState.value = false }
            )
        }

        // 5. Ghost Mode & Security Dialog
        if (showGhostDialogState.value) {
            GhostModeDialog(
                isGhostActive = isGhostActive,
                isFlagSecure = isFlagSecure,
                isShakeEnabled = isShakeEnabled,
                selectedUserAgent = userAgentOption,
                isPinProtected = ghostPinState.value != null,
                onToggleGhost = { enable ->
                    isGhostActiveState.value = enable
                    if (!enable) {
                        tabManager.cleanAllIncognitoTabs()
                        ghostSecurity.purgeBrowsingData(activeWebView())
                    }
                },
                onToggleFlagSecure = { enable ->
                    isFlagSecureState.value = enable
                    ghostSecurity.setFlagSecure(enable)
                },
                onToggleShake = { enable ->
                    isShakeEnabledState.value = enable
                    if (enable) ghostSecurity.startShakeListener() else ghostSecurity.stopShakeListener()
                },
                onSelectUserAgent = { newUa ->
                    userAgentState.value = newUa
                    activeWebView()?.settings?.userAgentString = resolveUserAgent(newUa)
                    activeWebView()?.reload()
                },
                onSetPin = { pin -> ghostPinState.value = pin },
                onPurgeData = {
                    ghostSecurity.purgeBrowsingData(activeWebView())
                    coroutineScope.launch { database.browserDao().clearAllHistory() }
                    tabManager.openNewTab("", isIncognito = false)
                },
                onDismiss = { showGhostDialogState.value = false }
            )
        }

        // 6. Reader Mode View
        if (showReaderModeState.value) {
            ReaderModeView(
                article = activeReaderArticleState.value,
                onDismiss = { showReaderModeState.value = false }
            )
        }
    }

    /**
     * Use the installed WebView's Chromium version for desktop requests instead of
     * shipping a permanently stale Chrome version string.
     */
    private fun resolveUserAgent(option: UserAgentOption): String? {
        if (option != UserAgentOption.CHROME_DESKTOP) return option.userAgentString
        val installedUa = WebSettings.getDefaultUserAgent(this)
        val chromeVersion = Regex("""Chrome/([0-9.]+)""")
            .find(installedUa)?.groupValues?.getOrNull(1) ?: "126.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings(webView: WebView, customUserAgent: String?) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Safe Browsing is supported by the installed Android System WebView on API 26+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            if (!customUserAgent.isNullOrBlank()) {
                userAgentString = customUserAgent
            }
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Intelligent PDF Dark Mode Bypass:
     * When PDF is loaded, dark mode styling is bypassed to preserve clean white background and dark text.
     */
    private fun applyDarkModeToWebView(webView: WebView?, isDark: Boolean, isPdf: Boolean) {
        if (webView == null) return

        if (isPdf) {
            val bypassPdfJs = """
                (function() {
                    var s = document.getElementById('bh-dark-mode-style');
                    if (s) s.remove();
                    document.documentElement.style.filter = 'none';
                    document.documentElement.style.backgroundColor = '#FFFFFF';
                })();
            """.trimIndent()
            webView.evaluateJavascript(bypassPdfJs, null)
            return
        }

        if (isDark) {
            val injectDarkJs = """
                (function() {
                    if (!document.getElementById('bh-dark-mode-style')) {
                        var style = document.createElement('style');
                        style.id = 'bh-dark-mode-style';
                        style.innerHTML = 'html { filter: invert(90%) hue-rotate(180deg) !important; background-color: #121212 !important; } img, video, canvas, iframe, svg { filter: invert(100%) hue-rotate(180deg) !important; }';
                        document.head.appendChild(style);
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(injectDarkJs, null)
        } else {
            val removeDarkJs = """
                (function() {
                    var s = document.getElementById('bh-dark-mode-style');
                    if (s) s.remove();
                    document.documentElement.style.filter = 'none';
                    document.documentElement.style.backgroundColor = '';
                })();
            """.trimIndent()
            webView.evaluateJavascript(removeDarkJs, null)
        }
    }

    private fun isPdfUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".pdf") ||
                lower.contains(".pdf?") ||
                lower.contains("/pdf/") ||
                lower.contains("notice/pdf") ||
                lower.contains("result/pdf")
    }

    private fun extractAndShowReaderMode(webView: WebView?, currentUrl: String) {
        if (webView == null || currentUrl.isBlank() || currentUrl == "about:blank") {
            Toast.makeText(this, "Navigate to an article to enable Reader Mode", Toast.LENGTH_SHORT).show()
            return
        }

        val readerJs = """
            (function() {
                var title = document.title || '';
                var h1 = document.querySelector('h1');
                if (h1 && h1.innerText) title = h1.innerText;
                var paragraphs = [];
                var article = document.querySelector('article') || document.querySelector('main') || document.body;
                var ps = article.querySelectorAll('p');
                for (var i = 0; i < ps.length; i++) {
                    var text = ps[i].innerText.trim();
                    if (text.length > 25) {
                        paragraphs.push(text);
                    }
                }
                return JSON.stringify({
                    title: title,
                    url: window.location.href,
                    domain: window.location.hostname,
                    paragraphs: paragraphs
                });
            })();
        """.trimIndent()

        webView.evaluateJavascript(readerJs) { jsonResult ->
            try {
                val cleanedJson = if (jsonResult != null && jsonResult.startsWith("\"") && jsonResult.endsWith("\"")) {
                    JSONObject(jsonResult.substring(1, jsonResult.length - 1).replace("\\\"", "\"").replace("\\\\", "\\"))
                } else {
                    JSONObject(jsonResult.orEmpty())
                }

                val title = cleanedJson.optString("title", "Article")
                val domain = cleanedJson.optString("domain", "")
                val pArray = cleanedJson.optJSONArray("paragraphs")
                val paragraphs = mutableListOf<String>()
                if (pArray != null) {
                    for (i in 0 until pArray.length()) {
                        paragraphs.add(pArray.getString(i))
                    }
                }

                activeReaderArticleState.value = ReaderArticle(
                    title = title,
                    url = currentUrl,
                    domain = domain,
                    paragraphs = paragraphs
                )
                showReaderModeState.value = true
            } catch (_: Exception) {
                activeReaderArticleState.value = ReaderArticle(
                    title = webView.title ?: "Article",
                    url = currentUrl,
                    domain = Uri.parse(currentUrl).host.orEmpty(),
                    paragraphs = listOf("Could not parse article text. Please ensure the page has loaded completely.")
                )
                showReaderModeState.value = true
            }
        }
    }

    private fun sanitizeQueryUrl(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else if (trimmed.contains(".") && !trimmed.contains(" ")) {
            "https://$trimmed"
        } else {
            "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
        }
    }
}

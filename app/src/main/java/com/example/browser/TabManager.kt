package com.example.browser

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebView
import com.example.model.BrowserTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TabManager(private val context: Context) {

    // Maximum concurrent live WebView instances allowed in memory (2 for 4GB RAM efficiency)
    private val maxLiveWebViews = 2

    private val _tabs = MutableStateFlow<List<BrowserTab>>(
        listOf(BrowserTab(id = UUID.randomUUID().toString(), title = "New Tab", url = ""))
    )
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(_tabs.value.first().id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    // Persistent in-memory bundles to restore hibernated WebView navigation states
    private val tabBundles = ConcurrentHashMap<String, Bundle>()

    // Live WebViews cache (bounded to maxLiveWebViews)
    private val liveWebViews = ConcurrentHashMap<String, WebView>()
    private val lastAccessedAtMs = ConcurrentHashMap<String, Long>()

    fun getActiveTab(): BrowserTab? {
        return _tabs.value.find { it.id == _activeTabId.value } ?: _tabs.value.firstOrNull()
    }

    /** Returns the WebView belonging to the selected tab, never a stale previous-tab view. */
    fun getLiveWebView(tabId: String): WebView? = liveWebViews[tabId]

    fun openNewTab(url: String = "", isIncognito: Boolean = false): String {
        val newId = UUID.randomUUID().toString()
        val newTab = BrowserTab(
            id = newId,
            title = if (isIncognito) "Ghost Tab" else if (url.isBlank()) "New Tab" else "Loading…",
            url = url,
            isIncognito = isIncognito
        )
        _tabs.update { it + newTab }
        selectTab(newId)
        return newId
    }

    fun selectTab(tabId: String) {
        val previousActiveId = _activeTabId.value
        if (previousActiveId != tabId) {
            // Save state of previous active tab if live
            val prevWebView = liveWebViews[previousActiveId]
            if (prevWebView != null) {
                val bundle = Bundle()
                prevWebView.saveState(bundle)
                tabBundles[previousActiveId] = bundle
                prevWebView.onPause()
            }
        }

        lastAccessedAtMs[tabId] = SystemClock.elapsedRealtime()
        _activeTabId.value = tabId

        // Mark selected tab as active/unhibernated
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) it.copy(isHibernated = false) else it
            }
        }

        // Apply aggressive memory reclamation: hibernate least recently used background tabs
        reclaimMemoryExceedingLimit()
    }

    fun closeTab(tabId: String) {
        // Destroy WebView and clean bundle
        liveWebViews[tabId]?.let { wv ->
            try {
                wv.stopLoading()
                wv.clearHistory()
                wv.destroy()
            } catch (_: Exception) {}
            liveWebViews.remove(tabId)
        }
        tabBundles.remove(tabId)
        lastAccessedAtMs.remove(tabId)

        val currentList = _tabs.value
        val updatedList = currentList.filterNot { it.id == tabId }

        if (updatedList.isEmpty()) {
            val freshId = UUID.randomUUID().toString()
            val freshTab = BrowserTab(id = freshId, title = "New Tab", url = "")
            _tabs.value = listOf(freshTab)
            _activeTabId.value = freshId
        } else {
            _tabs.value = updatedList
            if (_activeTabId.value == tabId) {
                _activeTabId.value = updatedList.last().id
            }
        }
    }

    /**
     * Reclaims native WebView memory for background tabs exceeding maxLiveWebViews.
     */
    fun reclaimMemoryExceedingLimit() {
        val activeId = _activeTabId.value
        val backgroundLiveTabIds = liveWebViews.keys.filter { it != activeId }
        val keepBackgroundCount = (maxLiveWebViews - 1).coerceAtLeast(0)
        val excessCount = (backgroundLiveTabIds.size - keepBackgroundCount).coerceAtLeast(0)
        val leastRecentlyUsed = backgroundLiveTabIds
            .sortedBy { lastAccessedAtMs[it] ?: Long.MIN_VALUE }
            .take(excessCount)

        leastRecentlyUsed.forEach(::hibernateTab)
    }

    /**
     * Genuine Hibernation: Saves WebView state into Bundle, tears down the WebView instance.
     */
    fun hibernateTab(tabId: String) {
        if (tabId == _activeTabId.value) return // Never hibernate currently active tab

        val webView = liveWebViews[tabId]
        if (webView != null) {
            val bundle = Bundle()
            webView.saveState(bundle)
            tabBundles[tabId] = bundle

            try {
                webView.stopLoading()
                // WebView.pauseTimers() is process-wide: calling it for one hibernated tab
                // can freeze JavaScript timers in the active tab too. Destroy this instance
                // without pausing timers globally.
                webView.destroy()
            } catch (_: Exception) {}
            liveWebViews.remove(tabId)

            _tabs.update { list ->
                list.map { if (it.id == tabId) it.copy(isHibernated = true) else it }
            }
        }
    }

    fun hibernateAllBackgroundTabs() {
        val activeId = _activeTabId.value
        _tabs.value.filter { it.id != activeId }.forEach {
            hibernateTab(it.id)
        }
        System.gc()
    }

    fun registerLiveWebView(tabId: String, webView: WebView) {
        // Compose may create a fresh WebView when a tab becomes active again. If a detached
        // instance for that same tab is still cached, replace and destroy it instead of leaking
        // an unreferenced native WebView alongside the restored one.
        lastAccessedAtMs[tabId] = SystemClock.elapsedRealtime()
        val previous = liveWebViews.put(tabId, webView)
        if (previous != null && previous !== webView) {
            try {
                previous.stopLoading()
                previous.destroy()
            } catch (_: Exception) {}
        }
        _tabs.update { list ->
            list.map { if (it.id == tabId) it.copy(isHibernated = false) else it }
        }
    }

    fun getSavedBundle(tabId: String): Bundle? {
        return tabBundles[tabId]
    }

    fun updateTabMetadata(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        _tabs.update { list ->
            list.map { if (it.id == tabId) transform(it) else it }
        }
    }

    /**
     * Explicitly release every WebView when the owning activity is destroyed.
     * Clearing the registry and saved bundles prevents the manager from retaining
     * native browser instances or session state after the browser exits.
     */
    fun destroyAllWebViews() {
        val views = liveWebViews.values.toList()
        liveWebViews.clear()
        views.forEach { webView ->
            try {
                webView.stopLoading()
                webView.clearHistory()
                webView.destroy()
            } catch (_: Exception) {}
        }
        tabBundles.clear()
        lastAccessedAtMs.clear()
    }

    fun cleanAllIncognitoTabs() {
        val incognitoTabIds = _tabs.value.filter { it.isIncognito }.map { it.id }
        incognitoTabIds.forEach { closeTab(it) }
    }
}

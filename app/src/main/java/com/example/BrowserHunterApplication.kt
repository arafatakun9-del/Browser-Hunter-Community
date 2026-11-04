package com.example

import android.app.Application
import com.example.download.MultiThreadDownloader

/**
 * Process-wide download queue shared by the browser Activity and its foreground service.
 * The service is active only while user-started transfers are running.
 */
class BrowserHunterApplication : Application() {
    val downloader: MultiThreadDownloader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MultiThreadDownloader(applicationContext)
    }
}

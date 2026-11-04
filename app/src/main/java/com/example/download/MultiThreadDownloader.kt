package com.example.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.example.model.DownloadChunk
import com.example.model.DownloadItem
import com.example.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class HttpContentRange(
    val startByte: Long,
    val endByte: Long,
    val totalBytes: Long
)

/** Parses only a numeric, internally consistent HTTP byte Content-Range header. */
internal fun parseHttpContentRange(value: String?): HttpContentRange? {
    val header = value?.trim() ?: return null
    val match = Regex("""^bytes\s+(\d+)-(\d+)/(\d+)$""", RegexOption.IGNORE_CASE)
        .matchEntire(header) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (start < 0L || end < start || total <= end) return null
    return HttpContentRange(start, end, total)
}

class MultiThreadDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        // Never allow an HTTPS download to be silently downgraded to cleartext HTTP.
        .followSslRedirects(false)
        .build()

    private val stateStore = EncryptedDownloadStateStore(context)
    private val persistLock = Any()
    private val persistMutex = Mutex()
    // Keep at most two files transferring at once; additional downloads remain queued.
    private val downloadSlots = Semaphore(2)
    private var lastPersistAtMs = 0L
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeCalls = ConcurrentHashMap<String, MutableSet<Call>>()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    init {
        // Recover the encrypted queue after process death. Active transfers become paused;
        // progress is reconciled from the actual partial files rather than stale UI snapshots.
        _downloads.value = stateStore.load().map(::recoverInterruptedItem)
    }

    private fun recoverInterruptedItem(item: DownloadItem): DownloadItem {
        val directory = getDownloadDirectory()
        val singleStreamFile = File(directory, "${item.id}_single.tmp")
        val chunkFiles = directory.listFiles()
            ?.filter { it.name.startsWith("${item.id}_part_") && it.name.endsWith(".tmp") }
            .orEmpty()

        val bytesOnDisk = when {
            singleStreamFile.isFile -> singleStreamFile.length()
            chunkFiles.isNotEmpty() -> chunkFiles.sumOf { it.length().coerceAtLeast(0L) }
            else -> item.downloadedBytes
        }
        val recoveredBytes = if (item.totalBytes > 0L) {
            bytesOnDisk.coerceIn(0L, item.totalBytes)
        } else {
            bytesOnDisk.coerceAtLeast(0L)
        }
        val recoveredProgress = if (item.totalBytes > 0L) {
            ((recoveredBytes.toDouble() / item.totalBytes.toDouble()) * 100.0)
                .toInt().coerceIn(0, 99)
        } else {
            0
        }
        val recoveredStatus = when (item.status) {
            DownloadStatus.CONNECTING,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PENDING -> DownloadStatus.PAUSED
            else -> item.status
        }

        return item.copy(
            downloadedBytes = recoveredBytes,
            progressPercent = if (recoveredStatus == DownloadStatus.COMPLETED) 100 else recoveredProgress,
            status = recoveredStatus,
            speedBps = 0L
        )
    }

    fun enqueueDownload(
        url: String,
        suggestedFileName: String? = null,
        threadCount: Int = 4
    ): String {
        val id = UUID.randomUUID().toString()
        val rawName = suggestedFileName ?: extractFileNameFromUrl(url)
        val initialItem = DownloadItem(
            id = id,
            originalUrl = url,
            downloadUrl = url,
            fileName = sanitizeFileName(rawName),
            mimeType = guessMimeType(rawName),
            threadCount = threadCount.coerceIn(1, 4),
            status = DownloadStatus.PENDING
        )
        _downloads.update { listOf(initialItem) + it }
        persistDownloadState(force = true)
        startDownloadJob(id)
        return id
    }

    fun pauseDownload(id: String) {
        activeJobs[id]?.cancel()
        cancelActiveCalls(id)
        activeJobs.remove(id)
        updateItem(id) { it.copy(status = DownloadStatus.PAUSED, speedBps = 0L) }
        persistDownloadState(force = true)
    }

    /**
     * Pause every active transfer in one state update. Used when Android limits the
     * dataSync foreground service, so partial files remain available for later resume.
     */
    fun pauseAllActiveDownloads() {
        val activeIds = _downloads.value
            .filter {
                it.status == DownloadStatus.PENDING ||
                    it.status == DownloadStatus.CONNECTING ||
                    it.status == DownloadStatus.DOWNLOADING
            }
            .mapTo(HashSet()) { it.id }
        if (activeIds.isEmpty()) return

        activeIds.forEach { id ->
            activeJobs.remove(id)?.cancel()
            cancelActiveCalls(id)
        }
        _downloads.update { items ->
            items.map { item ->
                if (item.id in activeIds) item.copy(status = DownloadStatus.PAUSED, speedBps = 0L)
                else item
            }
        }
        persistDownloadState(force = true)
    }

    fun resumeDownload(id: String) {
        val item = _downloads.value.firstOrNull { it.id == id } ?: return
        if (item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.FAILED) {
            updateItem(id) { it.copy(status = DownloadStatus.PENDING, errorMessage = null) }
            persistDownloadState(force = true)
            startDownloadJob(id)
        }
    }

    fun cancelDownload(id: String) {
        activeJobs[id]?.cancel()
        cancelActiveCalls(id)
        activeJobs.remove(id)
        _downloads.update { list -> list.filterNot { it.id == id } }
        persistDownloadState(force = true)
        // Clean up partial chunk files
        try {
            val downloadDir = getDownloadDirectory()
            downloadDir.listFiles()?.filter { it.name.startsWith(id) }?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    private fun startDownloadJob(id: String) {
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Waiting jobs stay PENDING and are picked up as soon as a slot is free.
                downloadSlots.withPermit {
                updateItem(id) { it.copy(status = DownloadStatus.CONNECTING) }
                val currentItem = _downloads.value.firstOrNull { it.id == id } ?: return@launch
                val targetUrl = currentItem.downloadUrl

                // 1. Probe URL metadata with HEAD or GET range
                var contentLength = -1L
                var acceptsRanges = false
                var resolvedName = currentItem.fileName

                try {
                    val headRequest = Request.Builder()
                        .url(targetUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) BrowserHunter/2026")
                        .head()
                        .build()

                    withTrackedCall(id, headRequest) { call ->
                        call.execute().use { response ->
                        if (response.isSuccessful) {
                            contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                            acceptsRanges = response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                            val contentDisposition = response.header("Content-Disposition")
                            if (!contentDisposition.isNullOrBlank()) {
                                parseContentDispositionFileName(contentDisposition)?.let {
                                    resolvedName = sanitizeFileName(it)
                                }
                            }
                        }
                        }
                    }
                } catch (_: Exception) {
                    // Fall back to direct GET
                }
                currentCoroutineContext().ensureActive()

                // Verify range support with an actual 0-1 byte request. Some servers advertise
                // Accept-Ranges but still ignore Range requests, which would corrupt chunk merges.
                try {
                    val probeGet = Request.Builder()
                        .url(targetUrl)
                        .header("Range", "bytes=0-1")
                        .get()
                        .build()
                    withTrackedCall(id, probeGet) { call ->
                        call.execute().use { res ->
                        val contentRange = res.header("Content-Range")
                        val total = contentRange?.substringAfterLast("/")?.toLongOrNull()
                        acceptsRanges = res.code == 206 &&
                            contentRange?.startsWith("bytes 0-") == true &&
                            total != null && total > 0
                        if (acceptsRanges) {
                            // A real 206 Content-Range probe is more reliable than a possibly stale HEAD length.
                            contentLength = total!!
                        }
                        }
                    }
                } catch (_: Exception) {
                    acceptsRanges = false
                }
                currentCoroutineContext().ensureActive()

                val downloadDir = getDownloadDirectory()
                // Keep each in-progress transfer isolated even when two downloads share a filename.
                val finalFile = File(downloadDir, "${id}_${resolvedName}")

                updateItem(id) {
                    it.copy(
                        fileName = resolvedName,
                        totalBytes = contentLength,
                        status = DownloadStatus.DOWNLOADING
                    )
                }

                // If content length unknown or range not supported, do single-stream direct download
                if (contentLength <= 0L || !acceptsRanges) {
                    downloadSingleStream(id, targetUrl, finalFile)
                } else {
                    downloadMultiThreaded(id, targetUrl, contentLength, finalFile, currentItem.threadCount)
                }
                }
            } catch (e: Exception) {
                if (isActive) {
                    updateItem(id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            speedBps = 0L,
                            errorMessage = e.localizedMessage ?: "Download failed"
                        )
                    }
                }
            } finally {
                activeJobs.remove(id, job)
            }
        }
        activeJobs[id] = job
        job.start()
    }

    private suspend fun downloadSingleStream(id: String, url: String, targetFile: File) {
        // This is the actual transfer mode; the UI must not keep claiming multiple streams
        // after the server rejects ranges or the download falls back to one connection.
        updateItem(id) { it.copy(threadCount = 1, status = DownloadStatus.DOWNLOADING, speedBps = 0L) }
        val tempFile = File(targetFile.parentFile, "${id}_single.tmp")
        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) BrowserHunter/2026")
        if (existingBytes > 0L) requestBuilder.header("Range", "bytes=${existingBytes}-")

        val call = newTrackedCall(id, requestBuilder.build())
        try {
            call.execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 416 && existingBytes > 0L) {
                    tempFile.delete()
                    throw Exception("Partial download expired; retry to restart")
                }
                throw Exception("HTTP ${response.code}")
            }
            val body = response.body ?: throw Exception("Empty response body")

            // A 206 response must describe the entire remaining byte interval. Otherwise a
            // truncated/partial response could be mistaken for a completed file when its total
            // length is absent or malformed. If Range is ignored with HTTP 200, restart cleanly.
            val bodyLength = body.contentLength()
            val parsedRange = if (response.code == 206) {
                parseHttpContentRange(response.header("Content-Range"))
            } else {
                null
            }
            if (response.code == 206) {
                val expectedBodyLength = parsedRange?.let { it.endByte - it.startByte + 1L }
                if (parsedRange == null ||
                    parsedRange.startByte != existingBytes ||
                    parsedRange.endByte != parsedRange.totalBytes - 1L ||
                    (bodyLength >= 0L && bodyLength != expectedBodyLength)
                ) {
                    tempFile.delete()
                    throw IOException("Server returned an incomplete or invalid byte range; retry to restart")
                }
            }
            val append = existingBytes > 0L && response.code == 206
            val initialBytes = if (append) existingBytes else 0L
            val total = parsedRange?.totalBytes ?: if (bodyLength >= 0L) initialBytes + bodyLength else -1L
            val buffer = ByteArray(32 * 1024)
            var downloaded = initialBytes
            var lastTime = System.currentTimeMillis()
            var bytesSinceLastSpeed = 0L

            val transferContext = currentCoroutineContext()
            body.byteStream().use { input ->
                FileOutputStream(tempFile, append).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        transferContext.ensureActive()
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        bytesSinceLastSpeed += bytesRead

                        val now = System.currentTimeMillis()
                        val diff = now - lastTime
                        if (diff >= 500) {
                            val speed = (bytesSinceLastSpeed * 1000) / diff
                            val progress = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 99) else 0
                            updateItem(id) {
                                it.copy(
                                    downloadedBytes = downloaded,
                                    totalBytes = if (total > 0) total else it.totalBytes,
                                    speedBps = speed,
                                    progressPercent = progress
                                )
                            }
                            lastTime = now
                            bytesSinceLastSpeed = 0L
                        }
                    }
                }
            }

            transferContext.ensureActive()
            if (total > 0L && downloaded != total) {
                throw IOException("Download ended early: expected $total bytes, received $downloaded")
            }

            if (tempFile.renameTo(targetFile) || copyFile(tempFile, targetFile)) {
                tempFile.delete()
                transferContext.ensureActive()
                val finalSize = targetFile.length()
                val savedLocation = publishCompletedFile(id, targetFile, targetFile.name.removePrefix("${id}_"))
                transferContext.ensureActive()
                if (savedLocation.startsWith("content://")) targetFile.delete()
                updateItem(id) {
                    it.copy(
                        downloadedBytes = finalSize,
                        totalBytes = finalSize,
                        progressPercent = 100,
                        status = DownloadStatus.COMPLETED,
                        speedBps = 0L,
                        localFilePath = savedLocation
                    )
                }
            } else {
                throw Exception("Could not save final file")
            }
            }
        } finally {
            releaseTrackedCall(id, call)
        }
    }

    private suspend fun downloadMultiThreaded(
        id: String,
        url: String,
        totalBytes: Long,
        targetFile: File,
        threads: Int
    ) {
        // Very small files cannot be split into the requested number of non-empty chunks.
        if (totalBytes < 2L) {
            downloadSingleStream(id, url, targetFile)
            return
        }
        val threadCount = minOf(threads.coerceIn(2, 4), totalBytes.coerceAtMost(4L).toInt())
        updateItem(id) { it.copy(threadCount = threadCount, status = DownloadStatus.DOWNLOADING) }
        val chunkSize = totalBytes / threadCount
        val chunks = (0 until threadCount).map { i ->
            val start = i * chunkSize
            val end = if (i == threadCount - 1) totalBytes - 1 else (start + chunkSize - 1)
            DownloadChunk(chunkIndex = i, startByte = start, endByte = end)
        }

        val downloadedTracker = AtomicLong(0L)
        val speedTracker = AtomicLong(0L)
        var lastTime = System.currentTimeMillis()

        // Launch speed monitor job in parallel
        val monitorJob = scope.launch {
            while (isActive) {
                delay(600)
                val now = System.currentTimeMillis()
                val diff = (now - lastTime).coerceAtLeast(1)
                val bytesInWindow = speedTracker.getAndSet(0L)
                val currentSpeed = (bytesInWindow * 1000) / diff
                val currentDownloaded = downloadedTracker.get()
                val pct = ((currentDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)

                updateItem(id) {
                    it.copy(
                        downloadedBytes = currentDownloaded,
                        speedBps = currentSpeed,
                        progressPercent = pct
                    )
                }
                lastTime = now
            }
        }

        try {
            // Use structured concurrency so a failed range request cancels sibling chunks.
            coroutineScope {
                chunks.map { chunk ->
                    async(Dispatchers.IO) {
                        downloadChunkPart(id, url, chunk, totalBytes, downloadedTracker, speedTracker)
                    }
                }.awaitAll()
            }
            monitorJob.cancel()

            // Merge chunks into targetFile
            updateItem(id) { it.copy(status = DownloadStatus.DOWNLOADING, speedBps = 0L) }
            mergeChunks(id, chunks, targetFile)
            val finalSize = targetFile.length()
            val savedLocation = publishCompletedFile(id, targetFile, targetFile.name.removePrefix("${id}_"))
            if (savedLocation.startsWith("content://")) targetFile.delete()

            updateItem(id) {
                it.copy(
                    downloadedBytes = finalSize,
                    totalBytes = finalSize,
                    progressPercent = 100,
                    status = DownloadStatus.COMPLETED,
                    speedBps = 0L,
                    localFilePath = savedLocation
                )
            }
        } catch (_: RangeNotSupportedException) {
            // A server may ignore or alter Range requests. Discard partial chunks and retry
            // with a single stream instead of merging corrupt data.
            getDownloadDirectory().listFiles()
                ?.filter { it.name.startsWith("${id}_part_") && it.name.endsWith(".tmp") }
                ?.forEach { it.delete() }
            updateItem(id) {
                it.copy(
                    downloadedBytes = 0L,
                    progressPercent = 0,
                    speedBps = 0L,
                    status = DownloadStatus.DOWNLOADING
                )
            }
            downloadSingleStream(id, url, targetFile)
        } finally {
            monitorJob.cancel()
        }
    }

    private class RangeNotSupportedException : Exception()

    private suspend fun downloadChunkPart(
        id: String,
        url: String,
        chunk: DownloadChunk,
        expectedTotalBytes: Long,
        downloadedTracker: AtomicLong,
        speedTracker: AtomicLong
    ) {
        val chunkFile = File(getDownloadDirectory(), "${id}_part_${chunk.chunkIndex}.tmp")
        val expectedChunkLength = chunk.endByte - chunk.startByte + 1L
        var existingBytes = if (chunkFile.exists()) chunkFile.length() else 0L

        if (existingBytes > expectedChunkLength) {
            // Discard an oversized stale partial instead of treating it as a complete chunk.
            chunkFile.delete()
            existingBytes = 0L
        }
        if (existingBytes == expectedChunkLength) {
            // Already downloaded and exactly the expected length.
            downloadedTracker.addAndGet(existingBytes)
            chunk.downloadedBytes = existingBytes
            chunk.isFinished = true
            return
        }

        val rangeStart = chunk.startByte + existingBytes
        val rangeEnd = chunk.endByte

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$rangeStart-$rangeEnd")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) BrowserHunter/2026")
            .build()

        withTrackedCall(id, request) { call ->
            call.execute().use { response ->
            // A range request must return 206 Partial Content and the expected byte range.
            // Accepting HTTP 200 here would write the entire file into each chunk.
            if (response.code == 200 || response.code == 416) {
                throw RangeNotSupportedException()
            }
            if (response.code != 206) {
                throw Exception("Chunk ${chunk.chunkIndex} failed with HTTP ${response.code}")
            }
            val expectedRangePrefix = "bytes ${rangeStart}-${rangeEnd}/"
            val contentRange = response.header("Content-Range")
            val totalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
            if (contentRange?.startsWith(expectedRangePrefix) != true || totalFromRange != expectedTotalBytes || totalFromRange <= rangeEnd) {
                throw RangeNotSupportedException()
            }
            val body = response.body ?: throw Exception("Empty chunk response")

            val buffer = ByteArray(16 * 1024)
            val expectedBytes = rangeEnd - rangeStart + 1L
            val transferContext = currentCoroutineContext()
            var bytesWritten = 0L
            val raf = RandomAccessFile(chunkFile, "rw")
            try {
                raf.seek(existingBytes)
                downloadedTracker.addAndGet(existingBytes)
                chunk.downloadedBytes = existingBytes

                body.byteStream().use { input ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        transferContext.ensureActive()
                        raf.write(buffer, 0, bytesRead)
                        downloadedTracker.addAndGet(bytesRead.toLong())
                        speedTracker.addAndGet(bytesRead.toLong())
                        chunk.downloadedBytes += bytesRead
                        bytesWritten += bytesRead
                    }
                }
                if (bytesWritten != expectedBytes) {
                    throw java.io.IOException("Chunk ${chunk.chunkIndex} ended early; expected $expectedBytes bytes, received $bytesWritten")
                }
            } finally {
                raf.close()
            }
            chunk.isFinished = true
            }
        }
    }

    private suspend fun mergeChunks(id: String, chunks: List<DownloadChunk>, targetFile: File) {
        val transferContext = currentCoroutineContext()
        val orderedChunks = chunks.sortedBy { it.chunkIndex }
        val expectedTotalBytes = orderedChunks.sumOf { it.endByte - it.startByte + 1L }

        // Validate every part before merging or deleting any resumable partials.
        orderedChunks.forEach { chunk ->
            transferContext.ensureActive()
            val chunkFile = File(getDownloadDirectory(), "${id}_part_${chunk.chunkIndex}.tmp")
            val expectedLength = chunk.endByte - chunk.startByte + 1L
            if (!chunkFile.isFile || chunkFile.length() != expectedLength) {
                throw IOException("Chunk ${chunk.chunkIndex} is incomplete; expected $expectedLength bytes")
            }
        }

        transferContext.ensureActive()
        if (targetFile.exists() && !targetFile.delete()) {
            throw IOException("Could not replace the temporary merged file")
        }

        // Copy in bounded blocks so Pause/Cancel remains responsive even for multi-GB files.
        val buffer = ByteArray(64 * 1024)
        FileOutputStream(targetFile).use { output ->
            orderedChunks.forEach { chunk ->
                transferContext.ensureActive()
                val chunkFile = File(getDownloadDirectory(), "${id}_part_${chunk.chunkIndex}.tmp")
                FileInputStream(chunkFile).use { input ->
                    while (true) {
                        transferContext.ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
            output.fd.sync()
        }

        transferContext.ensureActive()
        if (targetFile.length() != expectedTotalBytes) {
            targetFile.delete()
            throw IOException("Merged file size mismatch; expected $expectedTotalBytes bytes")
        }
        // Keep all chunk files until the merged output is verified and the coroutine is active.
        transferContext.ensureActive()
        orderedChunks.forEach { chunk ->
            File(getDownloadDirectory(), "${id}_part_${chunk.chunkIndex}.tmp").delete()
        }
    }

    private fun copyFile(src: File, dst: File): Boolean {
        return try {
            FileInputStream(src).use { inStream ->
                FileOutputStream(dst).use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun newTrackedCall(id: String, request: Request): Call {
        val call = client.newCall(request)
        activeCalls.computeIfAbsent(id) { ConcurrentHashMap.newKeySet<Call>() }.add(call)
        return call
    }

    private inline fun <T> withTrackedCall(id: String, request: Request, block: (Call) -> T): T {
        val call = newTrackedCall(id, request)
        return try {
            block(call)
        } finally {
            releaseTrackedCall(id, call)
        }
    }

    private fun releaseTrackedCall(id: String, call: Call) {
        val calls = activeCalls[id] ?: return
        calls.remove(call)
        if (calls.isEmpty()) activeCalls.remove(id, calls)
    }

    private fun cancelActiveCalls(id: String) {
        activeCalls[id]?.toList()?.forEach { it.cancel() }
    }

    private fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        var forceSave = false
        _downloads.update { list ->
            list.map { item ->
                if (item.id != id) item else {
                    val updated = transform(item)
                    if (updated.status != item.status && updated.status != DownloadStatus.CONNECTING && updated.status != DownloadStatus.DOWNLOADING) {
                        forceSave = true
                    }
                    updated
                }
            }
        }
        // Progress is persisted at most once every three seconds to avoid frequent flash writes.
        // Terminal/status transitions are saved immediately.
        persistDownloadState(force = forceSave)
    }

    private fun persistDownloadState(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        synchronized(persistLock) {
            if (!force && now - lastPersistAtMs < 3_000L) return
            lastPersistAtMs = now
        }
        scope.launch(Dispatchers.IO) {
            // Read the latest snapshot on the IO thread so queued writes cannot restore stale state.
            persistMutex.withLock { stateStore.save(_downloads.value) }
        }
    }

    /**
     * Publish completed files to the user's public Downloads collection on Android 10+.
     * On older Android versions, retain the app-specific file path rather than requesting
     * broad legacy storage permissions from the browser.
     */
    private fun publishCompletedFile(id: String, source: File, fileName: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return source.absolutePath

        val mimeType = guessMimeType(fileName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Browser Hunter")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a file in public Downloads")

        try {
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open the public Downloads file")
            FileInputStream(source).use { input ->
                output.use { destination -> input.copyTo(destination) }
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, publishValues, null, null)
            return uri.toString()
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw IOException("Could not publish the completed file to public Downloads: ${e.localizedMessage ?: "storage error"}", e)
        }
    }

    private fun getDownloadDirectory(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun extractFileNameFromUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val path = uri.lastPathSegment
            if (!path.isNullOrBlank() && path.contains(".")) {
                path
            } else {
                "download_${System.currentTimeMillis()}.bin"
            }
        } catch (_: Exception) {
            "download_${System.currentTimeMillis()}.bin"
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(64)
    }

    private fun parseContentDispositionFileName(cd: String): String? {
        val match = Regex("""filename\*?=['"]?(?:UTF-8'')?([^'";\n]+)['"]?""", RegexOption.IGNORE_CASE).find(cd)
        return match?.groupValues?.get(1)
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
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
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}

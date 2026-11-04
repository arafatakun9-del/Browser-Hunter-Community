package com.example.download

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.example.model.DownloadItem
import com.example.model.DownloadStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores download metadata (including signed URLs) encrypted with an Android Keystore key. */
internal class EncryptedDownloadStateStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "browser_hunter_downloads.enc"))

    @Synchronized
    fun save(items: List<DownloadItem>) {
        var output: java.io.FileOutputStream? = null
        try {
            val array = JSONArray()
            items.forEach { item ->
                array.put(JSONObject().apply {
                    put("id", item.id)
                    put("originalUrl", item.originalUrl)
                    put("downloadUrl", item.downloadUrl)
                    put("fileName", item.fileName)
                    put("mimeType", item.mimeType)
                    put("totalBytes", item.totalBytes)
                    put("downloadedBytes", item.downloadedBytes)
                    put("status", item.status.name)
                    put("threadCount", item.threadCount)
                    put("progressPercent", item.progressPercent)
                    put("localFilePath", item.localFilePath ?: JSONObject.NULL)
                    put("errorMessage", item.errorMessage ?: JSONObject.NULL)
                    put("timestamp", item.timestamp)
                })
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(array.toString().toByteArray(StandardCharsets.UTF_8))
            val iv = cipher.iv
            output = file.startWrite()
            output.write(iv.size)
            output.write(iv)
            output.write(encrypted)
            file.finishWrite(output)
            output = null
        } catch (_: Exception) {
            output?.let { file.failWrite(it) }
        }
    }

    @Synchronized
    fun load(): List<DownloadItem> {
        return try {
            val bytes = file.openRead().use { it.readBytes() }
            if (bytes.size < 13) return emptyList()
            val ivSize = bytes[0].toInt() and 0xff
            if (ivSize !in 12..16 || bytes.size <= 1 + ivSize) return emptyList()
            val iv = bytes.copyOfRange(1, 1 + ivSize)
            val encrypted = bytes.copyOfRange(1 + ivSize, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val json = JSONArray(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.optJSONObject(index) ?: continue
                    val status = runCatching { DownloadStatus.valueOf(item.optString("status")) }
                        .getOrDefault(DownloadStatus.PAUSED)
                    add(DownloadItem(
                        id = item.optString("id"),
                        originalUrl = item.optString("originalUrl"),
                        downloadUrl = item.optString("downloadUrl"),
                        fileName = item.optString("fileName"),
                        mimeType = item.optString("mimeType", "application/octet-stream"),
                        totalBytes = item.optLong("totalBytes", -1L),
                        downloadedBytes = item.optLong("downloadedBytes", 0L),
                        status = status,
                        threadCount = item.optInt("threadCount", 1).coerceIn(1, 4),
                        progressPercent = item.optInt("progressPercent", 0).coerceIn(0, 100),
                        localFilePath = item.optString("localFilePath").takeUnless { it.isBlank() || it == "null" },
                        errorMessage = item.optString("errorMessage").takeUnless { it.isBlank() || it == "null" },
                        timestamp = item.optLong("timestamp", System.currentTimeMillis())
                    ))
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            emptyList()
        } catch (_: Exception) {
            // Keep an unreadable encrypted snapshot untouched for diagnosis; never expose its URLs.
            emptyList()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "browser_hunter_download_state_v1"
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

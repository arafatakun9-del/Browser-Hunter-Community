package com.example.ai

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.model.ChatMessage
import com.example.model.MessageSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class ArafatAiService(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val systemPersona = """
        You are Arafat AI, an ultra-smart, loyal, and fast personal AI companion embedded directly into Browser Hunter web browser.
        Your avatar is Arafat, the creator and architect of Browser Hunter.
        Always communicate with high intelligence, clarity, and respect.
        Never identify yourself as Gemini, Google, or any third-party model.
        When asked who you are, state that you are Arafat AI, the dedicated personal intelligence of Browser Hunter.
        You can converse fluently in both English and Bengali (বাংলা), explain complex web pages, write code, formulate answers, and summarize research.
    """.trimIndent()

    suspend fun sendQuery(
        userPrompt: String,
        history: List<ChatMessage>,
        customApiKey: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val key = customApiKey?.takeIf { it.isNotBlank() }
                ?: BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }

            if (key.isNullOrBlank()) {
                return@withContext Result.failure(
                    Exception("Arafat AI needs an API key. Open AI settings to add your key.")
                )
            }

            // Build payload containing ONLY user prompt and conversation history (Privacy Shield: zero device metadata)
            val contentsArray = JSONArray()

            // Include past 6 turns for context
            val recentHistory = history.takeLast(6)
            for (msg in recentHistory) {
                val role = if (msg.sender == MessageSender.USER) "user" else "model"
                val partObj = JSONObject().put("text", msg.text)
                val turnObj = JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(partObj))
                contentsArray.put(turnObj)
            }

            // Add the current prompt
            val currentTurn = JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            contentsArray.put(currentTurn)

            val payload = JSONObject().apply {
                put("contents", contentsArray)
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPersona))))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 1200)
                })
            }

            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=$key"

            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // Keep provider-specific names and raw backend diagnostics out of the user-facing UI.
                    val safeMessage = when (response.code) {
                        400 -> "The request was rejected. Check your prompt and API settings."
                        401, 403 -> "The API key was rejected or does not have access. Check your AI settings."
                        404 -> "The selected AI model is unavailable for this API key or project."
                        408, 504 -> "Arafat AI timed out. Please try again."
                        429 -> "Arafat AI is temporarily rate-limited. Please wait and try again."
                        in 500..599 -> "The AI service is temporarily unavailable. Please try again later."
                        else -> "Arafat AI could not complete the request (error ${response.code})."
                    }
                    return@withContext Result.failure(Exception(safeMessage))
                }

                val jsonResponse = JSONObject(bodyString)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val reply = parts.getJSONObject(0).optString("text")
                        return@withContext Result.success(reply.trim())
                    }
                }
                Result.failure(Exception("No response text received from Arafat AI."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure(Exception("Arafat AI could not connect. Check your internet connection and try again."))
        }
    }

    /**
     * Standalone PWA Engine:
     * Pins the Browser Hunter AI shortcut with the neutral project icon.
     * When tapped from home screen, launches into standalone full-screen native app experience!
     */
    fun createStandalonePwaShortcut(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported) {
                val intent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(EXTRA_STANDALONE_AI, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

                val icon = Icon.createWithResource(context, R.drawable.ic_launcher_foreground)

                val pinShortcutInfo = ShortcutInfo.Builder(context, "arafat_ai_standalone_pwa")
                    .setShortLabel("Arafat AI")
                    .setLongLabel("Arafat AI Standalone")
                    .setIcon(icon)
                    .setIntent(intent)
                    .build()

                shortcutManager.requestPinShortcut(pinShortcutInfo, null)
                return true
            }
        }
        return false
    }

    companion object {
        const val EXTRA_STANDALONE_AI = "com.example.browserhunter.STANDALONE_AI"
    }
}

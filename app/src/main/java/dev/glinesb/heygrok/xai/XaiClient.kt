package dev.glinesb.heygrok.xai

import dev.glinesb.heygrok.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal xAI chat-completions client (OpenAI-compatible endpoint).
 * STUB-ish: no streaming, no retries, no conversation history.
 * API key comes from XAI_API_KEY in local.properties — never commit it.
 * For a shipped app, proxy through your own backend instead of embedding a key.
 */
class XaiClient(
    private val apiKey: String = BuildConfig.XAI_API_KEY,
    private val model: String = "grok-4",
) {
    suspend fun ask(question: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "(No XAI_API_KEY set in local.properties.)"
        val conn = (URL("https://api.x.ai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return@withContext "xAI error $code: $text"
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } catch (e: Exception) {
            "Request failed: ${e.message}"
        } finally {
            conn.disconnect()
        }
    }
}

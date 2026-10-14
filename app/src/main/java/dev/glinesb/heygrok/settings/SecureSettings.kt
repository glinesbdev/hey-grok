package dev.glinesb.heygrok.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * User-provided xAI API key + voice preference, encrypted at rest with
 * EncryptedSharedPreferences (AES-256-GCM values, key wrapped by an Android Keystore master key).
 * The key never goes into BuildConfig/local.properties and must never be logged.
 */
class SecureSettings(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().apply { if (v.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, v.trim()) }.apply()

    val hasApiKey: Boolean get() = apiKey != null

    /** Skip handing off to Grok / Grok Bot and always answer on our own result screen. */
    var alwaysAnswerInApp: Boolean
        get() = prefs.getBoolean(KEY_IN_APP, false)
        set(v) = prefs.edit().putBoolean(KEY_IN_APP, v).apply()

    var voice: String
        get() = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(v) = prefs.edit().putString(KEY_VOICE, v).apply()

    companion object {
        private const val FILE = "secure_settings"
        private const val KEY_API = "xai_api_key"
        private const val KEY_VOICE = "voice"
        private const val KEY_IN_APP = "always_answer_in_app"
        const val DEFAULT_VOICE = "eve"
        /** Built-in Grok voices; full list via GET https://api.x.ai/v1/tts/voices. */
        val VOICES = listOf("eve", "ara", "rex", "sal", "leo")
    }
}

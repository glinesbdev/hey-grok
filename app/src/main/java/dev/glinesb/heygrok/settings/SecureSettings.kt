package dev.glinesb.heygrok.settings

import dev.glinesb.heygrok.assist.LaunchTarget
import dev.glinesb.heygrok.router.AppRouter
import dev.glinesb.heygrok.router.BotContact
import dev.glinesb.heygrok.router.BotMessage
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * User-provided xAI API key + voice preference, encrypted at rest with
 * EncryptedSharedPreferences (AES-256-GCM values, key wrapped by an Android Keystore master key).
 * The key never goes into BuildConfig/local.properties and must never be logged.
 */
class SecureSettings(private val context: Context) {
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

    /** Target for launches without a wake phrase (tile, assistant gesture, widget mic). Not secret; stored here for one settings file. */
    var launchTarget: LaunchTarget
        get() = LaunchTarget.parse(prefs.getString(KEY_TARGET, null)) ?: AppRouter(context).let {
            LaunchTarget.default(it.isInstalled(AppRouter.GROK.packageName), it.isInstalled(AppRouter.GROK_BOT.packageName))
        }
        set(v) = prefs.edit().putString(KEY_TARGET, v.name).apply()

    /** Spoken name → Grok Bot agent id, for "Hey Grok Bot, tell <name> <message>". */
    var botContacts: List<BotContact>
        get() = BotMessage.decode(prefs.getString(KEY_BOTS, null))
        set(v) = prefs.edit().putString(KEY_BOTS, BotMessage.encode(v)).apply()

    /** Earcon + haptic when the wake word fires. */
    var wakeFeedback: Boolean
        get() = prefs.getBoolean(KEY_FEEDBACK, true)
        set(v) = prefs.edit().putBoolean(KEY_FEEDBACK, v).apply()

    /** Listening pop-up as a "Display over other apps" bubble when we don't hold the assistant role. */
    var overlayBubble: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE, false)
        set(v) = prefs.edit().putBoolean(KEY_BUBBLE, v).apply()

    var voice: String
        get() = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(v) = prefs.edit().putString(KEY_VOICE, v).apply()

    companion object {
        private const val FILE = "secure_settings"
        private const val KEY_API = "xai_api_key"
        private const val KEY_VOICE = "voice"
        private const val KEY_FEEDBACK = "wake_feedback"
        private const val KEY_BUBBLE = "overlay_bubble"
        private const val KEY_BOTS = "bot_contacts"
        private const val KEY_TARGET = "launch_target"
        private const val KEY_IN_APP = "always_answer_in_app"
        const val DEFAULT_VOICE = "eve"
        /** Built-in Grok voices; full list via GET https://api.x.ai/v1/tts/voices. */
        val VOICES = listOf("eve", "ara", "rex", "sal", "leo")
    }
}

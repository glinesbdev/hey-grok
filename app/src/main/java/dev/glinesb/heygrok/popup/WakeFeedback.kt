package dev.glinesb.heygrok.popup

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import dev.glinesb.heygrok.HgLog
import dev.glinesb.heygrok.settings.SecureSettings

/** Short earcon + haptic tick on wake (Settings → "Sound & vibration on wake", default on). */
object WakeFeedback {
    fun play(context: Context) {
        if (!SecureSettings(context).wakeFeedback) return
        HgLog.d("feedback: earcon + haptic")
        runCatching {
            context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }.onFailure { HgLog.w("feedback: vibrate failed", it) }
        runCatching {
            // 120 ms, finishes well before the recognizer opens the mic (350 ms after wake)
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 400)
        }.onFailure { HgLog.w("feedback: tone failed", it) }
    }
}

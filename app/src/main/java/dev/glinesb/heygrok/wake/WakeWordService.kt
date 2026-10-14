package dev.glinesb.heygrok.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.glinesb.heygrok.AppState
import dev.glinesb.heygrok.MainActivity
import dev.glinesb.heygrok.router.AppRouter
import dev.glinesb.heygrok.speech.SpeechCapture

/**
 * Foreground service (type=microphone) that keeps the wake-word engine running.
 *
 * Android 14+ rule: a microphone FGS may only be *started* while the app is in the
 * foreground (visible activity) and RECORD_AUDIO is granted. So [start] must be called
 * from the UI; we never auto-start from boot/background. Once running, the service
 * keeps "while-in-use" mic access even after the user leaves the app.
 */
class WakeWordService : Service() {

    private lateinit var engine: WakeWordEngine
    private lateinit var speech: SpeechCapture
    private lateinit var router: AppRouter

    override fun onCreate() {
        super.onCreate()
        engine = PorcupineWakeWordEngine(this)
        speech = SpeechCapture(this)
        router = AppRouter(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException / SecurityException on 14+
            Log.e(TAG, "startForeground failed", e)
            AppState.update { it.copy(listening = false, status = "Can't start: ${e.message}") }
            stopSelf()
            return START_NOT_STICKY
        }
        listenForWakeWord()
        // Not sticky: a system restart from background would violate Android 14 FGS rules.
        return START_NOT_STICKY
    }

    private fun listenForWakeWord() {
        AppState.update { it.copy(listening = true, status = "Listening for \"Hey Grok\"") }
        engine.start(
            onWake = { phrase -> onWake(phrase) },
            onError = { e -> AppState.update { it.copy(status = "Wake word error: ${e.message}") } },
        )
    }

    private fun onWake(phrase: WakePhrase) {
        // Release the mic so SpeechRecognizer can use it.
        engine.stop()
        AppState.update { it.copy(status = "Wake ($phrase) — speak your question") }
        speech.capture(
            onResult = { question ->
                AppState.update { it.copy(lastQuestion = question, status = "Routing…") }
                router.route(question, phrase)
                listenForWakeWord()
            },
            onError = { msg ->
                AppState.update { it.copy(status = "Speech error: $msg") }
                listenForWakeWord()
            },
        )
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hey Grok is listening")
            .setContentText("Say \"Hey Grok\" or \"Hey Grok Bot\"")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        speech.cancel()
        engine.release()
        AppState.update { it.copy(listening = false, status = "Stopped") }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL = "wake_word"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "dev.glinesb.heygrok.STOP"

        /** Call only while an activity is visible (Android 14 FGS rule). */
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, WakeWordService::class.java))
    }
}

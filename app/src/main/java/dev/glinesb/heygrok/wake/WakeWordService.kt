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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.glinesb.heygrok.AppState
import dev.glinesb.heygrok.HgLog
import dev.glinesb.heygrok.MainActivity
import dev.glinesb.heygrok.assist.AssistantRole
import dev.glinesb.heygrok.assist.MicCoordinator
import dev.glinesb.heygrok.assist.QuestionFlow
import dev.glinesb.heygrok.popup.ListeningPopup
import dev.glinesb.heygrok.popup.WakeFeedback
import dev.glinesb.heygrok.widget.WidgetUpdater

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
    private var flow: QuestionFlow? = null
    @Volatile private var paused = false

    private val micOwner = object : MicCoordinator.Owner {
        override fun pauseForCapture() { paused = true; flow?.cancel(); flow = null; engine.stop() }
        override fun resumeAfterCapture() { paused = false; listenForWakeWord() }
    }

    override fun onCreate() {
        super.onCreate()
        engine = OpenWakeWordEngine(this)
        MicCoordinator.owner = micOwner
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HgLog.i("service: stop requested")
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
            HgLog.e("service: startForeground failed", e)
            AppState.update { it.copy(listening = false, status = "Can't start: ${e.message}") }
            stopSelf()
            return START_NOT_STICKY
        }
        listenForWakeWord()
        WidgetUpdater.refresh(this)
        // Not sticky: a system restart from background would violate Android 14 FGS rules.
        return START_NOT_STICKY
    }

    private fun listenForWakeWord() {
        if (paused) return
        HgLog.i("service: listening for wake word")
        AppState.update { st -> st.copy(listening = true, status = if (st.status.contains("try again")) st.status else "Listening for \"Hey Grok\"") }
        engine.start(
            onWake = { phrase -> onWake(phrase) },
            onError = { e -> AppState.update { it.copy(status = "Wake word error: ${e.message}") } },
        )
    }

    private fun onWake(phrase: WakePhrase) {
        engine.stop() // the engine already released the mic before calling us
        // When we hold the assistant role, the system keeps our VoiceInteractionService bound, which
        // lets the hand-off start Grok / Grok Bot even with the app closed (see ARCHITECTURE.md).
        HgLog.i("service: wake $phrase (assistant role held=${AssistantRole.isHeld(this)})")
        WakeFeedback.play(this)
        ListeningPopup.start(this, phrase)
        flow = QuestionFlow(this, "wake").also { f -> f.run(phrase, onDone = { flow = null; ListeningPopup.end(); listenForWakeWord() }) }
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
            .setSmallIcon(dev.glinesb.heygrok.R.drawable.ic_stat_mic)
            .setContentTitle("Hey Grok is listening")
            .setContentText("Say \"Hey Grok\" or \"Hey Grok Bot\"")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        if (MicCoordinator.owner === micOwner) MicCoordinator.owner = null
        flow?.cancel()
        ListeningPopup.dismiss()
        engine.release()
        AppState.update { it.copy(listening = false, status = "Stopped") }
        WidgetUpdater.refresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL = "wake_word"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "dev.glinesb.heygrok.STOP"

        /** Call only while an activity is visible (Android 14 FGS rule). */
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, WakeWordService::class.java))
    }
}

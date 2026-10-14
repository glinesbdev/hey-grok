package dev.glinesb.heygrok.popup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.glinesb.heygrok.AppState
import dev.glinesb.heygrok.HgLog
import dev.glinesb.heygrok.MainActivity
import dev.glinesb.heygrok.assist.AssistantRole
import dev.glinesb.heygrok.assist.HeyGrokInteractionService
import dev.glinesb.heygrok.assist.HeyGrokSession
import dev.glinesb.heygrok.router.AppRouter
import dev.glinesb.heygrok.settings.SecureSettings
import dev.glinesb.heygrok.wake.WakePhrase
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Small "I'm listening" pop-up after the wake word fires while Hey Grok isn't on screen.
 * Surface, in order of preference:
 *  1. assistant role held → compact VoiceInteractionSession card (above any app, no permission needed)
 *  2. "Bubble over other apps" setting on + permission granted → SYSTEM_ALERT_WINDOW card
 *  3. otherwise → heads-up notification with live partial text
 * Dismisses on routing ("Sending to …"), error, or a safety timeout. Main thread only.
 */
object ListeningPopup {
    enum class Phase { LISTENING, SENDING, ERROR }
    data class Model(val title: String, val text: String?, val rms: Float, val phase: Phase)

    interface Surface { val name: String; fun show(); fun render(m: Model); fun dismiss() }

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var job: Job? = null
    private var surface: Surface? = null
    private var target = ""
    private var ending: Phase? = null
    @Volatile var last: Model? = null; private set

    /** Max idle time after capture ends without routing. Recognition itself is capped by SpeechCapture (60 s since last speech). */
    private const val TIMEOUT_MS = 60_000L
    /** Safety net only: re-arms while recognition is active, so it can't cut the user off. */
    private val timeout: Runnable = object : Runnable {
        override fun run() {
            if (AppState.state.value.capturing) { HgLog.d("popup: safety timer deferred (still capturing)"); main.postDelayed(this, TIMEOUT_MS); return }
            HgLog.w("popup: safety timeout"); dismiss()
        }
    }
    private val dismissRunnable: Runnable = object : Runnable {
        override fun run() { if (AppState.state.value.capturing) main.postDelayed(this, 500) else dismiss() }
    }

    private var sid = 0L
    private var onUserCancel: (() -> Unit)? = null

    /** The user dismissed the pop-up (tap, or the system hid the assistant session). */
    fun userDismissed(how: String) = main.post {
        if (surface == null) return@post
        HgLog.i("popup s$sid: dismissed by user ($how)")
        val cb = onUserCancel; dismiss(); cb?.invoke()
    }

    fun start(context: Context, phrase: WakePhrase, sessionId: Long, onUserCancel: () -> Unit) = main.post {
        dismiss()
        sid = sessionId; this.onUserCancel = onUserCancel
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            HgLog.d("popup: app is on screen, no pop-up"); return@post
        }
        val app = context.applicationContext
        val s = SecureSettings(app)
        val r = AppRouter(app)
        target = when {
            s.hasApiKey -> "Grok Voice"
            s.alwaysAnswerInApp -> "Hey Grok"
            else -> (if (phrase == WakePhrase.HEY_GROK_BOT) listOf(AppRouter.GROK_BOT, AppRouter.GROK) else listOf(AppRouter.GROK, AppRouter.GROK_BOT))
                .firstOrNull { r.isInstalled(it.packageName) }?.name ?: "Hey Grok"
        }
        ending = null
        val sf: Surface = when {
            AssistantRole.isHeld(app) && HeyGrokInteractionService.instance != null -> SessionSurface(app)
            s.overlayBubble && Settings.canDrawOverlays(app) -> BubbleSurface(app)
            else -> NotificationSurface(app)
        }
        HgLog.i("popup s$sid: show via ${sf.name} target=$target")
        surface = sf
        runCatching { sf.show() }.onFailure { HgLog.e("popup: ${sf.name} failed → notification", it); surface = NotificationSurface(app).also { n -> n.show() } }
        render(Model("Listening · $target", "Speak now…", -2f, Phase.LISTENING)) // always start empty
        job = scope.launch { AppState.state.collect { if (it.session == sid) render(model(it)) } }
        main.postDelayed(timeout, TIMEOUT_MS)
    }

    /** The question flow finished (routed, error, or Grok Voice turn over). */
    fun end() = main.post {
        if (surface == null) return@post
        val st = AppState.state.value
        if (st.session != sid) { HgLog.w("popup: end() for stale session"); return@post }
        ending = if (st.sendingTo != null) Phase.SENDING else if (st.status.contains("try again") || st.status.contains("rror")) Phase.ERROR else Phase.SENDING
        render(model(st))
        HgLog.i("popup: end ($ending)")
        main.removeCallbacks(dismissRunnable)
        if (AppState.state.value.capturing) HgLog.w("popup: end() while still capturing; dismiss waits for capture")
        main.postDelayed(dismissRunnable, if (ending == Phase.ERROR) 1_800L else 900L)
    }

    fun dismiss() {
        main.removeCallbacks(timeout); main.removeCallbacks(dismissRunnable)
        job?.cancel(); job = null
        surface?.let { HgLog.d("popup: dismiss ${it.name}"); runCatching { it.dismiss() } }
        surface = null; last = null; onUserCancel = null; ending = null
    }

    private fun model(st: AppState.State): Model = when {
        st.sendingTo != null -> Model("Sending to ${st.sendingTo}…", st.lastQuestion, st.rms, Phase.SENDING)
        ending == Phase.ERROR -> Model("Hey Grok", st.status, st.rms, Phase.ERROR)
        else -> Model("Listening · $target", st.partial ?: if (target == "Grok Voice") st.lastResult ?: st.status else "Speak now…", st.rms, Phase.LISTENING)
    }

    private fun render(m: Model) { last = m; surface?.render(m) }

    // ---- surfaces ----

    private class SessionSurface(val c: Context) : Surface {
        override val name = "assistant-session"
        override fun show() {
            HeyGrokInteractionService.instance!!.showSession(Bundle().apply { putBoolean(HeyGrokSession.EXTRA_COMPACT, true) }, 0)
        }
        override fun render(m: Model) { HeyGrokSession.compactCard?.render(m) }
        override fun dismiss() { HeyGrokSession.hideCompact() }
    }

    private class BubbleSurface(val c: Context) : Surface {
        override val name = "overlay-bubble"
        private val wm = c.getSystemService(WindowManager::class.java)
        private val card = PopupCardView(c)
        private val frame = android.widget.FrameLayout(c).apply {
            val m = (12 * c.resources.displayMetrics.density).toInt()
            addView(card, android.widget.FrameLayout.LayoutParams(-1, -2))
            setOnClickListener { userDismissed("bubble tap") }
            applyPopupInsets("bubble")
        }
        override fun show() {
            wm.addView(frame, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM; windowAnimations = android.R.style.Animation_InputMethod
                // lay out under the system bars and pad ourselves (see applyPopupInsets)
                if (Build.VERSION.SDK_INT >= 30) { fitInsetsTypes = 0; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
                else flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
            })
        }
        override fun render(m: Model) = card.render(m)
        override fun dismiss() { if (frame.isAttachedToWindow) wm.removeView(frame) }
    }

    private class NotificationSurface(val c: Context) : Surface {
        override val name = "heads-up-notification"
        private val nm = NotificationManagerCompat.from(c)
        private var lastPost = 0L
        private var lastText: String? = null
        override fun show() {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Listening pop-up", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shows what Hey Grok hears after the wake word"
                    setSound(null, null); enableVibration(false); setShowBadge(false)
                })
            if (!nm.areNotificationsEnabled()) HgLog.w("popup: notifications disabled, heads-up won't show")
            post(last ?: Model("Listening · …", "Speak now…", 0f, Phase.LISTENING), force = true)
        }
        override fun render(m: Model) = post(m, force = m.phase != Phase.LISTENING)
        private fun post(m: Model, force: Boolean) {
            val text = m.text ?: ""
            val now = SystemClock.uptimeMillis()
            // the system drops updates above ~10/s; only text changes matter here
            if (!force && (text == lastText || now - lastPost < 300)) return
            lastPost = now; lastText = text
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(dev.glinesb.heygrok.R.drawable.ic_stat_mic)
                .setContentTitle(m.title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOnlyAlertOnce(true).setSilent(false).setOngoing(m.phase == Phase.LISTENING)
                .setTimeoutAfter(TIMEOUT_MS * 3).setContentIntent(open).build()
            try { nm.notify(NOTIF_ID, n) } catch (e: SecurityException) { HgLog.w("popup: no notification permission", e) }
        }
        override fun dismiss() = nm.cancel(NOTIF_ID)
    }

    private const val CHANNEL = "listening_popup"
    private const val NOTIF_ID = 43
}

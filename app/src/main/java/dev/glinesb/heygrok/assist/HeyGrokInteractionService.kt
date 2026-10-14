package dev.glinesb.heygrok.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import dev.glinesb.heygrok.HgLog
import dev.glinesb.heygrok.popup.ListeningPopup
import dev.glinesb.heygrok.popup.PopupCardView
import dev.glinesb.heygrok.popup.applyPopupInsets
import android.os.Build
import androidx.core.view.WindowCompat

/**
 * Our VoiceInteractionService. It's bound by the system while Hey Grok is the default digital
 * assistant. That binding is what (a) makes long-press home / corner swipe open our session and
 * (b) exempts us from background-activity-start and while-in-use limits, so the wake-word hand-off
 * can open Grok / Grok Bot with the app closed.
 */
class HeyGrokInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); instance = this; HgLog.i("assist: VoiceInteractionService ready (role held)") }
    override fun onShutdown() { HgLog.i("assist: VoiceInteractionService shutdown"); if (instance === this) instance = null; super.onShutdown() }

    companion object {
        /** Set while the system has us bound as the assistant; used to show the compact wake pop-up. */
        @Volatile var instance: HeyGrokInteractionService? = null
    }
}

class HeyGrokSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = HeyGrokSession(this)
}

/**
 * Session for long-press home / corner swipe. Its UI is AssistActivity (a translucent overlay that
 * follows the system theme), started with startAssistantActivity so it shows above the current app.
 */
class HeyGrokSession(context: Context) : VoiceInteractionSession(context) {
    private var compact = false

    override fun onCreate() {
        super.onCreate()
        // Bottom-anchored, wrap-content window that lets touches through to the app underneath.
        window.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Draw under the nav bar / cutout and pad the card ourselves, so gesture and 3-button nav both work.
            WindowCompat.setDecorFitsSystemWindows(this, false)
            if (Build.VERSION.SDK_INT >= 30) attributes = attributes.apply {
                fitInsetsTypes = 0; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        compact = args?.getBoolean(EXTRA_COMPACT) == true
        setUiEnabled(compact) // only the compact wake pop-up has its own window content
        super.onPrepareShow(args, showFlags)
    }

    override fun onCreateContentView(): View? {
        if (!compact) return null
        val card = PopupCardView(context)
        ListeningPopup.last?.let(card::render)
        compactCard = card
        return FrameLayout(context).apply {
            addView(card, FrameLayout.LayoutParams(-1, -2))
            setOnClickListener { hide() }
            applyPopupInsets("session")
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        current = this
        HgLog.i("assist: session onShow flags=$showFlags compact=$compact")
        if (compact) return
        runCatching {
            startAssistantActivity(AssistActivity.askIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { HgLog.e("assist: startAssistantActivity failed", it) }
        hide() // the overlay activity owns the UI from here
    }
    override fun onHide() {
        HgLog.d("assist: session onHide")
        if (current === this) { current = null; compactCard = null }
        super.onHide()
    }

    companion object {
        const val EXTRA_COMPACT = "dev.glinesb.heygrok.COMPACT"
        @Volatile var current: HeyGrokSession? = null
        @Volatile var compactCard: PopupCardView? = null
    }
}

package dev.glinesb.heygrok.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.glinesb.heygrok.HgLog

/**
 * Our VoiceInteractionService. It's bound by the system while Hey Grok is the default digital
 * assistant. That binding is what (a) makes long-press home / corner swipe open our session and
 * (b) exempts us from background-activity-start and while-in-use limits, so the wake-word hand-off
 * can open Grok / Grok Bot with the app closed.
 */
class HeyGrokInteractionService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); HgLog.i("assist: VoiceInteractionService ready (role held)") }
    override fun onShutdown() { HgLog.i("assist: VoiceInteractionService shutdown"); super.onShutdown() }
}

class HeyGrokSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = HeyGrokSession(this)
}

/**
 * Session for long-press home / corner swipe. Its UI is AssistActivity (a translucent overlay that
 * follows the system theme), started with startAssistantActivity so it shows above the current app.
 */
class HeyGrokSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        HgLog.i("assist: session onShow flags=$showFlags")
        runCatching {
            startAssistantActivity(AssistActivity.askIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { HgLog.e("assist: startAssistantActivity failed", it) }
        hide() // the overlay activity owns the UI from here
    }
    override fun onHide() { HgLog.d("assist: session onHide"); super.onHide() }
}

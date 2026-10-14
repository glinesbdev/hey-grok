package dev.glinesb.heygrok.router

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.glinesb.heygrok.AppState
import dev.glinesb.heygrok.HgLog
import dev.glinesb.heygrok.settings.SecureSettings
import dev.glinesb.heygrok.ui.ResultActivity
import dev.glinesb.heygrok.wake.WakePhrase

/**
 * Decides where a spoken question goes:
 *  1. Preferred app for the phrase ("Hey Grok Bot" → Grok Bot, "Hey Grok" → Grok), else the other one.
 *  2. Try ACTION_SEND with the question text targeted at that package.
 *  3. Else just open the app with its launch intent (question can't be passed).
 *  0. "Always answer in this app" setting → skip straight to step 4.
 *  4. If neither app is installed (or that setting is on), show our own result screen backed by the xAI API.
 *
 * NOTE: starting activities from a background service is restricted (Android 10+).
 * Holding a visible foreground-service notification is NOT an exemption. It works
 * while our UI is visible; for true hands-free from the lock/home screen, use the
 * default-assistant role (see ARCHITECTURE.md) or a full-screen-intent notification.
 */
class AppRouter(private val context: Context) {

    data class Target(val name: String, val packageName: String)

    fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun installedTargets(): List<Target> = listOf(GROK, GROK_BOT).filter { isInstalled(it.packageName) }

    /** Session id of the question being routed (0 = unscoped); scopes AppState writes and intent identity. */
    private var sid = 0L

    fun route(question: String, phrase: WakePhrase, sessionId: Long = 0L) {
        sid = sessionId
        HgLog.i("router s$sid: route ${question.length} chars ($phrase)")
        if (SecureSettings(context).alwaysAnswerInApp) { sending("Hey Grok"); showFallback(question); return }
        if (phrase == WakePhrase.HEY_GROK_BOT && isInstalled(GROK_BOT.packageName) && tryBotMessage(question)) return
        val ordered = if (phrase == WakePhrase.HEY_GROK_BOT) listOf(GROK_BOT, GROK) else listOf(GROK, GROK_BOT)
        val target = ordered.firstOrNull { isInstalled(it.packageName) }
        when {
            target != null && trySend(target, question) -> sending(target.name)
            target != null && tryLaunch(target) -> sending(target.name)
            else -> { sending("Hey Grok"); showFallback(question) }
        }
    }

    /**
     * "tell <bot> <message>": open that bot's chat via grokbot://app/v1/agent?id=… and put the message on the
     * clipboard. ai.x.grok.bot exposes no prefill parameter, so we never send anything ourselves.
     * Falls back to ACTION_SEND of just the message if the link doesn't resolve.
     */
    private fun tryBotMessage(question: String): Boolean {
        val parsed = BotMessage.parse(question, SecureSettings(context).botContacts) ?: return false
        HgLog.i("router: bot message → '${parsed.contact.name}' (${parsed.message.length} chars)")
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(BotMessage.agentLink(parsed.contact.agentId)))
            .setPackage(GROK_BOT.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).unique()
        if (view.resolveActivity(context.packageManager) != null && start(view)) {
            sending("${parsed.contact.name} (Grok Bot)")
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Message for ${parsed.contact.name}", parsed.message))
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, "Message copied, paste to send", Toast.LENGTH_LONG).show()
            }
            return true
        }
        HgLog.w("router: agent link didn't resolve → ACTION_SEND of the message")
        return trySend(GROK_BOT, parsed.message).also { if (it) sending("${parsed.contact.name} (Grok Bot)") }
    }

    private fun sending(label: String) {
        HgLog.i("router s$sid: sending to $label")
        if (sid != 0L) AppState.updateIf(sid) { it.copy(sendingTo = label) } else AppState.update { it.copy(sendingTo = label) }
    }

    /**
     * Intents that differ only in extras are "equal" to Android. With FLAG_ACTIVITY_NEW_TASK, an existing
     * Grok task started by an earlier (e.g. cancelled) hand-off would just be brought to front with the
     * OLD text, and the new EXTRA_TEXT never delivered. A unique identifier makes every hand-off distinct.
     */
    private fun Intent.unique(): Intent = apply { identifier = "heygrok-s$sid-${System.nanoTime()}" }

    private fun trySend(target: Target, question: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(target.packageName)
            putExtra(Intent.EXTRA_TEXT, question)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.unique()
        if (send.resolveActivity(context.packageManager) == null) return false
        return start(send)
    }

    private fun tryLaunch(target: Target): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(target.packageName) ?: return false
        return start(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showFallback(question: String) {
        start(ResultActivity.intent(context, question).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(intent: Intent): Boolean = try {
        HgLog.i("router: start $intent"); context.startActivity(intent); true
    } catch (e: Exception) {
        HgLog.w("router: startActivity failed: $intent", e); false
    }

    companion object {
        /** Verified on Google Play: https://play.google.com/store/apps/details?id=ai.x.grok */
        val GROK = Target("Grok", "ai.x.grok")
        /** Verified on Google Play: https://play.google.com/store/apps/details?id=ai.x.grok.bot */
        val GROK_BOT = Target("Grok Bot", "ai.x.grok.bot")
    }
}

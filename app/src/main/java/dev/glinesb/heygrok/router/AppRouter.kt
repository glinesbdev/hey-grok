package dev.glinesb.heygrok.router

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
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

    fun route(question: String, phrase: WakePhrase) {
        if (SecureSettings(context).alwaysAnswerInApp) { showFallback(question); return }
        val ordered = if (phrase == WakePhrase.HEY_GROK_BOT) listOf(GROK_BOT, GROK) else listOf(GROK, GROK_BOT)
        val target = ordered.firstOrNull { isInstalled(it.packageName) }
        when {
            target != null && trySend(target, question) -> Unit
            target != null && tryLaunch(target) -> Unit
            else -> showFallback(question)
        }
    }

    private fun trySend(target: Target, question: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(target.packageName)
            putExtra(Intent.EXTRA_TEXT, question)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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
        context.startActivity(intent); true
    } catch (e: Exception) {
        Log.w("AppRouter", "startActivity failed: $intent", e); false
    }

    companion object {
        /** Verified on Google Play: https://play.google.com/store/apps/details?id=ai.x.grok */
        val GROK = Target("Grok", "ai.x.grok")
        /** Verified on Google Play: https://play.google.com/store/apps/details?id=ai.x.grok.bot */
        val GROK_BOT = Target("Grok Bot", "ai.x.grok.bot")
    }
}

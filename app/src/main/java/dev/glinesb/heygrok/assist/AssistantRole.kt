package dev.glinesb.heygrok.assist

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import dev.glinesb.heygrok.HgLog

/** Default digital assistant role (RoleManager.ROLE_ASSISTANT). Everything still works without it. */
object AssistantRole {
    fun isHeld(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.let {
            it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && it.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } ?: false

    /**
     * Register in onCreate. ROLE_ASSISTANT usually can't be granted through the role dialog (the
     * platform marks it non-requestable, so the request returns immediately), so on any
     * non-success we open the system's default-apps / assistant settings instead.
     */
    fun registerRequester(activity: ComponentActivity, onChanged: (Boolean) -> Unit): () -> Unit {
        val launcher: ActivityResultLauncher<Intent> =
            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val held = isHeld(activity)
                HgLog.i("role: request result=${it.resultCode}, held=$held")
                if (!held) openSettings(activity)
                onChanged(held)
            }
        return {
            val rm = activity.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                HgLog.i("role: requesting ROLE_ASSISTANT")
                runCatching { launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)) }
                    .onFailure { HgLog.w("role: request failed", it); openSettings(activity) }
            } else openSettings(activity)
        }
    }

    /** Settings → Apps → Default apps → Digital assistant app (falls back to voice-input settings). */
    fun openSettings(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in candidates) {
            try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); HgLog.i("role: opened ${i.action}"); return }
            catch (_: ActivityNotFoundException) {}
        }
    }
}

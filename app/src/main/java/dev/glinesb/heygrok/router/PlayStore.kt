package dev.glinesb.heygrok.router

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object PlayStore {
    /** Opens the Play Store app, or the web listing if no store app is available. */
    fun open(context: Context, pkg: String) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(flags))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(flags))
        }
    }
}

/** One "Get <app> on Google Play" button per Grok app that isn't installed (none, one, or both). */
@Composable
fun MissingAppButtons(router: AppRouter) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(AppRouter.GROK, AppRouter.GROK_BOT).filterNot { router.isInstalled(it.packageName) }.forEach { t ->
            OutlinedButton(onClick = { PlayStore.open(context, t.packageName) }) { Text("Get ${t.name} on Google Play") }
        }
    }
}

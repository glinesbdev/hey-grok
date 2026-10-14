package dev.glinesb.heygrok.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.glinesb.heygrok.AppState
import dev.glinesb.heygrok.HgLog
import dev.glinesb.heygrok.assist.AssistActivity
import dev.glinesb.heygrok.tile.ListeningTileService
import dev.glinesb.heygrok.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget: mic button (one-shot question via the translucent overlay) + listening
 * toggle + status. GlanceTheme uses Material You dynamic colors on Android 12+ and follows
 * light/dark. The toggle starts the mic service directly: widget interactions are exempt from
 * the while-in-use restriction.
 */
class HeyGrokWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Content(context) } }
    }

    @Composable
    private fun Content(context: Context) {
        val listening = AppState.state.collectAsState().value.listening
        Row(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                GlanceModifier.size(56.dp).cornerRadius(28.dp).background(GlanceTheme.colors.primary)
                    .clickable(actionStartActivity(AssistActivity.askIntent(context))),
                contentAlignment = Alignment.Center,
            ) { Text("🎙", style = TextStyle(fontSize = 24.sp)) }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text("Hey Grok", style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                Text(if (listening) "Listening for “Hey Grok”" else "Wake word off",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
            val toggle = if (listening)
                actionStartService(Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP), isForegroundService = false)
            else actionStartService(Intent(context, WakeWordService::class.java), isForegroundService = true)
            Box(
                GlanceModifier.cornerRadius(16.dp)
                    .background(if (listening) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp).clickable(toggle),
            ) {
                Text(if (listening) "On" else "Off", style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontWeight = FontWeight.Medium))
            }
        }
    }
}

class HeyGrokWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HeyGrokWidget()
}

/** Pushes the listening state to the widget and the Quick Settings tile. */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun refresh(context: Context) {
        val app = context.applicationContext
        ListeningTileService.refresh(app)
        scope.launch {
            runCatching {
                if (GlanceAppWidgetManager(app).getGlanceIds(HeyGrokWidget::class.java).isNotEmpty()) HeyGrokWidget().updateAll(app)
            }.onFailure { HgLog.w("widget: update failed", it) }
        }
    }
}

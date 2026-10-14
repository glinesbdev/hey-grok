package dev.glinesb.heygrok.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.glinesb.heygrok.AppState
import dev.glinesb.heygrok.HgLog
import dev.glinesb.heygrok.assist.AssistActivity
import dev.glinesb.heygrok.assist.AssistantRole
import dev.glinesb.heygrok.wake.WakeWordService

/**
 * Quick Settings tile: toggles wake-word listening and shows its state.
 *
 * Starting a *microphone* foreground service needs while-in-use access. Tile clicks are not on
 * Android's while-in-use exemption list (widgets, notifications, and the VoiceInteractionService
 * app are), so: if we hold the assistant role we call startForegroundService directly; otherwise we
 * open the translucent AssistActivity (startActivityAndCollapse), which starts it while visible.
 */
class ListeningTileService : TileService() {
    override fun onStartListening() { super.onStartListening(); render() }

    override fun onClick() {
        super.onClick()
        val listening = AppState.state.value.listening
        HgLog.i("tile: click (listening=$listening, locked=$isLocked)")
        if (listening) { WakeWordService.stop(this); render(false); return }
        val start = {
            if (AssistantRole.isHeld(this)) {
                HgLog.i("tile: role held → startForegroundService directly")
                runCatching { WakeWordService.start(this) }.onFailure { HgLog.e("tile: direct start failed", it); viaActivity() }
            } else viaActivity()
            render(true)
        }
        if (isLocked) unlockAndRun { start() } else start()
    }

    private fun viaActivity() {
        HgLog.i("tile: starting via translucent activity")
        val intent = AssistActivity.toggleIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(intent)
        }
    }

    private fun render(listening: Boolean = AppState.state.value.listening) {
        val t = qsTile ?: return
        t.state = if (listening) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Hey Grok"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (listening) "Listening" else "Off"
        t.updateTile()
    }

    companion object {
        /** Ask the system to refresh the tile after the state changed elsewhere. */
        fun refresh(c: Context) = runCatching { requestListeningState(c, ComponentName(c, ListeningTileService::class.java)) }
    }
}

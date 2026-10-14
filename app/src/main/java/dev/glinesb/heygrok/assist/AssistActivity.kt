package dev.glinesb.heygrok.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glinesb.heygrok.AppState
import dev.glinesb.heygrok.HgLog
import dev.glinesb.heygrok.MainActivity
import dev.glinesb.heygrok.ui.theme.HeyGrokTheme
import dev.glinesb.heygrok.wake.WakePhrase
import dev.glinesb.heygrok.wake.WakeWordService

/**
 * Lightweight translucent overlay (follows the system theme) used by:
 *  - the assistant session (long-press home / corner swipe) and the widget mic → ACTION_ASK
 *  - the Quick Settings tile when it can't start the mic service itself → ACTION_TOGGLE
 * Being a visible activity, it may use the mic and start Grok / Grok Bot without restrictions.
 */
class AssistActivity : ComponentActivity() {
    private var flow: QuestionFlow? = null
    private var borrowed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val action = intent?.action
        HgLog.i("overlay: onCreate action=$action")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            HgLog.w("overlay: no mic permission → opening main screen")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); return
        }
        when (action) {
            ACTION_TOGGLE -> {
                if (AppState.state.value.listening) WakeWordService.stop(this) else WakeWordService.start(this)
                HgLog.i("overlay: toggled listening → ${!AppState.state.value.listening}")
                finish(); return
            }
            else -> ask()
        }
        setContent {
            HeyGrokTheme {
                val st by AppState.state.collectAsStateWithLifecycle()
                Box(
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Card(
                        Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp),
                        shape = RoundedCornerShape(28.dp),
                        elevation = CardDefaults.cardElevation(8.dp),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Hey Grok", style = MaterialTheme.typography.titleLarge)
                            Text(st.status)
                            st.partial?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge) }
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    private fun ask() {
        MicCoordinator.borrow(); borrowed = MicCoordinator.owner != null
        flow = QuestionFlow(this, "overlay").also { f ->
            f.run(WakePhrase.HEY_GROK) {
                runOnUiThread { HgLog.i("overlay: flow done"); finish() }
            }
        }
    }

    override fun onDestroy() {
        flow?.cancel(); flow = null
        if (borrowed) { borrowed = false; MicCoordinator.giveBack() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_ASK = "dev.glinesb.heygrok.ASK"
        const val ACTION_TOGGLE = "dev.glinesb.heygrok.TOGGLE"
        fun askIntent(c: Context) = Intent(c, AssistActivity::class.java).setAction(ACTION_ASK)
        fun toggleIntent(c: Context) = Intent(c, AssistActivity::class.java).setAction(ACTION_TOGGLE)
    }
}

package dev.glinesb.heygrok.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import dev.glinesb.heygrok.router.AppRouter
import dev.glinesb.heygrok.router.PlayStore
import dev.glinesb.heygrok.settings.SecureSettings
import dev.glinesb.heygrok.ui.theme.HeyGrokTheme
import dev.glinesb.heygrok.wake.WakePhrase
import dev.glinesb.heygrok.wake.WakeWordService

/**
 * Lightweight translucent overlay (follows the system theme) used by:
 *  - the assistant session (long-press home / corner swipe) and the widget mic → ACTION_ASK
 *  - the Quick Settings tile when it can't start the mic service itself → ACTION_TOGGLE
 * Being a visible activity, it may use the mic and start Grok / Grok Bot without restrictions.
 *
 * Target selection for ACTION_ASK (no wake phrase): spoken prefix ("Grok bot, …") > chip the user tapped
 * > intent extra (widget "Bot" button) > Settings default. With "Ask each time" and no choice yet, the
 * transcript waits up to [CHOICE_TIMEOUT_MS] for a chip tap, then goes to the first installed app.
 */
class AssistActivity : ComponentActivity() {
    private var flow: QuestionFlow? = null
    private var borrowed = false
    private val main = Handler(Looper.getMainLooper())
    private val router by lazy { AppRouter(this) }

    private val selected = mutableStateOf<WakePhrase?>(null)
    private val awaitingChoice = mutableStateOf(false)
    private var pendingQuestion: String? = null
    private var routed = false
    private val timeout = Runnable {
        val q = pendingQuestion ?: return@Runnable
        val fallback = if (router.isInstalled(AppRouter.GROK.packageName)) WakePhrase.HEY_GROK else WakePhrase.HEY_GROK_BOT
        HgLog.i("overlay: no target chosen in ${CHOICE_TIMEOUT_MS}ms → $fallback")
        routeNow(q, fallback, "timeout")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val action = intent?.action
        HgLog.i("overlay: onCreate action=$action data=${intent?.data}")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            HgLog.w("overlay: no mic permission → opening main screen")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); return
        }
        if (action == ACTION_TOGGLE) {
            if (AppState.state.value.listening) WakeWordService.stop(this) else WakeWordService.start(this)
            HgLog.i("overlay: toggled listening → ${!AppState.state.value.listening}")
            finish(); return
        }
        val settings = SecureSettings(this)
        val forced = LaunchTarget.parse(intent?.getStringExtra(EXTRA_TARGET))
        val setting = settings.launchTarget
        selected.value = (forced ?: setting).phrase
        // Chips are pointless when the answer stays in this app (Grok Voice key or "Always answer in this app").
        val showChips = !settings.hasApiKey && !settings.alwaysAnswerInApp
        HgLog.i("overlay: target setting=$setting extra=$forced preselected=${selected.value} chips=$showChips")
        ask()
        setContent {
            HeyGrokTheme {
                val st by AppState.state.collectAsStateWithLifecycle()
                Box(
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Card(
                        Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp)
                            .clickable(remember { MutableInteractionSource() }, null) { /* swallow taps */ },
                        shape = RoundedCornerShape(28.dp),
                        elevation = CardDefaults.cardElevation(8.dp),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Hey Grok", style = MaterialTheme.typography.titleLarge)
                            if (awaitingChoice.value) Text("Send to which app?", style = MaterialTheme.typography.titleMedium)
                            else Text(st.status)
                            (pendingQuestion ?: st.partial)?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge) }
                            if (showChips) TargetChips(prominent = selected.value == null)
                            if (!awaitingChoice.value) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TargetChips(prominent: Boolean) {
        val options = listOf(AppRouter.GROK to WakePhrase.HEY_GROK, AppRouter.GROK_BOT to WakePhrase.HEY_GROK_BOT)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (t, phrase) ->
                val m = Modifier.weight(1f).then(if (prominent) Modifier.heightIn(min = 56.dp) else Modifier)
                if (router.isInstalled(t.packageName)) {
                    FilterChip(
                        selected = selected.value == phrase,
                        onClick = { choose(phrase) },
                        label = { Text(t.name, style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge) },
                        modifier = m,
                    )
                } else {
                    AssistChip(
                        onClick = { HgLog.i("overlay: Play Store for ${t.packageName}"); PlayStore.open(this@AssistActivity, t.packageName) },
                        label = { Text("Get ${t.name}") },
                        modifier = m,
                    )
                }
            }
        }
    }

    private fun choose(phrase: WakePhrase) {
        HgLog.i("overlay: chip tapped → $phrase")
        selected.value = phrase
        pendingQuestion?.let { routeNow(it, phrase, "chip") }
    }

    private fun ask() {
        MicCoordinator.borrow(); borrowed = MicCoordinator.owner != null
        flow = QuestionFlow(this, "overlay").also { f ->
            f.run(selected.value ?: WakePhrase.HEY_GROK,
                onDone = { runOnUiThread { HgLog.i("overlay: flow done"); if (pendingQuestion == null) finish() } },
                deliver = { text -> runOnUiThread { onTranscript(text) } })
        }
    }

    private fun onTranscript(text: String) {
        val spoken = SpokenPrefix.parse(text)
        val question = spoken.question.ifBlank { text }
        val installed = router.installedTargets()
        when {
            spoken.phrase != null -> { HgLog.i("overlay: spoken prefix → ${spoken.phrase}"); routeNow(question, spoken.phrase, "spoken") }
            selected.value != null -> routeNow(question, selected.value!!, "selected")
            installed.size <= 1 -> routeNow(question, if (installed.firstOrNull() == AppRouter.GROK_BOT) WakePhrase.HEY_GROK_BOT else WakePhrase.HEY_GROK, "only-app")
            else -> {
                HgLog.i("overlay: ask each time → waiting for chip (${CHOICE_TIMEOUT_MS}ms)")
                pendingQuestion = question; awaitingChoice.value = true
                main.postDelayed(timeout, CHOICE_TIMEOUT_MS)
            }
        }
    }

    private fun routeNow(question: String, phrase: WakePhrase, why: String) {
        if (routed) return
        routed = true; main.removeCallbacks(timeout); pendingQuestion = null; awaitingChoice.value = false
        HgLog.i("overlay: routing to $phrase ($why)")
        AppState.update { it.copy(lastQuestion = question, partial = null, status = "Routing…") }
        router.route(question, phrase)
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        flow?.cancel(); flow = null
        if (borrowed) { borrowed = false; MicCoordinator.giveBack() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_ASK = "dev.glinesb.heygrok.ASK"
        const val ACTION_TOGGLE = "dev.glinesb.heygrok.TOGGLE"
        const val EXTRA_TARGET = "dev.glinesb.heygrok.TARGET"
        const val CHOICE_TIMEOUT_MS = 8_000L
        fun askIntent(c: Context, target: LaunchTarget? = null) = Intent(c, AssistActivity::class.java).setAction(ACTION_ASK).apply {
            // distinct data so the widget's two buttons get distinct PendingIntents
            if (target != null) { putExtra(EXTRA_TARGET, target.name); data = Uri.parse("heygrok://ask/${target.name.lowercase()}") }
        }
        fun toggleIntent(c: Context) = Intent(c, AssistActivity::class.java).setAction(ACTION_TOGGLE)
    }
}

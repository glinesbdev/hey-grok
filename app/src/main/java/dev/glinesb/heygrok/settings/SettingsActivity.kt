package dev.glinesb.heygrok.settings

import android.os.Bundle
import android.net.Uri
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.glinesb.heygrok.ui.theme.HeyGrokTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.unit.dp
import dev.glinesb.heygrok.HgLog
import dev.glinesb.heygrok.assist.AssistantRole
import dev.glinesb.heygrok.assist.LaunchTarget
import dev.glinesb.heygrok.router.BotContact
import dev.glinesb.heygrok.router.BotMessage
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role

/** Optional Grok Voice mode: enter your own xAI key (stored encrypted) and pick a voice. */
class SettingsActivity : ComponentActivity() {
    private lateinit var requestRole: () -> Unit
    private val canOverlay = mutableStateOf(false)

    override fun onResume() { super.onResume(); canOverlay.value = android.provider.Settings.canDrawOverlays(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestRole = AssistantRole.registerRequester(this) { HgLog.i("settings: role held=$it") }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = SecureSettings(this)
        setContent {
            HeyGrokTheme {
                Surface {
                    var hasKey by remember { mutableStateOf(settings.hasApiKey) }
                    var input by remember { mutableStateOf("") }
                    var voice by remember { mutableStateOf(settings.voice) }
                    var inApp by remember { mutableStateOf(settings.alwaysAnswerInApp) }
                    var target by remember { mutableStateOf(settings.launchTarget) }
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Always answer in this app", style = MaterialTheme.typography.titleMedium)
                                Text("Don't hand off to Grok / Grok Bot. Answers need an xAI API key (below).")
                            }
                            Switch(checked = inApp, onCheckedChange = { inApp = it; settings.alwaysAnswerInApp = it })
                        }
                        HorizontalDivider()
                        Text("Default app for button/assistant launches", style = MaterialTheme.typography.titleMedium)
                        Text("Used by the widget mic, Quick Settings and the assistant gesture. \"Hey Grok\" / \"Hey Grok Bot\" always pick their own app, and you can start a question with \"Grok bot, …\" or \"Grok, …\".")
                        LaunchTarget.entries.forEach { t ->
                            Row(Modifier.fillMaxWidth().selectable(selected = target == t, role = Role.RadioButton) {
                                target = t; settings.launchTarget = t; HgLog.i("settings: launch target → $t")
                            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = target == t, onClick = null)
                                Spacer(Modifier.width(12.dp)); Text(t.label)
                            }
                        }
                        HorizontalDivider()
                        Text("Wake word feedback", style = MaterialTheme.typography.titleMedium)
                        var fb by remember { mutableStateOf(settings.wakeFeedback) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Sound & vibration on wake", Modifier.weight(1f))
                            Switch(checked = fb, onCheckedChange = { fb = it; settings.wakeFeedback = it; HgLog.i("settings: wake feedback=$it") })
                        }
                        var bubble by remember { mutableStateOf(settings.overlayBubble) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Bubble over other apps")
                                Text("When Hey Grok isn't the default assistant, show the listening card over other apps instead of a notification.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = bubble, onCheckedChange = { bubble = it; settings.overlayBubble = it; HgLog.i("settings: overlay bubble=$it") })
                        }
                        if (bubble && !canOverlay.value) OutlinedButton(onClick = {
                            HgLog.i("settings: requesting Display over other apps")
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }) { Text("Allow \"Display over other apps\"") }
                        HorizontalDivider()
                        Text("Grok Bot contacts", style = MaterialTheme.typography.titleMedium)
                        Text("Say \"Hey Grok Bot, tell <name> <message>\" to open that Bot's chat with the message copied. You paste and send it yourself. Agent id: paste the Bot's link from the Grok Bot desktop app (grokbot://app/v1/agent?id=…) or just the id.")
                        var bots by remember { mutableStateOf(settings.botContacts) }
                        bots.forEach { b ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(b.name); Text(b.agentId, style = MaterialTheme.typography.bodySmall) }
                                TextButton(onClick = { bots = bots - b; settings.botContacts = bots; HgLog.i("settings: removed bot contact") }) { Text("Remove") }
                            }
                        }
                        var botName by remember { mutableStateOf("") }
                        var botId by remember { mutableStateOf("") }
                        val parsedId = BotMessage.extractAgentId(botId)
                        OutlinedTextField(botName, { botName = it }, label = { Text("Name you'll say, e.g. Android Dev") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(botId, { botId = it }, label = { Text("Agent link or id") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            isError = botId.isNotBlank() && parsedId == null)
                        Button(enabled = botName.isNotBlank() && parsedId != null, onClick = {
                            bots = bots.filterNot { it.name.equals(botName.trim(), true) } + BotContact(botName.trim(), parsedId!!)
                            settings.botContacts = bots; botName = ""; botId = ""; HgLog.i("settings: added bot contact (${bots.size} total)")
                        }) { Text("Add Bot") }
                        HorizontalDivider()
                        Text("Default digital assistant", style = MaterialTheme.typography.titleMedium)
                        var held by remember { mutableStateOf(AssistantRole.isHeld(this@SettingsActivity)) }
                        Text(if (held) "Hey Grok is the default assistant." else "Long-press home / corner swipe opens another assistant.")
                        OutlinedButton(onClick = { requestRole(); held = AssistantRole.isHeld(this@SettingsActivity) }) {
                            Text(if (held) "Change in system settings" else "Set as default assistant")
                        }
                        HorizontalDivider()
                        Text("Grok Voice (optional)", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "With your own xAI API key, the question after the wake word goes straight to Grok Voice " +
                                "(speech-to-speech). This is paid, about $0.08 per minute, billed to YOUR xAI account. " +
                                "Without a key, the free path is used: on-device speech-to-text, then the Grok or Grok Bot app."
                        )
                        Text(if (hasKey) "API key: saved (encrypted on this device)" else "API key: not set (free mode)")
                        OutlinedTextField(
                            value = input, onValueChange = { input = it }, singleLine = true,
                            label = { Text("xAI API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = input.isNotBlank(), onClick = {
                                settings.apiKey = input; input = ""; hasKey = settings.hasApiKey
                            }) { Text("Save key") }
                            OutlinedButton(enabled = hasKey, onClick = {
                                settings.apiKey = null; hasKey = false
                            }) { Text("Remove key") }
                        }
                        Text("Voice", style = MaterialTheme.typography.titleMedium)
                        SecureSettings.VOICES.forEach { v ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = voice == v, onClick = { voice = v; settings.voice = v }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = voice == v, onClick = { voice = v; settings.voice = v })
                                Text(v)
                            }
                        }
                    }
                }
            }
        }
    }
}

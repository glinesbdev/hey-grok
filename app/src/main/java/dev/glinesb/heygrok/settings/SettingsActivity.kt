package dev.glinesb.heygrok.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.unit.dp

/** Optional Grok Voice mode: enter your own xAI key (stored encrypted) and pick a voice. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SecureSettings(this)
        setContent {
            MaterialTheme {
                Surface {
                    var hasKey by remember { mutableStateOf(settings.hasApiKey) }
                    var input by remember { mutableStateOf("") }
                    var voice by remember { mutableStateOf(settings.voice) }
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

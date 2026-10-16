package dev.glinesb.heygrok.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Where users get an xAI key. Kept in one place so the app and README stay in sync. */
object XaiKeyLinks {
    const val CONSOLE = "https://console.x.ai"
    const val API_KEYS = "https://console.x.ai/team/default/api-keys"
    const val DOCS = "https://docs.x.ai/developers/quickstart"

    val STEPS = listOf(
        "Sign up or log in at console.x.ai and verify your email.",
        "Add credits under Billing (the API is pay-as-you-go).",
        "Open API Keys (\"Get a key\" goes straight there) and create a new key.",
        "Copy the key (it's shown only once), paste it in the field above and tap Save key.",
    )
}

/** Opens a web link in a Custom Tab, falling back to a plain ACTION_VIEW, then to a toast. */
fun openLink(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: ActivityNotFoundException) {
            Toast.makeText(context, "No browser found to open $url", Toast.LENGTH_LONG).show()
        }
    }
}

/** Collapsible "How do I get this?" with numbered steps, a cost note and a docs link. */
@Composable
fun ApiKeyHelp(steps: List<String>, costNote: String, docsLabel: String, docsUrl: String) {
    val context = LocalContext.current
    var open by rememberSaveable(docsUrl) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }) { Text(if (open) "How do I get this? ▴" else "How do I get this? ▾") }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 4.dp)) {
                steps.forEachIndexed { i, s ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
                        Text(s, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(costNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                TextButton(onClick = { openLink(context, docsUrl) }) { Text(docsLabel) }
            }
        }
    }
}

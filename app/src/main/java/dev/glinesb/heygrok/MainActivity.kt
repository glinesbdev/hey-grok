package dev.glinesb.heygrok

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glinesb.heygrok.router.AppRouter
import dev.glinesb.heygrok.wake.WakeWordService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { HomeScreen() } } }
    }
}

private val requiredPermissions: Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val state by AppState.state.collectAsStateWithLifecycle()
    val router = remember { AppRouter(context) }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var perms by remember { mutableStateOf(requiredPermissions.associateWith { granted(it) }) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = requiredPermissions.associateWith { p -> granted(p) }
    }
    val micOk = perms[Manifest.permission.RECORD_AUDIO] == true

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Hey Grok", style = MaterialTheme.typography.headlineMedium)

        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        perms.forEach { (p, ok) -> Text("${if (ok) "✅" else "❌"} ${p.substringAfterLast('.')}") }
        if (perms.values.any { !it }) {
            Button(onClick = { launcher.launch(requiredPermissions) }) { Text("Grant permissions") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Listening", Modifier.weight(1f))
            Switch(
                checked = state.listening,
                enabled = micOk,
                onCheckedChange = { on ->
                    // Started from a visible activity → satisfies Android 14 mic FGS rule.
                    if (on) WakeWordService.start(context) else WakeWordService.stop(context)
                },
            )
        }
        Text("Status: ${state.status}")

        Text("Detected apps", style = MaterialTheme.typography.titleMedium)
        listOf(AppRouter.GROK, AppRouter.GROK_BOT).forEach { t ->
            val installed = router.isInstalled(t.packageName)
            Text("${if (installed) "✅" else "—"} ${t.name} (${t.packageName})")
        }

        Text("Last question", style = MaterialTheme.typography.titleMedium)
        Text(state.lastQuestion ?: "—")
        Text("Last result", style = MaterialTheme.typography.titleMedium)
        Text(state.lastResult ?: "— (handed off to app, or none yet)")
    }
}

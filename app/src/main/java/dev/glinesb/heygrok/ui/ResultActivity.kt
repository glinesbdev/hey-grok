package dev.glinesb.heygrok.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.glinesb.heygrok.ui.theme.HeyGrokTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.glinesb.heygrok.AppState
import dev.glinesb.heygrok.router.AppRouter
import dev.glinesb.heygrok.router.MissingAppButtons
import dev.glinesb.heygrok.settings.SecureSettings
import dev.glinesb.heygrok.xai.XaiClient

/** Fallback "answer overlay" when neither Grok app is installed. */
class ResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val question = intent.getStringExtra(EXTRA_Q).orEmpty()
        setContent {
            HeyGrokTheme {
                var answer by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(question) {
                    val a = XaiClient(SecureSettings(this@ResultActivity).apiKey).ask(question)
                    answer = a
                    AppState.update { it.copy(lastResult = a) }
                }
                Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState())) {
                    Text(question, style = MaterialTheme.typography.titleMedium)
                    Text(" ")
                    answer?.let { Text(it) } ?: CircularProgressIndicator()
                    Text(" ")
                    MissingAppButtons(remember { AppRouter(this@ResultActivity) })
                }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_Q = "question"
        fun intent(context: Context, question: String) =
            Intent(context, ResultActivity::class.java).putExtra(EXTRA_Q, question)
    }
}

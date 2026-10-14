package dev.glinesb.heygrok.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
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
import dev.glinesb.heygrok.settings.SecureSettings
import dev.glinesb.heygrok.xai.XaiClient

/** Fallback "answer overlay" when neither Grok app is installed. */
class ResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val question = intent.getStringExtra(EXTRA_Q).orEmpty()
        setContent {
            MaterialTheme {
                var answer by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(question) {
                    val a = XaiClient(SecureSettings(this@ResultActivity).apiKey).ask(question)
                    answer = a
                    AppState.update { it.copy(lastResult = a) }
                }
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    Text(question, style = MaterialTheme.typography.titleMedium)
                    Text(" ")
                    answer?.let { Text(it) } ?: CircularProgressIndicator()
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

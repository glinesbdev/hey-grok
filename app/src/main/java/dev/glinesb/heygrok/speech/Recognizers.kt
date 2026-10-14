package dev.glinesb.heygrok.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService

/** Finds a real (non-Hey-Grok) recognition service, preferring Google's on Pixel. */
object Recognizers {
    private val preferred = listOf("com.google.android.as", "com.google.android.tts", "com.google.android.googlequicksearchbox")

    fun external(context: Context): ComponentName? {
        val list = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }.filter { it.packageName != context.packageName }
        val pick = preferred.firstNotNullOfOrNull { p -> list.firstOrNull { it.packageName == p } } ?: list.firstOrNull()
        return pick?.let { ComponentName(it.packageName, it.name) }
    }
}

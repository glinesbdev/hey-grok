package dev.glinesb.heygrok.popup

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin

/** System-theme colours: Material You (dynamic) on Android 12+, static fallback below. */
internal data class Palette(val bg: Int, val fg: Int, val sub: Int, val accent: Int, val onAccent: Int) {
    companion object {
        fun of(c: Context): Palette {
            val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (Build.VERSION.SDK_INT >= 31) {
                fun col(id: Int) = c.getColor(id)
                if (night) Palette(col(android.R.color.system_neutral1_800), col(android.R.color.system_neutral1_50),
                    col(android.R.color.system_neutral2_200), col(android.R.color.system_accent1_200), col(android.R.color.system_accent1_800))
                else Palette(col(android.R.color.system_neutral1_50), col(android.R.color.system_neutral1_900),
                    col(android.R.color.system_neutral2_700), col(android.R.color.system_accent1_600), col(android.R.color.system_accent1_0))
            } else if (night) Palette(0xFF2B2930.toInt(), 0xFFE6E1E5.toInt(), 0xFFCAC4D0.toInt(), 0xFFD0BCFF.toInt(), 0xFF381E72.toInt())
            else Palette(0xFFF7F2FA.toInt(), 0xFF1D1B20.toInt(), 0xFF49454F.toInt(), 0xFF6750A4.toInt(), Color.WHITE)
        }
    }
}

/** Mic disc that breathes when idle and swells with the recognizer's RMS level. */
internal class MicPulseView(c: Context, private val p: Palette) : View(c) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.onAccent; textAlign = Paint.Align.CENTER }
    private var level = 0f
    private var t = 0f
    var active = true

    /** rmsdB from SpeechRecognizer is roughly -2 (silence) … 10 (loud). */
    fun setRms(db: Float) { level = 0.6f * level + 0.4f * ((db + 2f) / 12f).coerceIn(0f, 1f) }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) * 0.55f
        t += 0.12f
        val swell = if (active) 1f + 0.08f * sin(t) + 0.7f * level else 1f
        ring.alpha = 70
        canvas.drawCircle(cx, cy, (r * swell).coerceAtMost(minOf(cx, cy)), ring)
        canvas.drawCircle(cx, cy, r, disc)
        // simple mic glyph: capsule + stand
        glyph.style = Paint.Style.FILL
        val w = r * 0.34f; val h = r * 0.62f
        canvas.drawRoundRect(cx - w / 2, cy - h * 0.75f, cx + w / 2, cy + h * 0.25f, w / 2, w / 2, glyph)
        glyph.style = Paint.Style.STROKE; glyph.strokeWidth = r * 0.09f
        canvas.drawArc(cx - w, cy - h * 0.45f, cx + w, cy + h * 0.45f, 0f, 180f, false, glyph)
        canvas.drawLine(cx, cy + h * 0.45f, cx, cy + h * 0.7f, glyph)
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/** Bottom card used by the assistant-session pop-up and the "Display over other apps" bubble. */
class PopupCardView(c: Context) : LinearLayout(c) {
    private val p = Palette.of(c)
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private val mic = MicPulseView(c, p)
    private val title = TextView(c).apply { setTextColor(p.fg); textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
    private val body = TextView(c).apply { setTextColor(p.sub); textSize = 14f; maxLines = 3; ellipsize = TextUtils.TruncateAt.START }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(p.bg); cornerRadius = dp(28f) }
        elevation = dp(8f)
        val pad = dp(16f).toInt(); setPadding(pad, pad, pad * 3 / 2, pad)
        addView(mic, LayoutParams(dp(56f).toInt(), dp(56f).toInt()))
        addView(LinearLayout(c).apply {
            orientation = VERTICAL; setPadding(pad, 0, 0, 0)
            addView(title); addView(body)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        contentDescription = "Hey Grok listening"
    }

    fun render(m: ListeningPopup.Model) {
        title.text = m.title
        body.text = m.text ?: ""
        body.visibility = if (m.text.isNullOrBlank()) GONE else VISIBLE
        mic.active = m.phase == ListeningPopup.Phase.LISTENING
        mic.setRms(m.rms)
    }
}

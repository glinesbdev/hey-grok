package dev.glinesb.heygrok

import android.util.Log

/** Single log tag so `adb logcat -s HeyGrok` shows the whole pipeline. Never log API keys or audio. */
object HgLog {
    const val TAG = "HeyGrok"
    fun d(msg: String) = Log.d(TAG, msg)
    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String, t: Throwable? = null) = Log.w(TAG, msg, t)
    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}

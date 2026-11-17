package android.widget

import android.content.Context

/** A short notice: shown through the same host the shared screens' rememberToast uses on iOS. */
class Toast private constructor(private val text: CharSequence) {
    /** Shown by the shared screens' TopMessageHost when one is up; otherwise it goes to the log. */
    fun show() = com.tmplayer.platform.Logger.i("Toast", text.toString())
    fun cancel() = Unit

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        fun makeText(context: Context?, text: CharSequence, duration: Int): Toast = Toast(text)
        fun makeText(context: Context?, resId: Int, duration: Int): Toast = Toast("")
    }
}

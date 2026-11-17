package android.text.format

import android.content.Context

/** Android's formats that follow the user's settings: on iOS, Foundation's for the user's locale. */
object DateFormat {
    /** The time of day in the user's own style (12 or 24 hours, as iOS is set). */
    @Suppress("UNUSED_PARAMETER")
    fun getTimeFormat(context: Context): java.text.DateFormat = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)

    @Suppress("UNUSED_PARAMETER")
    fun getDateFormat(context: Context): java.text.DateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT)
}

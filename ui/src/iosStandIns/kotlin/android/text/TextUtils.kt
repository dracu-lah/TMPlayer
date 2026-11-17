package android.text

/** Android's text helpers, as much as the Views use. */
object TextUtils {
    enum class TruncateAt { START, MIDDLE, END, MARQUEE }

    fun isEmpty(str: CharSequence?): Boolean = str.isNullOrEmpty()

    fun equals(a: CharSequence?, b: CharSequence?): Boolean = a?.toString() == b?.toString()
}

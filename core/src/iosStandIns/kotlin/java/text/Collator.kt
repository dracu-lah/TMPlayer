package java.text

import com.tmplayer.platform.textCollator
import java.util.Locale

/** java.text.Collator over the pure half's [textCollator] (NSString's localized comparison on iOS). */
class Collator private constructor(private val locale: Locale) : Comparator<Any?> {
    var strength: Int = TERTIARY

    fun getStrength(): Int = strength
    fun setStrength(value: Int) {
        strength = value
    }

    override fun compare(a: Any?, b: Any?): Int =
        textCollator(locale, primary = strength == PRIMARY).compare(a?.toString().orEmpty(), b?.toString().orEmpty())

    fun equals(a: String, b: String): Boolean = compare(a, b) == 0

    companion object {
        const val PRIMARY = 0
        const val SECONDARY = 1
        const val TERTIARY = 2
        const val IDENTICAL = 3

        fun getInstance(): Collator = Collator(Locale.getDefault())
        fun getInstance(locale: Locale): Collator = Collator(locale)
    }
}

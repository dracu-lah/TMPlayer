package com.tmplayer.data

/**
 * Which way to support TMPlayer is offered first, by where the device says it is. UPI is how people
 * in India pay; Buy Me a Coffee takes cards and PayPal and is the first choice everywhere else.
 *
 * The region is only what the device already tells every app: the language's region (en-IN) and
 * the SIM's country, both readable without a permission. There is no network call, no IP lookup
 * and nothing is sent or counted anywhere. The viewer can always see the other way too.
 */
object SupportRegion {

    /** The two ways to pay. */
    enum class Method { Coffee, Upi }

    /** What the window draws: [first] as the full card on top, [second] under it. */
    data class Order(val first: Method, val second: Method) {
        /** True when the second way is a quiet row rather than a full card (outside India). */
        val secondIsQuiet: Boolean get() = first == Method.Coffee
    }

    private const val INDIA = "IN"

    /**
     * A region code made safe: trimmed and upper-cased, and only a two-letter one is kept. Locale
     * regions can be empty ("en"), numeric ("419") or odd, and a SIM string can be blank when
     * there is no SIM; all of those are no region.
     */
    fun clean(code: String?): String? {
        val text = code?.trim()?.uppercase().orEmpty()
        return text.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
    }

    /** Whether the device is in India by either answer: the language's region or the SIM. */
    fun isIndia(localeRegion: String?, simCountry: String?): Boolean =
        clean(localeRegion) == INDIA || clean(simCountry) == INDIA

    /** The order for the window and the reminder's first button. */
    fun order(localeRegion: String?, simCountry: String?): Order =
        if (isIndia(localeRegion, simCountry)) Order(Method.Upi, Method.Coffee) else Order(Method.Coffee, Method.Upi)
}

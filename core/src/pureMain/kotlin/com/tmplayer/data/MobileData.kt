package com.tmplayer.data

/**
 * What a video that has to come over mobile data does (R2, "On mobile data"): one question in place
 * of the twin switches Wi-Fi only and Save mobile data.
 *
 * Stored as [stored] under `mobile_data`. A phone that never chose has only the two old booleans,
 * which [resolve] reads, so nothing a viewer set is lost and nothing is rewritten until they pick.
 */
enum class MobileData(val stored: String) {
    /** A large video (see [MeteredPolicy.LARGE_DOWNLOAD_BYTES]) asks once per session. The default. */
    Ask("ask"),

    /** Nothing that is not already on the phone starts on mobile data: it waits for Wi-Fi. */
    Never("never"),

    /** Never asked: the viewer has said mobile data is fine. */
    Allow("allow"),
    ;

    /** Whether this choice stops a video that still needs the network on a metered connection. */
    val blocks: Boolean get() = this == Never

    /** Whether this choice asks before a large video starts on a metered connection. */
    val asks: Boolean get() = this == Ask

    companion object {
        /** The choice a stored word names, or null for no word, an unknown word or garbage. */
        fun parse(raw: String?): MobileData? = entries.firstOrNull { it.stored == raw }

        /**
         * The choice the two switches this replaced stand for: Wi-Fi only on is Never; "Save mobile
         * data" on, or both off (the safe default), is Ask first.
         */
        @Suppress("UNUSED_PARAMETER")
        fun fromLegacy(wifiOnly: Boolean, saveMobileData: Boolean): MobileData = if (wifiOnly) Never else Ask

        /** The stored word when it is one of ours, else what the old booleans say. */
        fun resolve(stored: String?, wifiOnly: Boolean, saveMobileData: Boolean): MobileData =
            parse(stored) ?: fromLegacy(wifiOnly, saveMobileData)
    }
}

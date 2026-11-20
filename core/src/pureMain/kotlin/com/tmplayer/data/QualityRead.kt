package com.tmplayer.data

/**
 * What picture a copy is, decided one way for every card, badge and order (HX38): the size
 * Telegram states when it states one, else the tag in the file's name, else nothing. A name that
 * says 720p over a file stated 1920 wide is a mislabelled 1080p, and the badge, the copies list
 * and the order all say so, with [mismatch] telling a caller that the name was wrong.
 *
 * Pure and total: a blank, odd or huge name gives null, never an error.
 */
object QualityRead {

    /** "4K", "1080p", "720p", "SD" or null when neither the stated size nor the name says. */
    fun of(item: MediaItem): String? =
        MediaFacts.resolutionOf(item.width, item.height) ?: named(item)

    /** The name's own tag, whatever the size says. */
    fun named(item: MediaItem): String? =
        (if (item.fileName.isBlank()) MediaText.qualityTags(item.title) else item.qualityTags).firstOrNull()

    /** True when the name and the stated size name two different pictures: the size is right. */
    fun mismatch(item: MediaItem): Boolean {
        val stated = MediaFacts.resolutionOf(item.width, item.height) ?: return false
        val name = named(item) ?: return false
        return stated != name
    }

    /** 4 for 4K down to 1 for SD, 0 for an unknown label. */
    fun rank(label: String): Int = when (label.uppercase()) {
        "4K" -> 4
        "1080P" -> 3
        "720P" -> 2
        "SD" -> 1
        else -> 0
    }

    /** The label a picture height (480, 720, 1080, 2160) goes by. */
    fun labelOfHeight(height: Int): String = when {
        height >= 2100 -> "4K"
        height >= 1060 -> "1080p"
        height >= 700 -> "720p"
        else -> "SD"
    }

    /**
     * A copy nobody means to watch where a better one exists: a cam, a telesync or a pre-DVD rip.
     * Read from the name only; a copy that names none of these is not low grade.
     */
    fun lowGrade(item: MediaItem): Boolean {
        val name = item.fileName.ifBlank { item.title }
        if (name.length > MAX_NAME) return false
        return LOW_GRADE.containsMatchIn(name)
    }

    private const val MAX_NAME = 600

    private val LOW_GRADE = Regex(
        """(?<![\p{L}\p{N}])(?:hd[-._ ]?cam|cam[-._ ]?rip|telesync|hd[-._ ]?ts|pre[-._ ]?dvd|hq[-._ ]?cam)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE,
    )
}

package android.text

/**
 * Android's text with marks over ranges of it (bold, a colour), for the code on iOS that writes and
 * reads them: Media3's subtitle cues and the SubtitleView that draws them.
 */
interface Spanned : CharSequence {
    /** Every span on the text with its range, in the order they were set; iOS's way to read them. */
    val spanRanges: List<SpanRange>

    fun getSpanStart(what: Any): Int = spanRanges.firstOrNull { it.what === what }?.start ?: -1

    fun getSpanEnd(what: Any): Int = spanRanges.firstOrNull { it.what === what }?.end ?: -1

    fun getSpanFlags(what: Any): Int = spanRanges.firstOrNull { it.what === what }?.flags ?: 0

    companion object {
        const val SPAN_INCLUSIVE_EXCLUSIVE = 17
        const val SPAN_INCLUSIVE_INCLUSIVE = 18
        const val SPAN_EXCLUSIVE_EXCLUSIVE = 33
        const val SPAN_EXCLUSIVE_INCLUSIVE = 34
        const val SPAN_PRIORITY = 0x00FF0000
    }
}

/** A span over [start] to [end] (exclusive) of a text. */
class SpanRange(val what: Any, val start: Int, val end: Int, val flags: Int)

/** Text whose spans can be set and taken off, Android's. */
interface Spannable : Spanned {
    fun setSpan(what: Any, start: Int, end: Int, flags: Int)

    fun removeSpan(what: Any)
}

/** Android's SpannableStringBuilder: text that grows, with its spans. */
class SpannableStringBuilder(text: CharSequence = "") : Spannable, Appendable {
    private val chars = StringBuilder(text)
    private val spans = ArrayList<SpanRange>()

    init {
        if (text is Spanned) spans.addAll(text.spanRanges)
    }

    override val spanRanges: List<SpanRange> get() = spans.toList()

    override val length: Int get() = chars.length

    override fun get(index: Int): Char = chars[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = SpannableStringBuilder().also { out ->
        out.chars.append(chars, startIndex, endIndex)
        for (span in spans) {
            val start = maxOf(span.start, startIndex)
            val end = minOf(span.end, endIndex)
            if (start < end || (start == end && span.start == span.end)) out.spans += SpanRange(span.what, start - startIndex, end - startIndex, span.flags)
        }
    }

    override fun toString(): String = chars.toString()

    override fun setSpan(what: Any, start: Int, end: Int, flags: Int) {
        if (start < 0 || end > chars.length || start > end) throw IndexOutOfBoundsException("span $start..$end of ${chars.length}")
        spans.removeAll { it.what === what }
        spans += SpanRange(what, start, end, flags)
    }

    override fun removeSpan(what: Any) {
        spans.removeAll { it.what === what }
    }

    override fun append(value: CharSequence?): SpannableStringBuilder = apply {
        val text = value ?: "null"
        val at = chars.length
        chars.append(text)
        if (text is Spanned) for (span in text.spanRanges) spans += SpanRange(span.what, span.start + at, span.end + at, span.flags)
    }

    override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): SpannableStringBuilder =
        append((value ?: "null").subSequence(startIndex, endIndex))

    override fun append(value: Char): SpannableStringBuilder = apply { chars.append(value) }
}

/** Android's SpannableString: fixed text with spans. */
class SpannableString(text: CharSequence) : Spannable {
    private val builder = SpannableStringBuilder(text)

    override val spanRanges: List<SpanRange> get() = builder.spanRanges

    override val length: Int get() = builder.length

    override fun get(index: Int): Char = builder[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = builder.subSequence(startIndex, endIndex)

    override fun toString(): String = builder.toString()

    override fun setSpan(what: Any, start: Int, end: Int, flags: Int) = builder.setSpan(what, start, end, flags)

    override fun removeSpan(what: Any) = builder.removeSpan(what)

    companion object {
        fun valueOf(source: CharSequence): SpannableString = SpannableString(source)
    }
}

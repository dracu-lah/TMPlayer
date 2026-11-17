package androidx.media3.common.text

// Media3's subtitle cues on iOS: the text subtitles the engine hands over (EngineCues), which
// upstream's player puts into its SubtitleView as on Android.

/** One subtitle cue, Media3's: text with its spans, where it stands, how large. */
class Cue private constructor(
    val text: CharSequence?,
    val textAlignment: android.text.Layout.Alignment?,
    val bitmap: android.graphics.Bitmap?,
    val line: Float,
    val lineType: Int,
    val lineAnchor: Int,
    val position: Float,
    val positionAnchor: Int,
    val size: Float,
    val textSize: Float,
    val textSizeType: Int,
    val windowColor: Int,
    val windowColorSet: Boolean,
) {
    fun buildUpon(): Builder = Builder().setText(text).setTextAlignment(textAlignment).setBitmap(bitmap).setLine(line, lineType)
        .setLineAnchor(lineAnchor).setPosition(position).setPositionAnchor(positionAnchor).setSize(size)
        .setTextSize(textSize, textSizeType).also { if (windowColorSet) it.setWindowColor(windowColor) }

    /** Media3's cue builder: text or a picture, where it sits, how large. */
    class Builder {
        private var text: CharSequence? = null
        private var textAlignment: android.text.Layout.Alignment? = null
        private var bitmap: android.graphics.Bitmap? = null
        private var line = DIMEN_UNSET
        private var lineType = TYPE_UNSET
        private var lineAnchor = TYPE_UNSET
        private var position = DIMEN_UNSET
        private var positionAnchor = TYPE_UNSET
        private var size = DIMEN_UNSET
        private var textSize = DIMEN_UNSET
        private var textSizeType = TYPE_UNSET
        private var windowColor = 0xFF000000.toInt()
        private var windowColorSet = false

        fun setText(text: CharSequence?): Builder = apply { this.text = text }

        fun getText(): CharSequence? = text

        fun setTextAlignment(textAlignment: android.text.Layout.Alignment?): Builder = apply { this.textAlignment = textAlignment }

        fun setLineAnchor(lineAnchor: Int): Builder = apply { this.lineAnchor = lineAnchor }

        fun getLineAnchor(): Int = lineAnchor

        fun setPositionAnchor(positionAnchor: Int): Builder = apply { this.positionAnchor = positionAnchor }

        fun getPositionAnchor(): Int = positionAnchor

        fun setBitmap(bitmap: android.graphics.Bitmap?): Builder = apply { this.bitmap = bitmap }

        fun setLine(line: Float, lineType: Int): Builder = apply {
            this.line = line
            this.lineType = lineType
        }

        fun setPosition(position: Float): Builder = apply { this.position = position }

        fun setSize(size: Float): Builder = apply { this.size = size }

        fun setTextSize(textSize: Float, textSizeType: Int): Builder = apply {
            this.textSize = textSize
            this.textSizeType = textSizeType
        }

        fun setWindowColor(windowColor: Int): Builder = apply {
            this.windowColor = windowColor
            windowColorSet = true
        }

        fun build(): Cue =
            Cue(text, textAlignment, bitmap, line, lineType, lineAnchor, position, positionAnchor, size, textSize, textSizeType, windowColor, windowColorSet)
    }

    companion object {
        val EMPTY: Cue = Builder().setText("").build()
        const val DIMEN_UNSET = -Float.MAX_VALUE
        const val TYPE_UNSET = Int.MIN_VALUE
        const val LINE_TYPE_FRACTION = 0
        const val LINE_TYPE_NUMBER = 1
        const val ANCHOR_TYPE_START = 0
        const val ANCHOR_TYPE_MIDDLE = 1
        const val ANCHOR_TYPE_END = 2
        const val TEXT_SIZE_TYPE_FRACTIONAL = 0
        const val TEXT_SIZE_TYPE_FRACTIONAL_IGNORE_PADDING = 1
        const val TEXT_SIZE_TYPE_ABSOLUTE = 2
    }
}

class CueGroup(val cues: List<Cue>, val presentationTimeUs: Long) {
    companion object {
        val EMPTY_TIME_ZERO: CueGroup = CueGroup(emptyList(), 0)
    }
}

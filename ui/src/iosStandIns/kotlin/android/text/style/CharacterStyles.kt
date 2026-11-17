package android.text.style

// Android's character spans that Media3's subtitle decoders set and its SubtitleView draws.

/** Bold, italic or both, as android.graphics.Typeface's styles. */
class StyleSpan(val style: Int)

class UnderlineSpan

class StrikethroughSpan

class ForegroundColorSpan(val foregroundColor: Int)

class BackgroundColorSpan(val backgroundColor: Int)

/** The text at [size] pixels ([dip]: density-independent pixels). */
class AbsoluteSizeSpan(val size: Int, val dip: Boolean = false)

/** The text at [sizeChange] times the size around it. */
class RelativeSizeSpan(val sizeChange: Float)

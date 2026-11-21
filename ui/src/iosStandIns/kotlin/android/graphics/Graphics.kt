package android.graphics

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.PathDirection
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.RRect

// Android's 2D drawing on iOS, over Skia: the library Android's own Canvas draws with, and the one
// Compose draws with on iOS. Android's Views (the player's controls, docs/IOS.md, layer 3)
// draw through these as they do on Android.

/** Android's colour helpers: colours are ARGB ints. */
object Color {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val TRANSPARENT = 0
    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val GRAY = 0xFF888888.toInt()
    const val DKGRAY = 0xFF444444.toInt()
    const val LTGRAY = 0xFFCCCCCC.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()

    fun alpha(color: Int): Int = color ushr 24

    fun red(color: Int): Int = (color shr 16) and 0xFF

    fun green(color: Int): Int = (color shr 8) and 0xFF

    fun blue(color: Int): Int = color and 0xFF

    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    fun rgb(red: Int, green: Int, blue: Int): Int = argb(255, red, green, blue)

    /** "#RRGGBB", "#AARRGGBB", "#RGB" or "#ARGB", as Android reads them. */
    fun parseColor(colorString: String): Int {
        val hex = colorString.trim().removePrefix("#")
        val full = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> throw IllegalArgumentException("Unknown color $colorString")
        }
        return full.toLong(16).toInt()
    }
}

/** An integer rectangle, Android's. */
class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0) {
    constructor(other: Rect) : this(other.left, other.top, other.right, other.bottom)

    fun width(): Int = right - left

    fun height(): Int = bottom - top

    fun centerX(): Int = (left + right) / 2

    fun centerY(): Int = (top + bottom) / 2

    fun exactCenterX(): Float = (left + right) / 2.0f

    fun exactCenterY(): Float = (top + bottom) / 2.0f

    fun isEmpty(): Boolean = left >= right || top >= bottom

    /** Java's isEmpty(), as Kotlin also reads it. */
    val isEmpty: Boolean get() = isEmpty()

    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun set(other: Rect) = set(other.left, other.top, other.right, other.bottom)

    fun setEmpty() = set(0, 0, 0, 0)

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    fun offset(dx: Int, dy: Int) = set(left + dx, top + dy, right + dx, bottom + dy)

    fun inset(dx: Int, dy: Int) = set(left + dx, top + dy, right - dx, bottom - dy)

    override fun equals(other: Any?): Boolean =
        other is Rect && other.left == left && other.top == top && other.right == right && other.bottom == bottom

    override fun hashCode(): Int = ((left * 31 + top) * 31 + right) * 31 + bottom

    override fun toString(): String = "Rect($left, $top - $right, $bottom)"
}

/** A float rectangle, Android's. */
class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    constructor(rect: Rect) : this(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())

    fun width(): Float = right - left

    fun height(): Float = bottom - top

    fun centerX(): Float = (left + right) / 2f

    fun centerY(): Float = (top + bottom) / 2f

    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun set(rect: RectF) = set(rect.left, rect.top, rect.right, rect.bottom)

    fun set(rect: Rect) = set(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())

    fun inset(dx: Float, dy: Float) = set(left + dx, top + dy, right - dx, bottom - dy)

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    internal fun skia(): org.jetbrains.skia.Rect = org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom)
}

/** Android's typefaces, as the system fonts of iOS. */
class Typeface private constructor(internal val skia: org.jetbrains.skia.Typeface?, val style: Int, internal val weight: Int) {
    val isBold: Boolean get() = style and BOLD != 0

    val isItalic: Boolean get() = style and ITALIC != 0

    // A style asked for that the font itself does not have, which Paint then fakes, as Android's.
    internal val lacksBold: Boolean get() = isBold && (skia?.fontStyle?.weight ?: 400) < 600
    internal val lacksItalic: Boolean get() = isItalic && skia?.fontStyle?.slant == org.jetbrains.skia.FontSlant.UPRIGHT

    companion object {
        const val NORMAL = 0
        const val BOLD = 1
        const val ITALIC = 2
        const val BOLD_ITALIC = 3

        private val cache = HashMap<String, Typeface>()

        /** A family by Android's names ("sans-serif", "sans-serif-medium", "serif", "monospace") at [weight]. */
        fun of(family: String?, weight: Int, italic: Boolean): Typeface {
            val key = "$family/$weight/$italic"
            return cache.getOrPut(key) {
                // Android's families by the iOS fonts closest to them; Skia on iOS finds fonts by
                // their family names only, so each has a list to try in order.
                val names = when (family?.substringBefore("-medium")?.substringBefore("-light")?.substringBefore("-condensed")) {
                    "serif" -> listOf("Times New Roman", "Georgia")
                    "monospace" -> listOf("Menlo", "Courier New")
                    else -> listOf("Roboto", "Helvetica Neue", "Helvetica", "Arial")
                }
                val slant = if (italic) FontStyle.ITALIC.slant else FontStyle.NORMAL.slant
                val skiaStyle = FontStyle(weight, FontStyle.NORMAL.width, slant)
                val typeface = names.firstNotNullOfOrNull { name -> runCatching { FontMgr.default.matchFamilyStyle(name, skiaStyle) }.getOrNull() }
                Typeface(typeface, (if (weight >= 600) BOLD else 0) or (if (italic) ITALIC else 0), weight)
            }
        }

        fun create(familyName: String?, style: Int): Typeface {
            val weight = if (style and BOLD != 0) 700 else if (familyName?.endsWith("-medium") == true) 500 else 400
            return of(familyName, weight, style and ITALIC != 0)
        }

        /**
         * [family] in [style], Android's: the same family's bold or italic where it has one; where it
         * has not, the family's face asked in that style, which Paint draws with fake bold or a slant.
         */
        fun create(family: Typeface?, style: Int): Typeface {
            val weight = if (style and BOLD != 0) 700 else family?.weight ?: 400
            val italic = style and ITALIC != 0
            val face = family?.skia ?: return of(null, weight, italic)
            if (style == family.style) return family
            return cache.getOrPut("face:${face.familyName}/$weight/$italic") {
                val slant = if (italic) FontStyle.ITALIC.slant else FontStyle.NORMAL.slant
                val match = runCatching { FontMgr.default.matchFamilyStyle(face.familyName, FontStyle(weight, FontStyle.NORMAL.width, slant)) }.getOrNull()
                // A font from a file has no family the system knows: its own face, faked.
                val same = match?.takeIf { it.familyName == face.familyName } ?: face
                Typeface(same, style and BOLD_ITALIC, weight)
            }
        }

        fun create(family: Typeface?, weight: Int, italic: Boolean): Typeface = of(null, weight, italic)

        /**
         * A font file from :app's assets (bundled as assets/), Android's; Android throws a
         * RuntimeException when the file is not there, and so does this.
         */
        fun createFromAsset(assets: android.content.res.AssetManager, path: String): Typeface {
            val file = assets.pathOf(path) ?: throw RuntimeException("Font asset not found $path")
            val face = FontMgr.default.makeFromFile(file) ?: throw RuntimeException("Font asset not found $path")
            return Typeface(face, NORMAL, 400)
        }

        val DEFAULT: Typeface get() = of("sans-serif", 400, false)
        val DEFAULT_BOLD: Typeface get() = of("sans-serif", 700, false)
        val SANS_SERIF: Typeface get() = of("sans-serif", 400, false)
        val SERIF: Typeface get() = of("serif", 400, false)
        val MONOSPACE: Typeface get() = of("monospace", 400, false)
    }
}

/** How Android blends a tint onto a drawing. */
object PorterDuff {
    enum class Mode { CLEAR, SRC, DST, SRC_OVER, DST_OVER, SRC_IN, DST_IN, SRC_OUT, DST_OUT, SRC_ATOP, DST_ATOP, XOR, MULTIPLY, SCREEN, ADD, OVERLAY }
}

/** A colour filter, Android's. */
open class ColorFilter internal constructor(internal val skia: org.jetbrains.skia.ColorFilter)

/** A tint, Android's: [color] blended in [mode]. */
class PorterDuffColorFilter(val color: Int, val mode: PorterDuff.Mode) : ColorFilter(
    org.jetbrains.skia.ColorFilter.makeBlend(color, blendOf(mode)),
)

internal fun blendOf(mode: PorterDuff.Mode): org.jetbrains.skia.BlendMode = when (mode) {
    PorterDuff.Mode.CLEAR -> org.jetbrains.skia.BlendMode.CLEAR
    PorterDuff.Mode.SRC -> org.jetbrains.skia.BlendMode.SRC
    PorterDuff.Mode.DST -> org.jetbrains.skia.BlendMode.DST
    PorterDuff.Mode.SRC_OVER -> org.jetbrains.skia.BlendMode.SRC_OVER
    PorterDuff.Mode.DST_OVER -> org.jetbrains.skia.BlendMode.DST_OVER
    PorterDuff.Mode.SRC_IN -> org.jetbrains.skia.BlendMode.SRC_IN
    PorterDuff.Mode.DST_IN -> org.jetbrains.skia.BlendMode.DST_IN
    PorterDuff.Mode.SRC_OUT -> org.jetbrains.skia.BlendMode.SRC_OUT
    PorterDuff.Mode.DST_OUT -> org.jetbrains.skia.BlendMode.DST_OUT
    PorterDuff.Mode.SRC_ATOP -> org.jetbrains.skia.BlendMode.SRC_ATOP
    PorterDuff.Mode.DST_ATOP -> org.jetbrains.skia.BlendMode.DST_ATOP
    PorterDuff.Mode.XOR -> org.jetbrains.skia.BlendMode.XOR
    PorterDuff.Mode.MULTIPLY -> org.jetbrains.skia.BlendMode.MODULATE
    PorterDuff.Mode.SCREEN -> org.jetbrains.skia.BlendMode.SCREEN
    PorterDuff.Mode.ADD -> org.jetbrains.skia.BlendMode.PLUS
    PorterDuff.Mode.OVERLAY -> org.jetbrains.skia.BlendMode.OVERLAY
}

/** A shader, Android's. */
open class Shader internal constructor(internal val skia: org.jetbrains.skia.Shader) {
    enum class TileMode { CLAMP, REPEAT, MIRROR }
}

/** A linear gradient between points, Android's. */
class LinearGradient(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, positions: FloatArray?, tile: TileMode) :
    Shader(
        org.jetbrains.skia.Shader.makeLinearGradient(
            x0, y0, x1, y1,
            org.jetbrains.skia.Gradient(
                org.jetbrains.skia.Gradient.Colors(
                    colors.map { org.jetbrains.skia.Color4f(it) }.toTypedArray(),
                    positions,
                    when (tile) {
                        TileMode.CLAMP -> org.jetbrains.skia.FilterTileMode.CLAMP
                        TileMode.REPEAT -> org.jetbrains.skia.FilterTileMode.REPEAT
                        TileMode.MIRROR -> org.jetbrains.skia.FilterTileMode.MIRROR
                    },
                ),
            ),
        ),
    ) {
    constructor(x0: Float, y0: Float, x1: Float, y1: Float, color0: Int, color1: Int, tile: TileMode) :
        this(x0, y0, x1, y1, intArrayOf(color0, color1), null, tile)
}

/** How a shape is drawn, Android's, over Skia's paint. */
class Paint(flags: Int = 0) {
    internal val skia = org.jetbrains.skia.Paint().apply { isAntiAlias = flags and ANTI_ALIAS_FLAG != 0 }
    // No typeface is the default one, as Android's Paint: Skia's own null face has no metrics.
    private var font = org.jetbrains.skia.Font(Typeface.DEFAULT.skia, 12f)

    constructor(other: Paint) : this() {
        color = other.color
        style = other.style
        strokeWidth = other.strokeWidth
        strokeCap = other.strokeCap
        strokeJoin = other.strokeJoin
        isAntiAlias = other.isAntiAlias
        textSize = other.textSize
        typeface = other.typeface
        isFakeBoldText = other.isFakeBoldText
        textSkewX = other.textSkewX
        textAlign = other.textAlign
        colorFilter = other.colorFilter
        shader = other.shader
    }

    enum class Style { FILL, STROKE, FILL_AND_STROKE }

    enum class Cap { BUTT, ROUND, SQUARE }

    enum class Join { MITER, ROUND, BEVEL }

    enum class Align { LEFT, CENTER, RIGHT }

    /** The text's measures, Android's. */
    class FontMetrics {
        var top = 0f
        var ascent = 0f
        var descent = 0f
        var bottom = 0f
        var leading = 0f
    }

    var color: Int
        get() = skia.color
        set(value) {
            skia.color = value
        }

    var alpha: Int
        get() = Color.alpha(skia.color)
        set(value) {
            skia.color = (skia.color and 0x00FFFFFF) or ((value.coerceIn(0, 255)) shl 24)
        }

    var style: Style = Style.FILL
        set(value) {
            field = value
            skia.mode = when (value) {
                Style.FILL -> PaintMode.FILL
                Style.STROKE -> PaintMode.STROKE
                Style.FILL_AND_STROKE -> PaintMode.STROKE_AND_FILL
            }
        }

    var strokeWidth: Float
        get() = skia.strokeWidth
        set(value) {
            skia.strokeWidth = value
        }

    var strokeCap: Cap = Cap.BUTT
        set(value) {
            field = value
            skia.strokeCap = when (value) {
                Cap.BUTT -> PaintStrokeCap.BUTT
                Cap.ROUND -> PaintStrokeCap.ROUND
                Cap.SQUARE -> PaintStrokeCap.SQUARE
            }
        }

    var strokeJoin: Join = Join.MITER
        set(value) {
            field = value
            skia.strokeJoin = when (value) {
                Join.MITER -> PaintStrokeJoin.MITER
                Join.ROUND -> PaintStrokeJoin.ROUND
                Join.BEVEL -> PaintStrokeJoin.BEVEL
            }
        }

    var isAntiAlias: Boolean
        get() = skia.isAntiAlias
        set(value) {
            skia.isAntiAlias = value
        }

    var colorFilter: ColorFilter? = null
        set(value) {
            field = value
            skia.colorFilter = value?.skia
        }

    var shader: Shader? = null
        set(value) {
            field = value
            skia.shader = value?.skia
        }

    var textSize: Float = 12f
        set(value) {
            field = value
            remakeFont()
        }

    var typeface: Typeface? = null
        set(value) {
            field = value
            remakeFont()
        }

    /** Bold drawn by thickening the strokes, Android's, also used when the typeface asks for a bold its font lacks. */
    var isFakeBoldText: Boolean = false
        set(value) {
            field = value
            remakeFont()
        }

    /** The text slanted, Android's; -0.25 is the slant Android gives an italic its font lacks. */
    var textSkewX: Float = 0f
        set(value) {
            field = value
            remakeFont()
        }

    /** Takes [other]'s colour, fill or stroke and shadow, keeping this paint's text: for a layout drawing runs of one text. */
    internal fun takeLook(other: Paint) {
        color = other.color
        style = other.style
        strokeWidth = other.strokeWidth
        strokeJoin = other.strokeJoin
        skia.imageFilter = other.skia.imageFilter
    }

    // As Android's Paint: a typeface whose style asks for a bold or an italic its font does not
    // have is drawn with fake bold or a slant.
    private fun remakeFont() {
        val face = typeface
        font = org.jetbrains.skia.Font((face ?: Typeface.DEFAULT).skia, textSize).also {
            it.isEmboldened = isFakeBoldText || face?.lacksBold == true
            it.skewX = if (textSkewX != 0f) textSkewX else if (face?.lacksItalic == true) -0.25f else 0f
        }
        forgetLines()
    }

    // Text shaped once and kept: measured and drawn from the same shaping, as Android's layouts
    // are, with the system's fallback fonts for what the typeface lacks (a title in another
    // script, an emoji). Skia's own measureTextWidth comes out narrower than the text it draws
    // on iOS, which pushed right-aligned text past its edge.
    private val shaped = LinkedHashMap<String, org.jetbrains.skia.TextLine>()

    private fun forgetLines() {
        shaped.values.forEach { it.close() }
        shaped.clear()
    }

    internal fun line(text: String): org.jetbrains.skia.TextLine {
        shaped[text]?.let { return it }
        if (shaped.size >= SHAPED_LINES) {
            val oldest = shaped.keys.first()
            shaped.remove(oldest)?.close()
        }
        return org.jetbrains.skia.TextLine.make(text, font).also { shaped[text] = it }
    }

    var textAlign: Align = Align.LEFT


    fun setShadowLayer(radius: Float, dx: Float, dy: Float, shadowColor: Int) {
        skia.imageFilter = if (radius <= 0f) null else org.jetbrains.skia.ImageFilter.makeDropShadow(dx, dy, radius / 2f, radius / 2f, shadowColor)
    }

    fun clearShadowLayer() {
        skia.imageFilter = null
    }

    fun measureText(text: String): Float = if (text.isEmpty()) 0f else line(text).width

    /**
     * Whether [string] draws without a missing-glyph box, Android's: in the paint's typeface or a
     * system font that falls back for it, as the text drawn here does.
     */
    fun hasGlyph(string: String): Boolean {
        if (string.isEmpty()) return false
        var i = 0
        while (i < string.length) {
            val high = string[i]
            val code = if (high.isHighSurrogate() && i + 1 < string.length) {
                ((high.code - 0xD800) shl 10) + (string[i + 1].code - 0xDC00) + 0x10000
            } else {
                high.code
            }
            i += if (code > 0xFFFF) 2 else 1
            if (font.getUTF32Glyph(code) != 0.toShort()) continue
            if (FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.NORMAL, null, code) == null) return false
        }
        return true
    }

    fun measureText(text: CharSequence, start: Int, end: Int): Float = measureText(text.subSequence(start, end).toString())

    /** How far the font rises above the baseline (negative) and falls below it, Android's. */
    fun ascent(): Float = font.metrics.ascent

    fun descent(): Float = font.metrics.descent

    fun getFontMetrics(): FontMetrics {
        val m = font.metrics
        return FontMetrics().also {
            it.top = m.top
            it.ascent = m.ascent
            it.descent = m.descent
            it.bottom = m.bottom
            it.leading = m.leading
        }
    }

    fun getFontMetrics(metrics: FontMetrics): Float {
        val m = font.metrics
        metrics.top = m.top
        metrics.ascent = m.ascent
        metrics.descent = m.descent
        metrics.bottom = m.bottom
        metrics.leading = m.leading
        return m.descent - m.ascent + m.leading
    }

    internal fun skiaFont(): org.jetbrains.skia.Font = font

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
        const val DITHER_FLAG = 4

        /** Shaped texts a paint keeps: a View's few labels, without growing on a changing clock. */
        private const val SHAPED_LINES = 16
    }
}

/** A path of lines and curves, Android's, built with Skia's path builder. */
class Path() {
    private val builder = org.jetbrains.skia.PathBuilder()
    private var built: org.jetbrains.skia.Path? = null

    constructor(src: Path) : this() {
        builder.addPath(src.skia)
        fillType = src.fillType
    }

    /** The path as Skia draws it, made again only after a change. */
    internal val skia: org.jetbrains.skia.Path
        get() = built ?: builder.snapshot().also {
            it.fillMode = fillMode()
            built = it
        }

    enum class Direction { CW, CCW }

    enum class FillType { WINDING, EVEN_ODD, INVERSE_WINDING, INVERSE_EVEN_ODD }

    var fillType: FillType = FillType.WINDING
        set(value) {
            field = value
            built = null
        }

    private fun fillMode(): PathFillMode = when (fillType) {
        FillType.WINDING -> PathFillMode.WINDING
        FillType.EVEN_ODD -> PathFillMode.EVEN_ODD
        FillType.INVERSE_WINDING -> PathFillMode.INVERSE_WINDING
        FillType.INVERSE_EVEN_ODD -> PathFillMode.INVERSE_EVEN_ODD
    }

    private inline fun change(block: org.jetbrains.skia.PathBuilder.() -> Unit) {
        builder.block()
        built = null
    }

    fun reset() = change {
        reset()
        fillType = FillType.WINDING
    }

    fun rewind() = reset()

    fun isEmpty(): Boolean = skia.isEmpty

    fun moveTo(x: Float, y: Float) = change { moveTo(x, y) }

    fun rMoveTo(dx: Float, dy: Float) = change { rMoveTo(dx, dy) }

    fun lineTo(x: Float, y: Float) = change { lineTo(x, y) }

    fun rLineTo(dx: Float, dy: Float) = change { rLineTo(dx, dy) }

    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) = change { quadTo(x1, y1, x2, y2) }

    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = change { cubicTo(x1, y1, x2, y2, x3, y3) }

    fun arcTo(oval: RectF, startAngle: Float, sweepAngle: Float, forceMoveTo: Boolean) =
        change { arcTo(oval.skia(), startAngle, sweepAngle, forceMoveTo) }

    fun arcTo(oval: RectF, startAngle: Float, sweepAngle: Float) = arcTo(oval, startAngle, sweepAngle, false)

    fun close() = change { closePath() }

    fun addRect(left: Float, top: Float, right: Float, bottom: Float, dir: Direction) =
        change { addRect(org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom), dirOf(dir)) }

    fun addRect(rect: RectF, dir: Direction) = addRect(rect.left, rect.top, rect.right, rect.bottom, dir)

    fun addRoundRect(rect: RectF, rx: Float, ry: Float, dir: Direction) =
        change { addRRect(RRect.makeLTRB(rect.left, rect.top, rect.right, rect.bottom, rx, ry), dirOf(dir)) }

    fun addRoundRect(rect: RectF, radii: FloatArray, dir: Direction) =
        change { addRRect(RRect.makeComplexLTRB(rect.left, rect.top, rect.right, rect.bottom, radii), dirOf(dir)) }

    fun addCircle(x: Float, y: Float, radius: Float, dir: Direction) = change { addCircle(x, y, radius, dirOf(dir)) }

    fun addOval(oval: RectF, dir: Direction) = change { addOval(oval.skia(), dirOf(dir)) }

    fun addPath(src: Path) = change { addPath(src.skia) }

    fun offset(dx: Float, dy: Float) = change { offset(dx, dy) }

    private fun dirOf(dir: Direction) = if (dir == Direction.CW) PathDirection.CLOCKWISE else PathDirection.COUNTER_CLOCKWISE
}

/** Where a View draws, Android's, over a Skia canvas. */
class Canvas(internal val skia: org.jetbrains.skia.Canvas) {
    /**
     * Android's canvas over [bitmap]. Here a picture of its size that is drawn on and let go: the
     * one caller (the player's neutral media icon, HX88) needs no pixels back on iOS, where Now
     * Playing shows no artwork while a PIN profile is hidden.
     */
    constructor(bitmap: Bitmap) : this(org.jetbrains.skia.Surface.makeRasterN32Premul(bitmap.width.coerceAtLeast(1), bitmap.height.coerceAtLeast(1)).canvas)

    fun save(): Int = skia.save()

    fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int): Int =
        skia.saveLayer(org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom), org.jetbrains.skia.Paint().apply { this.alpha = alpha })

    fun restore() = skia.restore()

    fun restoreToCount(count: Int) = skia.restoreToCount(count)

    fun translate(dx: Float, dy: Float) {
        skia.translate(dx, dy)
    }

    fun scale(sx: Float, sy: Float) {
        skia.scale(sx, sy)
    }

    fun scale(sx: Float, sy: Float, px: Float, py: Float) {
        skia.translate(px, py)
        skia.scale(sx, sy)
        skia.translate(-px, -py)
    }

    fun rotate(degrees: Float) {
        skia.rotate(degrees)
    }

    fun rotate(degrees: Float, px: Float, py: Float) {
        skia.translate(px, py)
        skia.rotate(degrees)
        skia.translate(-px, -py)
    }

    fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        skia.clipRect(org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom), true)
        return true
    }

    fun clipRect(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        clipRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    fun clipRect(rect: Rect): Boolean = clipRect(rect.left, rect.top, rect.right, rect.bottom)

    fun clipRect(rect: RectF): Boolean = clipRect(rect.left, rect.top, rect.right, rect.bottom)

    /** A rounded rectangle clip, for a View that clips to its outline. */
    internal fun clipRoundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        skia.clipRRect(org.jetbrains.skia.RRect.makeLTRB(left, top, right, bottom, radius), true)
    }

    fun clipPath(path: Path): Boolean {
        skia.clipPath(path.skia, true)
        return true
    }

    fun drawColor(color: Int) {
        skia.drawPaint(org.jetbrains.skia.Paint().apply { this.color = color })
    }

    fun drawColor(color: Int, mode: PorterDuff.Mode) {
        skia.drawPaint(org.jetbrains.skia.Paint().apply {
            this.color = color
            blendMode = blendOf(mode)
        })
    }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        skia.drawRect(org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom), paint.skia)
    }

    fun drawRect(rect: RectF, paint: Paint) = drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)

    fun drawRect(rect: Rect, paint: Paint) = drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)

    fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
        skia.drawRRect(RRect.makeLTRB(rect.left, rect.top, rect.right, rect.bottom, rx, ry), paint.skia)
    }

    fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) =
        drawRoundRect(RectF(left, top, right, bottom), rx, ry, paint)

    fun drawOval(oval: RectF, paint: Paint) {
        skia.drawOval(oval.skia(), paint.skia)
    }

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        skia.drawCircle(cx, cy, radius, paint.skia)
    }

    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        skia.drawLine(startX, startY, stopX, stopY, paint.skia)
    }

    fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) {
        skia.drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter, paint.skia)
    }

    fun drawPath(path: Path, paint: Paint) {
        skia.drawPath(path.skia, paint.skia)
    }

    /** Text from its baseline at [y], aligned as the paint says, Android's. */
    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        val width = paint.measureText(text)
        val left = when (paint.textAlign) {
            Paint.Align.LEFT -> x
            Paint.Align.CENTER -> x - width / 2f
            Paint.Align.RIGHT -> x - width
        }
        if (text.isNotEmpty()) skia.drawTextLine(paint.line(text), left, y, paint.skia)
    }

    fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) =
        drawText(text.subSequence(start, end).toString(), x, y, paint)
}

/**
 * A picture in memory, Android's, over Skia's image. Its pixels change only by a copy into it
 * (PixelCopy into a Bitmap made blank, as Android's), which puts a new image in its place.
 */
class Bitmap internal constructor(image: org.jetbrains.skia.Image) {
    internal var image: org.jetbrains.skia.Image = image
        private set

    /** [source] drawn over the whole of this picture, scaled to its size: what a copy into it leaves. */
    fun copyFrom(source: Bitmap) {
        image = if (source.width == width && source.height == height) source.image else createScaledBitmap(source, width, height, true).image
    }

    val width: Int get() = image.width

    val height: Int get() = image.height

    var isRecycled: Boolean = false
        private set

    fun recycle() {
        isRecycled = true
    }

    fun getByteCount(): Int = width * height * 4

    val byteCount: Int get() = getByteCount()

    val config: Config get() = Config.ARGB_8888

    /** Android's pixel layouts; every Bitmap here is ARGB_8888. */
    enum class Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE }

    /** Android's file formats for [compress]. */
    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    /** Writes the picture to [stream] as [format] at [quality] (0 to 100), Android's; false when it could not. */
    fun compress(format: CompressFormat, quality: Int, stream: java.io.OutputStream): Boolean {
        val skiaFormat = when (format) {
            CompressFormat.JPEG -> org.jetbrains.skia.EncodedImageFormat.JPEG
            CompressFormat.PNG -> org.jetbrains.skia.EncodedImageFormat.PNG
            else -> org.jetbrains.skia.EncodedImageFormat.WEBP
        }
        val data = image.encodeToData(skiaFormat, quality.coerceIn(0, 100)) ?: return false
        return runCatching { stream.write(data.bytes) }.isSuccess
    }

    companion object {
        /** [source] drawn at [width] by [height], smoothed when [filter]. */
        fun createScaledBitmap(source: Bitmap, width: Int, height: Int, filter: Boolean): Bitmap {
            if (width == source.width && height == source.height) return source
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(width.coerceAtLeast(1), height.coerceAtLeast(1))
            val paint = org.jetbrains.skia.Paint().apply { isAntiAlias = filter }
            surface.canvas.drawImageRect(
                source.image,
                org.jetbrains.skia.Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()),
                if (filter) org.jetbrains.skia.SamplingMode.LINEAR else org.jetbrains.skia.SamplingMode.DEFAULT,
                paint,
                true,
            )
            return Bitmap(surface.makeImageSnapshot())
        }

        /** A Bitmap over a Skia image, for Compose's pictures on iOS. */
        internal fun fromSkia(image: org.jetbrains.skia.Image): Bitmap = Bitmap(image)

        /** A blank picture of [width] by [height] to draw or copy into, Android's. */
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            require(width > 0 && height > 0) { "width and height must be > 0" }
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(width, height)
            return Bitmap(surface.makeImageSnapshot())
        }

        /** A Bitmap from encoded bytes (PNG, JPEG, WebP), or null when they are not a picture. */
        internal fun decode(bytes: ByteArray): Bitmap? = runCatching { Bitmap(org.jetbrains.skia.Image.makeFromEncoded(bytes)) }.getOrNull()
    }
}

/** Android's pixel format names, which a Drawable's getOpacity answers with. */
object PixelFormat {
    const val UNKNOWN = 0
    const val OPAQUE = -1
    const val TRANSPARENT = -2
    const val TRANSLUCENT = -3
}

/** A View's outline: a rectangle, rounded or not, Android's. */
class Outline {
    internal val rect = Rect()
    var radius: Float = 0f
        private set
    var alpha: Float = 1f
    internal var empty = true

    fun setRect(left: Int, top: Int, right: Int, bottom: Int) = setRoundRect(left, top, right, bottom, 0f)

    fun setRoundRect(left: Int, top: Int, right: Int, bottom: Int, radius: Float) {
        rect.set(left, top, right, bottom)
        this.radius = radius
        empty = false
    }

    fun setEmpty() {
        empty = true
    }

    fun isEmpty(): Boolean = empty
}

/**
 * Decodes a rectangle of a large picture, Android's BitmapRegionDecoder: the storyboard's tiles,
 * the scrub preview's frames. On Skia the picture is decoded once and each region drawn out of it.
 */
class BitmapRegionDecoder private constructor(private val image: org.jetbrains.skia.Image?) {
    val width: Int get() = image?.width ?: -1

    val height: Int get() = image?.height ?: -1

    var isRecycled: Boolean = false
        private set

    /** The pixels inside [rect], or an IllegalArgumentException outside the picture, as Android's. */
    fun decodeRegion(rect: Rect, options: BitmapFactory.Options?): Bitmap? {
        val source = image ?: return null
        check(!isRecycled) { "Region decoder is recycled" }
        require(rect.right > 0 && rect.bottom > 0 && rect.left < source.width && rect.top < source.height) { "rectangle is outside the image" }
        val w = rect.width()
        val h = rect.height()
        if (w <= 0 || h <= 0) return null
        val sample = (options?.inSampleSize ?: 1).coerceAtLeast(1)
        val outW = (w / sample).coerceAtLeast(1)
        val outH = (h / sample).coerceAtLeast(1)
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(outW, outH)
        surface.canvas.drawImageRect(
            source,
            org.jetbrains.skia.Rect.makeLTRB(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()),
            org.jetbrains.skia.Rect.makeWH(outW.toFloat(), outH.toFloat()),
            org.jetbrains.skia.SamplingMode.LINEAR,
            null,
            true,
        )
        return Bitmap(surface.makeImageSnapshot())
    }

    fun recycle() {
        isRecycled = true
    }

    companion object {
        /** A decoder for the picture in the file at [pathName]; an IOException when it is not one, as Android's. */
        fun newInstance(pathName: String): BitmapRegionDecoder {
            val bytes = runCatching { java.io.File(pathName).readBytes() }.getOrNull() ?: throw java.io.IOException("Cannot read $pathName")
            return newInstance(bytes)
        }

        fun newInstance(pathName: String, @Suppress("UNUSED_PARAMETER") isShareable: Boolean): BitmapRegionDecoder = newInstance(pathName)

        fun newInstance(data: ByteArray): BitmapRegionDecoder {
            val image = runCatching { org.jetbrains.skia.Image.makeFromEncoded(data) }.getOrNull()
                ?: throw java.io.IOException("Image format not supported")
            return BitmapRegionDecoder(image)
        }

        fun newInstance(stream: java.io.InputStream): BitmapRegionDecoder = newInstance(stream.readBytes())
    }
}

/** Android's picture decoding from bytes and files, on Skia. */
object BitmapFactory {
    /** How to decode, Android's; the sample size is the one honoured here. */
    class Options {
        var inSampleSize: Int = 1
        var inJustDecodeBounds: Boolean = false
        var inPreferredConfig: Bitmap.Config? = Bitmap.Config.ARGB_8888
        var inMutable: Boolean = false
        var outWidth: Int = -1
        var outHeight: Int = -1
        var outMimeType: String? = null
    }

    fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? = decodeByteArray(data, offset, length, null)

    fun decodeByteArray(data: ByteArray, offset: Int, length: Int, opts: Options?): Bitmap? {
        val bytes = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
        val bitmap = Bitmap.decode(bytes) ?: return null
        if (opts != null) {
            opts.outWidth = bitmap.width
            opts.outHeight = bitmap.height
            if (opts.inJustDecodeBounds) return null
            val sample = opts.inSampleSize.coerceAtLeast(1)
            if (sample > 1) return Bitmap.createScaledBitmap(bitmap, (bitmap.width / sample).coerceAtLeast(1), (bitmap.height / sample).coerceAtLeast(1), true)
        }
        return bitmap
    }

    fun decodeFile(pathName: String): Bitmap? = decodeFile(pathName, null)

    fun decodeFile(pathName: String, opts: Options?): Bitmap? {
        val bytes = runCatching { java.io.File(pathName).readBytes() }.getOrNull() ?: return null
        return decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun decodeStream(stream: java.io.InputStream?): Bitmap? {
        val bytes = runCatching { stream?.readBytes() }.getOrNull() ?: return null
        return decodeByteArray(bytes, 0, bytes.size)
    }
}

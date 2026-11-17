package android.widget

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.ResourceFiles
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.util.android
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

// Android's stock widgets on iOS, the ones :app's layouts use, laid out and drawn by Android's
// rules: FrameLayout, LinearLayout, TextView, Button, ImageView, ImageButton, ProgressBar, Space,
// HorizontalScrollView.

/** Children stacked over each other, each placed by its gravity, Android's. */
open class FrameLayout(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : ViewGroup(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    var foregroundGravity: Int = Gravity.FILL
    var measureAllChildren: Boolean = false

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams = LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = if (p == null) generateDefaultLayoutParams() else LayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var maxWidth = 0
        var maxHeight = 0
        val exactly = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        val matchLater = ArrayList<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            if (child.visibility == GONE && !measureAllChildren) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as LayoutParams
            maxWidth = maxOf(maxWidth, child.measuredWidth + lp.leftMargin + lp.rightMargin)
            maxHeight = maxOf(maxHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
            if (!exactly && (lp.width == ViewGroup.LayoutParams.MATCH_PARENT || lp.height == ViewGroup.LayoutParams.MATCH_PARENT)) matchLater += child
        }
        maxWidth = maxOf(maxWidth + paddingLeft + paddingRight, suggestedMinimumWidth)
        maxHeight = maxOf(maxHeight + paddingTop + paddingBottom, suggestedMinimumHeight)
        setMeasuredDimension(resolveSize(maxWidth, widthMeasureSpec), resolveSize(maxHeight, heightMeasureSpec))
        if (matchLater.size > 1) {
            for (child in matchLater) {
                val lp = child.layoutParams as LayoutParams
                val childWidth = if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                    MeasureSpec.makeMeasureSpec(maxOf(0, measuredWidth - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin), MeasureSpec.EXACTLY)
                } else {
                    getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin, lp.width)
                }
                val childHeight = if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                    MeasureSpec.makeMeasureSpec(maxOf(0, measuredHeight - paddingTop - paddingBottom - lp.topMargin - lp.bottomMargin), MeasureSpec.EXACTLY)
                } else {
                    getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height)
                }
                child.measure(childWidth, childHeight)
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val parentLeft = paddingLeft
        val parentRight = right - left - paddingRight
        val parentTop = paddingTop
        val parentBottom = bottom - top - paddingBottom
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            lp.resolveLayoutDirection(if (isLayoutRtl) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR)
            val w = child.measuredWidth
            val h = child.measuredHeight
            val gravity = Gravity.getAbsoluteGravity(
                if (lp.gravity == LayoutParams.UNSPECIFIED_GRAVITY) Gravity.TOP or Gravity.START else lp.gravity,
                if (isLayoutRtl) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR,
            )
            val childLeft = when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.CENTER_HORIZONTAL -> parentLeft + (parentRight - parentLeft - w) / 2 + lp.leftMargin - lp.rightMargin
                Gravity.RIGHT -> parentRight - w - lp.rightMargin
                else -> parentLeft + lp.leftMargin
            }
            val childTop = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.CENTER_VERTICAL -> parentTop + (parentBottom - parentTop - h) / 2 + lp.topMargin - lp.bottomMargin
                Gravity.BOTTOM -> parentBottom - h - lp.bottomMargin
                else -> parentTop + lp.topMargin
            }
            child.layout(childLeft, childTop, childLeft + w, childTop + h)
        }
    }

    /** FrameLayout's LayoutParams: a gravity in the frame. */
    open class LayoutParams : ViewGroup.MarginLayoutParams {
        var gravity: Int = UNSPECIFIED_GRAVITY

        constructor(width: Int, height: Int) : super(width, height)

        constructor(width: Int, height: Int, gravity: Int) : super(width, height) {
            this.gravity = gravity
        }

        constructor(source: ViewGroup.LayoutParams) : super(source) {
            if (source is LayoutParams) gravity = source.gravity
        }

        constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
            attrs?.android("layout_gravity")?.let { gravity = Gravity.parse(it) }
        }

        companion object {
            const val UNSPECIFIED_GRAVITY = -1

            // Java's statics reach a subclass's name; Kotlin's companions do not, so each says them.
            const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
            const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }
}

/** Children in a row or a column, sharing what is left by weight, Android's. */
open class LinearLayout(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : ViewGroup(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    var orientation: Int = if (attrs?.android("orientation") == "vertical") VERTICAL else HORIZONTAL
        set(value) {
            field = value
            requestLayout()
        }

    var gravity: Int = attrs?.android("gravity")?.let(Gravity::parse) ?: (Gravity.START or Gravity.TOP)
        set(value) {
            field = value
            requestLayout()
        }

    var weightSum: Float = attrs?.android("weightSum")?.toFloatOrNull() ?: -1f
    var isBaselineAligned: Boolean = true
    private var totalLength = 0

    fun setHorizontalGravity(horizontalGravity: Int) {
        gravity = (gravity and Gravity.HORIZONTAL_GRAVITY_MASK.inv() and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK.inv()) or horizontalGravity
    }

    fun setVerticalGravity(verticalGravity: Int) {
        gravity = (gravity and Gravity.VERTICAL_GRAVITY_MASK.inv()) or verticalGravity
    }

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = if (orientation == HORIZONTAL) {
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    } else {
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams = LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = if (p == null) generateDefaultLayoutParams() else LayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (orientation == VERTICAL) measureVertical(widthMeasureSpec, heightMeasureSpec) else measureHorizontal(widthMeasureSpec, heightMeasureSpec)
    }

    private fun visibleChildren(): List<View> = (0 until childCount).mapNotNull { getChildAt(it) }.filter { it.visibility != GONE }

    /** Android's measureVertical: the fixed and wrapped first, then the weighted share what is left. */
    private fun measureVertical(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        totalLength = 0
        var maxWidth = 0
        var totalWeight = 0f
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val children = visibleChildren()
        for (child in children) {
            val lp = child.layoutParams as LayoutParams
            totalWeight += lp.weight
            if (heightMode == MeasureSpec.EXACTLY && lp.height == 0 && lp.weight > 0) {
                totalLength += lp.topMargin + lp.bottomMargin
            } else {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, if (totalWeight == 0f) totalLength else 0)
                totalLength += child.measuredHeight + lp.topMargin + lp.bottomMargin
            }
            maxWidth = maxOf(maxWidth, child.measuredWidth + lp.leftMargin + lp.rightMargin)
        }
        totalLength += paddingTop + paddingBottom
        val heightSize = resolveSize(maxOf(totalLength, suggestedMinimumHeight), heightMeasureSpec)
        var remaining = heightSize - totalLength
        if (totalWeight > 0 && remaining != 0) {
            val sum = if (weightSum > 0f) weightSum else totalWeight
            var weightLeft = sum
            totalLength = 0
            for (child in children) {
                val lp = child.layoutParams as LayoutParams
                if (lp.weight > 0) {
                    val share = (lp.weight * remaining / weightLeft).toInt()
                    weightLeft -= lp.weight
                    remaining -= share
                    val childHeight = if (lp.height == 0 && heightMode == MeasureSpec.EXACTLY) share else child.measuredHeight + share
                    child.measure(
                        getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin, lp.width),
                        MeasureSpec.makeMeasureSpec(maxOf(0, childHeight), MeasureSpec.EXACTLY),
                    )
                    maxWidth = maxOf(maxWidth, child.measuredWidth + lp.leftMargin + lp.rightMargin)
                }
                totalLength += child.measuredHeight + lp.topMargin + lp.bottomMargin
            }
            totalLength += paddingTop + paddingBottom
        }
        maxWidth = maxOf(maxWidth + paddingLeft + paddingRight, suggestedMinimumWidth)
        setMeasuredDimension(resolveSize(maxWidth, widthMeasureSpec), heightSize)
        // MATCH_PARENT children of a wrapped column take the column's final width.
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            for (child in children) {
                val lp = child.layoutParams as LayoutParams
                if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY),
                    )
                }
            }
        }
    }

    /** Android's measureHorizontal, the same along the row. */
    private fun measureHorizontal(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        totalLength = 0
        var maxHeight = 0
        var totalWeight = 0f
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val children = visibleChildren()
        for (child in children) {
            val lp = child.layoutParams as LayoutParams
            totalWeight += lp.weight
            if (widthMode == MeasureSpec.EXACTLY && lp.width == 0 && lp.weight > 0) {
                totalLength += lp.leftMargin + lp.rightMargin
            } else {
                measureChildWithMargins(child, widthMeasureSpec, if (totalWeight == 0f) totalLength else 0, heightMeasureSpec, 0)
                totalLength += child.measuredWidth + lp.leftMargin + lp.rightMargin
            }
            maxHeight = maxOf(maxHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
        }
        totalLength += paddingLeft + paddingRight
        val widthSize = resolveSize(maxOf(totalLength, suggestedMinimumWidth), widthMeasureSpec)
        var remaining = widthSize - totalLength
        if (totalWeight > 0 && remaining != 0) {
            val sum = if (weightSum > 0f) weightSum else totalWeight
            var weightLeft = sum
            totalLength = 0
            for (child in children) {
                val lp = child.layoutParams as LayoutParams
                if (lp.weight > 0) {
                    val share = (lp.weight * remaining / weightLeft).toInt()
                    weightLeft -= lp.weight
                    remaining -= share
                    val childWidth = if (lp.width == 0 && widthMode == MeasureSpec.EXACTLY) share else child.measuredWidth + share
                    child.measure(
                        MeasureSpec.makeMeasureSpec(maxOf(0, childWidth), MeasureSpec.EXACTLY),
                        getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height),
                    )
                    maxHeight = maxOf(maxHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
                }
                totalLength += child.measuredWidth + lp.leftMargin + lp.rightMargin
            }
            totalLength += paddingLeft + paddingRight
        }
        maxHeight = maxOf(maxHeight + paddingTop + paddingBottom, suggestedMinimumHeight)
        setMeasuredDimension(widthSize, resolveSize(maxHeight, heightMeasureSpec))
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            for (child in children) {
                val lp = child.layoutParams as LayoutParams
                if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                    child.measure(
                        MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(measuredHeight - paddingTop - paddingBottom - lp.topMargin - lp.bottomMargin, MeasureSpec.EXACTLY),
                    )
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val direction = if (isLayoutRtl) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
        val absolute = Gravity.getAbsoluteGravity(gravity, direction)
        val children = visibleChildren()
        if (orientation == VERTICAL) {
            val width = right - left
            var childTop = when (absolute and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.BOTTOM -> paddingTop + bottom - top - totalLength
                Gravity.CENTER_VERTICAL -> paddingTop + (bottom - top - totalLength) / 2
                else -> paddingTop
            }
            for (child in children) {
                val lp = child.layoutParams as LayoutParams
                lp.resolveLayoutDirection(direction)
                val childGravity = Gravity.getAbsoluteGravity(if (lp.gravity < 0) absolute else lp.gravity, direction)
                val w = child.measuredWidth
                val childLeft = when (childGravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
                    Gravity.CENTER_HORIZONTAL -> paddingLeft + (width - paddingLeft - paddingRight - w) / 2 + lp.leftMargin - lp.rightMargin
                    Gravity.RIGHT -> width - paddingRight - w - lp.rightMargin
                    else -> paddingLeft + lp.leftMargin
                }
                childTop += lp.topMargin
                child.layout(childLeft, childTop, childLeft + w, childTop + child.measuredHeight)
                childTop += child.measuredHeight + lp.bottomMargin
            }
        } else {
            val height = bottom - top
            val rtl = isLayoutRtl
            var childLeft = when (absolute and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.RIGHT -> paddingLeft + right - left - totalLength
                Gravity.CENTER_HORIZONTAL -> paddingLeft + (right - left - totalLength) / 2
                else -> paddingLeft
            }
            for (child in if (rtl) children.reversed() else children) {
                val lp = child.layoutParams as LayoutParams
                lp.resolveLayoutDirection(direction)
                val childGravity = if (lp.gravity < 0) absolute else lp.gravity
                val h = child.measuredHeight
                val childTop = when (childGravity and Gravity.VERTICAL_GRAVITY_MASK) {
                    Gravity.CENTER_VERTICAL -> paddingTop + (height - paddingTop - paddingBottom - h) / 2 + lp.topMargin - lp.bottomMargin
                    Gravity.BOTTOM -> height - paddingBottom - h - lp.bottomMargin
                    else -> paddingTop + lp.topMargin
                }
                childLeft += lp.leftMargin
                child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + h)
                childLeft += child.measuredWidth + lp.rightMargin
            }
        }
    }

    /** LinearLayout's LayoutParams: a weight and a gravity across the line. */
    open class LayoutParams : ViewGroup.MarginLayoutParams {
        var weight: Float = 0f
        var gravity: Int = -1

        constructor(width: Int, height: Int) : super(width, height)

        constructor(width: Int, height: Int, weight: Float) : super(width, height) {
            this.weight = weight
        }

        constructor(source: ViewGroup.LayoutParams) : super(source) {
            if (source is LayoutParams) {
                weight = source.weight
                gravity = source.gravity
            }
        }

        constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
            weight = attrs?.android("layout_weight")?.toFloatOrNull() ?: 0f
            attrs?.android("layout_gravity")?.let { gravity = Gravity.parse(it) }
        }

        companion object {
            const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
            const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}

/** Text, Android's: one line or a few, ellipsized at the end, in its gravity, with an icon beside it. */
open class TextView(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var colors: ColorStateList = ColorStateList.valueOf(DEFAULT_TEXT_COLOR)
    private var lines: List<String> = emptyList()
    private var drawableStart: Drawable? = null
    private var drawableEnd: Drawable? = null
    private var drawableTint: ColorStateList? = null

    var text: CharSequence = ""
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /** Shown in capitals, the text itself kept as it is, Android's setAllCaps. */
    var isAllCaps: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    fun setAllCaps(allCaps: Boolean) {
        isAllCaps = allCaps
    }

    /** What is drawn: the text, in capitals when [isAllCaps]. */
    private val shownText: String get() = if (isAllCaps) text.toString().uppercase() else text.toString()

    /** TextView's own minimum height, in pixels, Android's getMinHeight/setMinHeight. */
    var minHeight: Int
        get() = minimumHeight
        set(value) {
            minimumHeight = value
            requestLayout()
        }

    /** TextView's own minimum width, in pixels, Android's getMinWidth/setMinWidth. */
    var minWidth: Int
        get() = minimumWidth
        set(value) {
            minimumWidth = value
            requestLayout()
        }

    var gravity: Int = Gravity.TOP or Gravity.START
        set(value) {
            field = value
            invalidate()
        }

    var maxLines: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    var maxWidth: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    var ellipsize: TextUtils.TruncateAt? = null
    var compoundDrawablePadding: Int = 0
    var includeFontPadding: Boolean = true
    var letterSpacing: Float = 0f
    var hint: CharSequence? = null

    var typeface: Typeface?
        get() = paint.typeface
        set(value) {
            paint.typeface = value
            requestLayout()
            invalidate()
        }

    /** The text's size in pixels. */
    /** Java's pair, as Kotlin sees it: reads in pixels, writes in sp (setTextSize(float)). */
    var textSize: Float
        get() = paint.textSize
        set(value) = setTextSize(TypedValue.COMPLEX_UNIT_SP, value)

    fun getPaint(): Paint = paint

    val currentTextColor: Int get() = colors.getColorForState(drawableState, colors.defaultColor)

    val textColors: ColorStateList get() = colors

    val lineCount: Int get() = lines.size.coerceAtLeast(1)

    fun setText(resid: Int) {
        text = context?.getString(resid) ?: ""
    }

    /** A size in sp, Android's default unit for text. */
    fun setTextSize(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, size)

    fun setTextSize(unit: Int, size: Float) {
        paint.textSize = TypedValue.applyDimension(unit, size, resources.displayMetrics)
        requestLayout()
        invalidate()
    }

    fun setTextColor(color: Int) = setTextColor(ColorStateList.valueOf(color))

    fun setTextColor(colors: ColorStateList?) {
        this.colors = colors ?: ColorStateList.valueOf(DEFAULT_TEXT_COLOR)
        invalidate()
    }

    fun setTypeface(tf: Typeface?, style: Int) {
        typeface = Typeface.create(tf, style)
    }

    fun setSingleLine(singleLine: Boolean = true) {
        maxLines = if (singleLine) 1 else Int.MAX_VALUE
    }

    fun setLines(lines: Int) {
        maxLines = lines
    }

    fun setShadowLayer(radius: Float, dx: Float, dy: Float, color: Int) = paint.setShadowLayer(radius, dx, dy, color)

    fun setCompoundDrawablesRelativeWithIntrinsicBounds(start: Drawable?, top: Drawable?, end: Drawable?, bottom: Drawable?) {
        drawableStart = start
        drawableEnd = end
        requestLayout()
        invalidate()
    }

    /** The same by :app's drawable ids, 0 for none, Android's. */
    fun setCompoundDrawablesRelativeWithIntrinsicBounds(start: Int, top: Int, end: Int, bottom: Int) {
        fun of(id: Int): Drawable? = if (id == 0) null else ImageView.drawableForImage(id)
        setCompoundDrawablesRelativeWithIntrinsicBounds(of(start), of(top), of(end), of(bottom))
    }

    fun setCompoundDrawablesWithIntrinsicBounds(left: Drawable?, top: Drawable?, right: Drawable?, bottom: Drawable?) =
        setCompoundDrawablesRelativeWithIntrinsicBounds(left, top, right, bottom)

    fun setCompoundDrawableTintList(tint: ColorStateList?) {
        drawableTint = tint
        drawableStart?.setTintList(tint)
        drawableEnd?.setTintList(tint)
        invalidate()
    }

    val compoundDrawablesRelative: Array<Drawable?> get() = arrayOf(drawableStart, null, drawableEnd, null)

    init {
        paint.textSize = 14f * ResourceFiles.scaledDensity
        if (attrs != null) {
            attrs?.android("textSize")?.let { ResourceFiles.dimension(it) }?.let { paint.textSize = it }
            attrs?.android("textColor")?.let { ResourceFiles.colorStateList(it) }?.let { colors = it }
            val family = attrs?.android("fontFamily")
            val bold = attrs?.android("textStyle")?.contains("bold") == true
            val italic = attrs?.android("textStyle")?.contains("italic") == true
            paint.typeface = Typeface.of(family ?: "sans-serif", if (bold) 700 else if (family?.endsWith("-medium") == true) 500 else 400, italic)
            attrs?.android("text")?.let { text = ResourceFiles.string(it) ?: "" }
            attrs?.android("hint")?.let { hint = ResourceFiles.string(it) }
            attrs?.android("gravity")?.let { gravity = Gravity.parse(it) }
            attrs?.android("maxLines")?.toIntOrNull()?.let { maxLines = it }
            attrs?.android("lines")?.toIntOrNull()?.let { maxLines = it }
            if (attrs?.android("singleLine") == "true") maxLines = 1
            attrs?.android("maxWidth")?.let { ResourceFiles.dimension(it) }?.let { maxWidth = it.toInt() }
            attrs?.android("ellipsize")?.let {
                ellipsize = when (it) {
                    "start" -> TextUtils.TruncateAt.START
                    "middle" -> TextUtils.TruncateAt.MIDDLE
                    "marquee" -> TextUtils.TruncateAt.MARQUEE
                    "end" -> TextUtils.TruncateAt.END
                    else -> null
                }
            }
            attrs?.android("textAllCaps")?.let { isAllCaps = it == "true" }
            attrs?.android("includeFontPadding")?.let { includeFontPadding = it != "false" }
            attrs?.android("letterSpacing")?.toFloatOrNull()?.let { letterSpacing = it }
            attrs?.android("drawablePadding")?.let { ResourceFiles.dimension(it) }?.let { compoundDrawablePadding = it.toInt() }
            drawableStart = (attrs?.android("drawableStart") ?: attrs?.android("drawableLeft"))?.let(Drawable::fromReference)
            drawableEnd = (attrs?.android("drawableEnd") ?: attrs?.android("drawableRight"))?.let(Drawable::fromReference)
            attrs?.android("drawableTint")?.let(ResourceFiles::colorStateList)?.let { setCompoundDrawableTintList(it) }
        } else {
            paint.typeface = Typeface.DEFAULT
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        drawableStart?.setState(drawableState)
        drawableEnd?.setState(drawableState)
    }

    /** One line's height as Android's StaticLayout gives it, with the font's padding by default. */
    private fun lineHeight(): Float {
        val m = paint.getFontMetrics()
        return if (includeFontPadding) m.bottom - m.top else m.descent - m.ascent
    }

    private fun iconWidth(): Int {
        var w = 0
        drawableStart?.let { w += it.getIntrinsicWidth().coerceAtLeast(0) + compoundDrawablePadding }
        drawableEnd?.let { w += it.getIntrinsicWidth().coerceAtLeast(0) + compoundDrawablePadding }
        return w
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val extra = paddingLeft + paddingRight + iconWidth()
        val content = shownText
        val desired = content.split('\n').maxOfOrNull { paint.measureText(it) }?.let { kotlin.math.ceil(it).toInt() } ?: 0
        var width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(desired + extra, widthSize)
            else -> desired + extra
        }
        if (widthMode != MeasureSpec.EXACTLY) width = minOf(width, maxWidth).coerceAtLeast(minimumWidth)
        lines = layoutLines(content, (width - extra).coerceAtLeast(0))
        val iconHeight = maxOf(drawableStart?.getIntrinsicHeight() ?: 0, drawableEnd?.getIntrinsicHeight() ?: 0)
        val textHeight = kotlin.math.ceil(lineHeight() * lines.size.coerceAtLeast(1)).toInt()
        val desiredHeight = maxOf(textHeight, iconHeight) + paddingTop + paddingBottom
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight
        }
        setMeasuredDimension(width, maxOf(height, minimumHeight))
    }

    /** The text broken into lines that fit [width], at most maxLines, the last ellipsized. */
    private fun layoutLines(content: String, width: Int): List<String> {
        if (content.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (paragraph in content.split('\n')) {
            if (out.size >= maxLines) break
            var rest = paragraph
            while (rest.isNotEmpty() && out.size < maxLines) {
                if (paint.measureText(rest) <= width || out.size == maxLines - 1) {
                    out.add(rest)
                    rest = ""
                    break
                }
                var cut = rest.length
                while (cut > 1 && paint.measureText(rest.substring(0, cut)) > width) cut--
                val space = rest.lastIndexOf(' ', cut).takeIf { it > 0 } ?: cut
                out.add(rest.substring(0, space).trimEnd())
                rest = rest.substring(space).trimStart()
            }
            if (paragraph.isEmpty()) out.add("")
        }
        val last = out.lastIndex
        if (last >= 0 && paint.measureText(out[last]) > width && ellipsize != null) out[last] = ellipsized(out[last], width)
        return out
    }

    private fun ellipsized(line: String, width: Int): String {
        val dots = "…"
        if (paint.measureText(dots) > width) return ""
        var end = line.length
        while (end > 0 && paint.measureText(line.substring(0, end) + dots) > width) end--
        return line.substring(0, end).trimEnd() + dots
    }

    override fun onDraw(canvas: Canvas) {
        val m = paint.getFontMetrics()
        val lineHeight = lineHeight()
        val textBlock = lineHeight * lines.size.coerceAtLeast(1)
        val innerLeft = paddingLeft + (drawableStart?.let { it.getIntrinsicWidth().coerceAtLeast(0) + compoundDrawablePadding } ?: 0)
        val innerRight = width - paddingRight - (drawableEnd?.let { it.getIntrinsicWidth().coerceAtLeast(0) + compoundDrawablePadding } ?: 0)
        val innerTop = paddingTop
        val innerBottom = height - paddingBottom
        val vertical = gravity and Gravity.VERTICAL_GRAVITY_MASK
        val blockTop = when (vertical) {
            Gravity.CENTER_VERTICAL -> innerTop + (innerBottom - innerTop - textBlock) / 2f
            Gravity.BOTTOM -> innerBottom - textBlock
            else -> innerTop.toFloat()
        }
        drawIcons(canvas, innerTop, innerBottom, vertical)
        paint.color = currentTextColor
        val shown = if (lines.isEmpty() && text.isNotEmpty()) listOf(shownText) else lines
        val horizontal = Gravity.getAbsoluteGravity(gravity, if (isLayoutRtl) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR) and Gravity.HORIZONTAL_GRAVITY_MASK
        val save = canvas.save()
        canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        shown.forEachIndexed { index, line ->
            val lineWidth = paint.measureText(line)
            val x = when (horizontal) {
                Gravity.CENTER_HORIZONTAL -> innerLeft + (innerRight - innerLeft - lineWidth) / 2f
                Gravity.RIGHT -> innerRight - lineWidth
                else -> innerLeft.toFloat()
            }
            val top = blockTop + lineHeight * index
            val baseline = top - (if (includeFontPadding) m.top else m.ascent)
            canvas.drawText(line, x, baseline, paint)
        }
        canvas.restoreToCount(save)
    }

    private fun drawIcons(canvas: Canvas, innerTop: Int, innerBottom: Int, vertical: Int) {
        drawableStart?.let { icon ->
            val w = icon.getIntrinsicWidth().coerceAtLeast(0)
            val h = icon.getIntrinsicHeight().coerceAtLeast(0)
            val top = innerTop + (innerBottom - innerTop - h) / 2
            icon.setBounds(paddingLeft, top, paddingLeft + w, top + h)
            icon.draw(canvas)
        }
        drawableEnd?.let { icon ->
            val w = icon.getIntrinsicWidth().coerceAtLeast(0)
            val h = icon.getIntrinsicHeight().coerceAtLeast(0)
            val top = innerTop + (innerBottom - innerTop - h) / 2
            icon.setBounds(width - paddingRight - w, top, width - paddingRight, top + h)
            icon.draw(canvas)
        }
    }

    companion object {
        /** Android's default text colour on a dark theme's player, where every TextView sets its own. */
        private const val DEFAULT_TEXT_COLOR = 0xDE000000.toInt()
    }
}

/**
 * A TextView that is a button, Android's. Made without a style attribute, or from a layout, it
 * takes the theme's `buttonStyle`, as Android's Button does; made with `borderlessButtonStyle` (the
 * next-up card's Hide and Play now), the theme's borderless one.
 */
open class Button(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : TextView(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, android.R.attr.buttonStyle)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, android.R.attr.buttonStyle)

    init {
        isClickable = true
        isFocusable = true
        if (attrs?.android("gravity") == null) gravity = Gravity.CENTER
        when (defStyleAttr) {
            android.R.attr.buttonStyle -> themeStyle(attrs, borderless = false)
            android.R.attr.borderlessButtonStyle -> themeStyle(attrs, borderless = true)
        }
    }

    /**
     * Theme.Material's Widget.Material.Button and its Borderless child (both of TMPlayer's themes
     * are Theme.Material's, dark): what the element and its style leave unset comes from there, as
     * Android resolves a style attribute under the element's own. The values are the platform's:
     * 88 by 48 dp at least, 14 sp medium capitals in the dark theme's primary text colour, on a
     * background inset 4 dp across and 6 dp down whose 8 and 4 dp of padding make the text's 12
     * and 10 dp; the borderless one draws only its ripple, the other a 2 dp rounded grey seat.
     * Android raises that seat 2 dp with a shadow, which is not drawn here.
     */
    private fun themeStyle(attrs: AttributeSet?, borderless: Boolean) {
        val dp = ResourceFiles.density
        if (attrs?.android("minWidth") == null) minWidth = (88 * dp).toInt()
        if (attrs?.android("minHeight") == null) minHeight = (48 * dp).toInt()
        if (attrs?.android("textAllCaps") == null) isAllCaps = true
        if (attrs?.android("fontFamily") == null && attrs?.android("textStyle") == null) {
            typeface = Typeface.of("sans-serif-medium", 500, false)
        }
        if (attrs?.android("textColor") == null) {
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), IntArray(0)), intArrayOf(DISABLED_TEXT, PRIMARY_TEXT)))
        }
        if (attrs?.android("background") != null) return
        val seat = InsetSeat((4 * dp).toInt(), (6 * dp).toInt(), 2 * dp, if (borderless) null else SEAT_COLOR)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(HIGHLIGHT), if (borderless) null else seat, seat.mask())
        val padded = listOf("padding", "paddingHorizontal", "paddingVertical", "paddingLeft", "paddingRight", "paddingTop", "paddingBottom", "paddingStart", "paddingEnd")
        if (padded.none { attrs?.android(it) != null }) setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
    }

    /** A rounded rectangle inset from its bounds, Material's btn_default_mtrl_shape; its mask fills the same shape in the ripple's colour. */
    private class InsetSeat(private val across: Int, private val down: Int, private val radius: Float, color: Int?) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color ?: 0 }
        private val filled = color != null

        fun mask(): Drawable = InsetSeat(across, down, radius, null)

        override fun setTintList(tint: ColorStateList?) {
            paint.color = tint?.defaultColor ?: paint.color
        }

        override fun draw(canvas: Canvas) {
            if (!filled && paint.color == 0) return
            val b = getBounds()
            canvas.drawRoundRect(RectF((b.left + across).toFloat(), (b.top + down).toFloat(), (b.right - across).toFloat(), (b.bottom - down).toFloat()), radius, radius, paint)
        }
    }

    companion object {
        // Theme.Material's dark values: primary_text_default_material_dark, at disabled_alpha_material_dark
        // (0.3) when disabled; button_material_dark; ripple_material_dark.
        private const val PRIMARY_TEXT = 0xFFFFFFFF.toInt()
        private const val DISABLED_TEXT = 0x4DFFFFFF
        private const val SEAT_COLOR = 0xFF5A595B.toInt()
        private const val HIGHLIGHT = 0x33FFFFFF
    }
}

/** A picture, Android's: a drawable scaled into the View by its scale type, tinted. */
open class ImageView(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    enum class ScaleType { MATRIX, FIT_XY, FIT_START, FIT_CENTER, FIT_END, CENTER, CENTER_CROP, CENTER_INSIDE }

    private var image: Drawable? = null
    private var tint: ColorStateList? = null
    private var imageAlphaValue = 255
    var adjustViewBounds: Boolean = false

    var scaleType: ScaleType = ScaleType.FIT_CENTER
        set(value) {
            field = value
            invalidate()
        }

    var imageTintList: ColorStateList?
        get() = tint
        set(value) {
            tint = value
            image?.setTintList(value)
            invalidate()
        }

    val drawable: Drawable? get() = image

    fun setImageDrawable(drawable: Drawable?) {
        if (image === drawable) return
        image?.callback = null
        image = drawable
        drawable?.callback = this
        tint?.let { drawable?.setTintList(it) }
        drawable?.setState(drawableState)
        requestLayout()
        invalidate()
    }

    fun setImageResource(resId: Int) = setImageDrawable(if (resId == 0) null else drawableForImage(resId))

    fun setImageBitmap(bitmap: android.graphics.Bitmap?) = setImageDrawable(bitmap?.let { android.graphics.drawable.BitmapDrawable(it) })

    fun setColorFilter(color: Int) {
        imageTintList = ColorStateList.valueOf(color)
    }

    fun setColorFilter(color: Int, mode: PorterDuff.Mode) = setColorFilter(color)

    fun clearColorFilter() {
        imageTintList = null
    }

    var imageAlpha: Int
        get() = imageAlphaValue
        set(value) {
            imageAlphaValue = value
            image?.setAlpha(value)
            invalidate()
        }

    init {
        if (attrs != null) {
            attrs?.android("src")?.let(Drawable::fromReference)?.let { setImageDrawable(it) }
            attrs?.android("tint")?.let(ResourceFiles::colorStateList)?.let { imageTintList = it }
            attrs?.android("scaleType")?.let {
                scaleType = when (it) {
                    "fitXY" -> ScaleType.FIT_XY
                    "fitStart" -> ScaleType.FIT_START
                    "fitEnd" -> ScaleType.FIT_END
                    "center" -> ScaleType.CENTER
                    "centerCrop" -> ScaleType.CENTER_CROP
                    "centerInside" -> ScaleType.CENTER_INSIDE
                    "matrix" -> ScaleType.MATRIX
                    else -> ScaleType.FIT_CENTER
                }
            }
            attrs?.android("adjustViewBounds")?.let { adjustViewBounds = it == "true" }
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        image?.setState(drawableState)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (image?.getIntrinsicWidth() ?: 0).coerceAtLeast(0) + paddingLeft + paddingRight
        val h = (image?.getIntrinsicHeight() ?: 0).coerceAtLeast(0) + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(maxOf(w, suggestedMinimumWidth), widthMeasureSpec),
            resolveSize(maxOf(h, suggestedMinimumHeight), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val drawable = image ?: return
        val boxW = (width - paddingLeft - paddingRight).toFloat()
        val boxH = (height - paddingTop - paddingBottom).toFloat()
        if (boxW <= 0f || boxH <= 0f) return
        val iw = drawable.getIntrinsicWidth().takeIf { it > 0 }?.toFloat() ?: boxW
        val ih = drawable.getIntrinsicHeight().takeIf { it > 0 }?.toFloat() ?: boxH
        val rect = when (scaleType) {
            ScaleType.FIT_XY, ScaleType.MATRIX -> RectF(0f, 0f, boxW, boxH)
            ScaleType.CENTER -> RectF((boxW - iw) / 2f, (boxH - ih) / 2f, (boxW + iw) / 2f, (boxH + ih) / 2f)
            ScaleType.CENTER_CROP -> {
                val s = maxOf(boxW / iw, boxH / ih)
                RectF((boxW - iw * s) / 2f, (boxH - ih * s) / 2f, (boxW + iw * s) / 2f, (boxH + ih * s) / 2f)
            }
            ScaleType.CENTER_INSIDE -> {
                val s = minOf(1f, minOf(boxW / iw, boxH / ih))
                RectF((boxW - iw * s) / 2f, (boxH - ih * s) / 2f, (boxW + iw * s) / 2f, (boxH + ih * s) / 2f)
            }
            else -> {
                val s = minOf(boxW / iw, boxH / ih)
                val w = iw * s
                val h = ih * s
                when (scaleType) {
                    ScaleType.FIT_START -> RectF(0f, 0f, w, h)
                    ScaleType.FIT_END -> RectF(boxW - w, boxH - h, boxW, boxH)
                    else -> RectF((boxW - w) / 2f, (boxH - h) / 2f, (boxW + w) / 2f, (boxH + h) / 2f)
                }
            }
        }
        val save = canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.clipRect(0f, 0f, boxW, boxH)
        drawable.setBounds(rect.left.toInt(), rect.top.toInt(), kotlin.math.round(rect.right).toInt(), kotlin.math.round(rect.bottom).toInt())
        drawable.draw(canvas)
        canvas.restoreToCount(save)
    }

    companion object {
        /** A drawable by a resource id of :app's R, through the table the shell installs. */
        var drawableForImage: (Int) -> Drawable? = { id ->
            ResourceFiles.reference(id)?.takeIf { it.startsWith("drawable/") }?.let { Drawable.fromReference("@$it") }
        }
    }
}

/** An ImageView that is a button, Android's. */
open class ImageButton(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : ImageView(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    init {
        isClickable = true
        if (attrs?.android("focusable") == null) isFocusable = true
    }
}

/** Nothing, taking room, Android's. */
class Space(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    override fun draw(canvas: Canvas) {}
}

/** Android's indeterminate circular spinner: an arc growing and turning, in its tint. */
open class ProgressBar(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    /** Android's horizontal style, a bar filled to [progress]; otherwise the round spinner. */
    private val horizontal = defStyleAttr == android.R.attr.progressBarStyleHorizontal ||
        attrs?.getAttributeValue(null, "style")?.contains("Horizontal") == true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var spinner: android.animation.ValueAnimator? = null
    private var turn = 0f

    var isIndeterminate: Boolean = !horizontal
        set(value) {
            field = value
            invalidate()
        }
    var min: Int = 0
    var max: Int = 100
        set(value) {
            field = value
            invalidate()
        }
    var progress: Int = 0
        set(value) {
            field = value.coerceIn(min, max)
            invalidate()
        }
    var secondaryProgress: Int = 0
        set(value) {
            field = value.coerceIn(min, max)
            invalidate()
        }

    var indeterminateTintList: ColorStateList? = attrs?.android("indeterminateTint")?.let(ResourceFiles::colorStateList)
    var progressTintList: ColorStateList? = attrs?.android("progressTint")?.let(ResourceFiles::colorStateList)
        set(value) {
            field = value
            invalidate()
        }
    var progressBackgroundTintList: ColorStateList? = attrs?.android("progressBackgroundTint")?.let(ResourceFiles::colorStateList)
        set(value) {
            field = value
            invalidate()
        }
    var secondaryProgressTintList: ColorStateList? = null

    /** Java's getMinHeight/setMinHeight and the width's, Android 10's. */
    var minHeight: Int = 0
    var maxHeight: Int = Int.MAX_VALUE
    var minWidth: Int = 0
    var maxWidth: Int = Int.MAX_VALUE

    fun setProgress(progress: Int, animate: Boolean) {
        this.progress = progress
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = ResourceFiles.density
        if (horizontal) {
            // Material's horizontal bar: 16dp tall, the 4dp track centred in it.
            val height = (16 * density).toInt().coerceIn(minHeight, maxHeight)
            val width = (48 * density).toInt().coerceIn(minWidth, maxWidth)
            setMeasuredDimension(resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec), resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec))
            return
        }
        val size = (48 * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        spinner = null
        super.onDetachedFromWindow()
    }

    /**
     * Turns only while it is drawn: started from onDraw, stopped by itself once the View is hidden,
     * faded out with its parent (the player's controls fade rather than hide), detached or no
     * longer indeterminate, so nothing runs for a spinner nobody sees. Drawn again, it turns again.
     */
    private fun spin() {
        if (spinner != null) return
        spinner = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.animation.TimeInterpolator { it }
            addUpdateListener {
                if (!isAttachedToWindow || !isDrawn || !isIndeterminate) {
                    spinner?.cancel()
                    spinner = null
                    return@addUpdateListener
                }
                turn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (horizontal && !isIndeterminate) {
            drawBar(canvas)
            return
        }
        val size = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return
        spin()
        // Material's spinner: a 4dp stroke on a 48dp box, an arc growing to 270 and back as it turns.
        val stroke = size * 4f / 48f
        val inset = stroke / 2f + size * 4f / 48f
        val left = paddingLeft + (width - paddingLeft - paddingRight - size) / 2f + inset
        val top = paddingTop + (height - paddingTop - paddingBottom - size) / 2f + inset
        paint.strokeWidth = stroke
        paint.color = indeterminateTintList?.defaultColor ?: android.graphics.Color.WHITE
        val sweep = 20f + 250f * (0.5f - 0.5f * kotlin.math.cos(turn * 2 * kotlin.math.PI.toFloat()))
        val start = turn * 1080f
        canvas.drawArc(RectF(left, top, left + size - 2 * inset, top + size - 2 * inset), start, sweep, false, paint)
    }

    /** The track, the secondary progress and the progress, Material's, filled from the start side. */
    private fun drawBar(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right <= left) return
        val track = 4f * ResourceFiles.density
        val centre = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val top = centre - track / 2f
        val bottom = centre + track / 2f
        val tint = progressTintList?.defaultColor ?: android.graphics.Color.WHITE
        fill.color = progressBackgroundTintList?.defaultColor ?: ((tint and 0x00FFFFFF) or (0x4D shl 24))
        canvas.drawRect(left, top, right, bottom, fill)
        val span = (max - min).coerceAtLeast(1)
        fun end(value: Int): Float = left + (right - left) * ((value - min).toFloat() / span).coerceIn(0f, 1f)
        val rtl = isLayoutRtl
        fun draw(value: Int, color: Int) {
            fill.color = color
            val to = end(value)
            if (rtl) canvas.drawRect(right - (to - left), top, right, bottom, fill) else canvas.drawRect(left, top, to, bottom, fill)
        }
        if (secondaryProgress > min) draw(secondaryProgress, secondaryProgressTintList?.defaultColor ?: ((tint and 0x00FFFFFF) or (0x80 shl 24)))
        draw(progress, tint)
    }

    private companion object {
        const val CYCLE_MS = 1333L
    }
}

/**
 * A column that scrolls up and down when its one child is taller than it, Android's ScrollView:
 * the child measured with no limit on its height (or stretched to the view's with fillViewport), a
 * drag past the touch slop taken from the children and turned into scrolling.
 */
open class ScrollView(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : FrameLayout(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    var isFillViewport: Boolean = attrs?.android("fillViewport") == "true"
    var isVerticalScrollBarEnabled: Boolean = false
    var overScrollMode: Int = 0
    private var downY = 0f
    private var startScroll = 0
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val child = getChildAt(0) ?: return
        val lp = child.layoutParams as FrameLayout.LayoutParams
        child.measure(
            getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin, lp.width),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val room = measuredHeight - paddingTop - paddingBottom
        if (isFillViewport && child.measuredHeight < room) {
            child.measure(
                MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(room, MeasureSpec.EXACTLY),
            )
        }
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(
                measuredWidth,
                resolveSize(child.measuredHeight + paddingTop + paddingBottom, heightMeasureSpec),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val lp = child.layoutParams as FrameLayout.LayoutParams
        val childLeft = paddingLeft + lp.leftMargin
        child.layout(childLeft, paddingTop + lp.topMargin, childLeft + child.measuredWidth, paddingTop + lp.topMargin + child.measuredHeight)
        scrollY = scrollY.coerceIn(0, maxScroll())
    }

    private fun maxScroll(): Int = maxOf(0, (getChildAt(0)?.measuredHeight ?: 0) - (height - paddingTop - paddingBottom))

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                startScroll = scrollY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (maxScroll() > 0 && kotlin.math.abs(event.y - downY) > TOUCH_SLOP * ResourceFiles.density) dragging = true
        }
        return dragging
    }

    /** As Android's: with a child, the touch is the scroll view's, whether or not it moves. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (childCount == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                startScroll = scrollY
            }
            MotionEvent.ACTION_MOVE -> scrollY = (startScroll - (event.y - downY)).toInt().coerceIn(0, maxScroll())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    fun fullScroll(direction: Int): Boolean {
        scrollY = if (direction == FOCUS_DOWN) maxScroll() else 0
        return true
    }

    fun smoothScrollTo(x: Int, y: Int) {
        scrollY = y.coerceIn(0, maxScroll())
    }

    private companion object {
        const val TOUCH_SLOP = 8f
    }
}

/** A row that scrolls sideways when its one child is wider than it, Android's. */
open class HorizontalScrollView(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : FrameLayout(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    var isFillViewport: Boolean = attrs?.android("fillViewport") == "true"
    var isHorizontalScrollBarEnabled: Boolean = false
    var overScrollMode: Int = 0
    private var downX = 0f
    private var startScroll = 0
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val child = getChildAt(0) ?: return
        val lp = child.layoutParams as FrameLayout.LayoutParams
        child.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height),
        )
        if (isFillViewport && child.measuredWidth < measuredWidth - paddingLeft - paddingRight) {
            child.measure(
                MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY),
            )
        }
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
            setMeasuredDimension(
                resolveSize(child.measuredWidth + paddingLeft + paddingRight, widthMeasureSpec),
                measuredHeight,
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val lp = child.layoutParams as FrameLayout.LayoutParams
        val childTop = paddingTop + lp.topMargin
        child.layout(paddingLeft + lp.leftMargin, childTop, paddingLeft + lp.leftMargin + child.measuredWidth, childTop + child.measuredHeight)
        scrollX = scrollX.coerceIn(0, maxScroll())
    }

    private fun maxScroll(): Int = maxOf(0, (getChildAt(0)?.measuredWidth ?: 0) - (width - paddingLeft - paddingRight))

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                startScroll = scrollX
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (maxScroll() > 0 && kotlin.math.abs(event.x - downX) > TOUCH_SLOP * ResourceFiles.density) dragging = true
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                startScroll = scrollX
            }
            MotionEvent.ACTION_MOVE -> scrollX = (startScroll - (event.x - downX)).toInt().coerceIn(0, maxScroll())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    fun fullScroll(direction: Int): Boolean {
        scrollX = if (direction == FOCUS_RIGHT) maxScroll() else 0
        return true
    }

    fun smoothScrollTo(x: Int, y: Int) {
        scrollX = x.coerceIn(0, maxScroll())
    }

    private companion object {
        const val TOUCH_SLOP = 8f
    }
}

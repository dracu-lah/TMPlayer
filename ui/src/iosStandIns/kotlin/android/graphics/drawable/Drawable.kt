package android.graphics.drawable

import android.content.res.ColorStateList
import android.content.res.ResourceFiles
import android.content.res.XmlNode
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.graphics.vector.PathParser

// Android's drawables on iOS: what a View's background and icons are, read from :app's res/drawable
// XML as Android reads it (shape, selector, layer-list, ripple, vector), drawn on Android's Canvas
// over Skia. Each Java getter is here as Android's function and as the Kotlin property Android's
// code reads it as.

/** Something a View draws in given bounds, Android's. */
abstract class Drawable {
    private val boundsRect = Rect()
    private var stateSet: IntArray = IntArray(0)
    var callback: Callback? = null

    /** What a drawable tells when it needs drawing again, Android's. */
    interface Callback {
        fun invalidateDrawable(who: Drawable)
    }

    abstract fun draw(canvas: Canvas)

    open fun setAlpha(alpha: Int) {}

    open fun getAlpha(): Int = 255

    open fun setColorFilter(colorFilter: ColorFilter?) {}

    open fun getOpacity(): Int = -3

    fun getBounds(): Rect = boundsRect

    val bounds: Rect get() = boundsRect

    fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        if (boundsRect.left == left && boundsRect.top == top && boundsRect.right == right && boundsRect.bottom == bottom) return
        boundsRect.set(left, top, right, bottom)
        onBoundsChange(boundsRect)
    }

    fun setBounds(bounds: Rect) = setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)

    protected open fun onBoundsChange(bounds: Rect) {}

    open fun isStateful(): Boolean = false

    /** The View's state; true when the drawable changed with it. */
    open fun setState(stateSet: IntArray): Boolean {
        if (this.stateSet.contentEquals(stateSet)) return false
        this.stateSet = stateSet
        return onStateChange(stateSet)
    }

    open fun getState(): IntArray = stateSet

    val state: IntArray get() = getState()

    protected open fun onStateChange(state: IntArray): Boolean = false

    open fun getIntrinsicWidth(): Int = -1

    open fun getIntrinsicHeight(): Int = -1

    val intrinsicWidth: Int get() = getIntrinsicWidth()

    val intrinsicHeight: Int get() = getIntrinsicHeight()

    open fun getPadding(padding: Rect): Boolean {
        padding.set(0, 0, 0, 0)
        return false
    }

    open fun setTintList(tint: ColorStateList?) {}

    open fun setTint(tintColor: Int) = setTintList(ColorStateList.valueOf(tintColor))

    open fun setTintMode(tintMode: PorterDuff.Mode?) {}

    open fun mutate(): Drawable = this

    open fun setVisible(visible: Boolean, restart: Boolean): Boolean = false

    fun invalidateSelf() {
        callback?.invalidateDrawable(this)
    }

    companion object {
        /** A drawable of :app's by its reference ("@drawable/bg_controls_pill", "#80000000"), or null. */
        fun fromReference(reference: String?): Drawable? {
            val ref = reference?.trim() ?: return null
            if (ref.startsWith("#") || ref.startsWith("@color/") || ref.startsWith("@android:color/")) {
                val list = ResourceFiles.colorStateList(ref) ?: return null
                return if (list.isStateful()) ColorStateListDrawable(list) else ColorDrawable(list.defaultColor)
            }
            if (ref.startsWith("?")) return null
            if (!ref.startsWith("@drawable/")) return null
            val node = ResourceFiles.xml("drawable/${ref.substringAfter('/')}") ?: return null
            return fromXml(node)
        }

        /** A drawable from its XML element, as Android's DrawableInflater builds it. */
        fun fromXml(node: XmlNode): Drawable? = when (node.name) {
            "shape" -> GradientDrawable.fromXml(node)
            "selector" -> StateListDrawable.fromXml(node)
            "layer-list" -> LayerDrawable.fromXml(node)
            "ripple" -> RippleDrawable.fromXml(node)
            "vector" -> VectorDrawable.fromXml(node)
            "inset" -> node.attribute("drawable")?.let(::fromReference) ?: node.children.firstOrNull()?.let(::fromXml)
            "color" -> ResourceFiles.color(node.attribute("color"))?.let(::ColorDrawable)
            else -> null
        }

        /** An item's drawable: its android:drawable attribute, or the element inside it. */
        internal fun itemDrawable(item: XmlNode): Drawable? =
            item.attribute("drawable")?.let(::fromReference) ?: item.children.firstOrNull()?.let(::fromXml)
    }
}

/** One colour over the bounds, Android's. */
class ColorDrawable(color: Int) : Drawable() {
    constructor() : this(0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var color: Int = color
        set(value) {
            field = value
            invalidateSelf()
        }

    fun getColor(): Int = color

    override fun draw(canvas: Canvas) {
        if (android.graphics.Color.alpha(color) == 0) return
        paint.color = color
        canvas.drawRect(bounds, paint)
    }
}

/** A Bitmap drawn into the drawable's bounds, Android's. */
class BitmapDrawable(val bitmap: android.graphics.Bitmap) : Drawable() {
    override fun draw(canvas: Canvas) {
        if (bitmap.isRecycled || bounds.isEmpty()) return
        canvas.skia.drawImageRect(
            bitmap.image,
            org.jetbrains.skia.Rect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()),
            org.jetbrains.skia.Rect.makeLTRB(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
            org.jetbrains.skia.SamplingMode.LINEAR,
            null,
            true,
        )
    }

    override fun getIntrinsicWidth(): Int = bitmap.width

    override fun getIntrinsicHeight(): Int = bitmap.height
}

/** A colour list's colour for the View's state, Android's. */
class ColorStateListDrawable(private val list: ColorStateList) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean = true

    override fun draw(canvas: Canvas) {
        paint.color = list.getColorForState(state, list.defaultColor)
        canvas.drawRect(bounds, paint)
    }
}

/** A rectangle, oval or line with a fill, an edge, corners and a gradient, Android's shape. */
open class GradientDrawable() : Drawable() {
    enum class Orientation { TOP_BOTTOM, TR_BL, RIGHT_LEFT, BR_TL, BOTTOM_TOP, BL_TR, LEFT_RIGHT, TL_BR }

    /** A gradient along [orientation] through [colors], Android's second constructor. */
    constructor(orientation: Orientation, colors: IntArray?) : this() {
        this.orientation = orientation
        setColors(colors)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** Java's setShape/getShape pair, as Kotlin reads it. */
    var shape: Int = RECTANGLE
        set(value) {
            field = value
            invalidateSelf()
        }
    private var solid: ColorStateList? = null
    private var stroke: ColorStateList? = null
    private var strokeWidth = 0f
    private var radius = 0f
    private var radii: FloatArray? = null
    private var gradientColors: IntArray? = null
    private var gradientPositions: FloatArray? = null
    var orientation: Orientation = Orientation.TOP_BOTTOM
    private var alphaValue = 255

    fun setShape(shape: Int) {
        this.shape = shape
        invalidateSelf()
    }

    fun setColor(color: Int) {
        solid = ColorStateList.valueOf(color)
        gradientColors = null
        invalidateSelf()
    }

    fun setColor(colors: ColorStateList?) {
        solid = colors
        gradientColors = null
        invalidateSelf()
    }

    fun setColors(colors: IntArray?) {
        gradientColors = colors
        gradientPositions = null
        invalidateSelf()
    }

    fun setColors(colors: IntArray?, offsets: FloatArray?) {
        gradientColors = colors
        gradientPositions = offsets
        invalidateSelf()
    }

    fun setStroke(width: Int, color: Int) = setStroke(width, ColorStateList.valueOf(color))

    fun setStroke(width: Int, colorStateList: ColorStateList?) {
        strokeWidth = width.toFloat()
        stroke = colorStateList
        invalidateSelf()
    }

    fun setCornerRadius(radius: Float) {
        this.radius = radius
        radii = null
        invalidateSelf()
    }

    /** Java's getCornerRadius/setCornerRadius pair, as Kotlin sees it. */
    var cornerRadius: Float
        get() = radius
        set(value) = setCornerRadius(value)

    /** The corner the View's outline follows when it clips to it. */
    internal val outlineRadius: Float get() = radii?.firstOrNull() ?: radius

    fun setCornerRadii(radii: FloatArray?) {
        this.radii = radii
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue

    override fun isStateful(): Boolean = solid?.isStateful() == true || stroke?.isStateful() == true

    override fun onStateChange(state: IntArray): Boolean = isStateful()

    override fun draw(canvas: Canvas) {
        val box = RectF(bounds)
        if (box.width() <= 0f || box.height() <= 0f) return
        val colors = gradientColors
        if (colors != null) {
            val (x0, y0, x1, y1) = gradientLine(box)
            fill.shader = LinearGradient(x0, y0, x1, y1, colors, gradientPositions, Shader.TileMode.CLAMP)
            fill.color = android.graphics.Color.BLACK
        } else {
            fill.shader = null
            fill.color = solid?.getColorForState(state, solid?.defaultColor ?: 0) ?: 0
        }
        fill.alpha = (android.graphics.Color.alpha(fill.color) * alphaValue / 255)
        if (colors != null || android.graphics.Color.alpha(fill.color) > 0) drawShape(canvas, box, fill)
        val edgeColor = stroke?.getColorForState(state, stroke?.defaultColor ?: 0) ?: 0
        if (strokeWidth > 0f && android.graphics.Color.alpha(edgeColor) > 0) {
            edge.color = edgeColor
            edge.strokeWidth = strokeWidth
            val half = strokeWidth / 2f
            drawShape(canvas, RectF(box.left + half, box.top + half, box.right - half, box.bottom - half), edge)
        }
    }

    private fun drawShape(canvas: Canvas, box: RectF, paint: Paint) {
        when (shape) {
            OVAL -> canvas.drawOval(box, paint)
            LINE -> canvas.drawLine(box.left, box.centerY(), box.right, box.centerY(), paint)
            else -> {
                val corners = radii
                if (corners != null) {
                    val path = Path()
                    path.addRoundRect(box, corners.map { it.coerceAtMost(minOf(box.width(), box.height()) / 2f) }.toFloatArray(), Path.Direction.CW)
                    canvas.drawPath(path, paint)
                } else if (radius > 0f) {
                    val r = radius.coerceAtMost(minOf(box.width(), box.height()) / 2f)
                    canvas.drawRoundRect(box, r, r, paint)
                } else {
                    canvas.drawRect(box, paint)
                }
            }
        }
    }

    private fun gradientLine(box: RectF): List<Float> = when (orientation) {
        Orientation.TOP_BOTTOM -> listOf(box.left, box.top, box.left, box.bottom)
        Orientation.BOTTOM_TOP -> listOf(box.left, box.bottom, box.left, box.top)
        Orientation.LEFT_RIGHT -> listOf(box.left, box.top, box.right, box.top)
        Orientation.RIGHT_LEFT -> listOf(box.right, box.top, box.left, box.top)
        Orientation.TL_BR -> listOf(box.left, box.top, box.right, box.bottom)
        Orientation.BR_TL -> listOf(box.right, box.bottom, box.left, box.top)
        Orientation.TR_BL -> listOf(box.right, box.top, box.left, box.bottom)
        Orientation.BL_TR -> listOf(box.left, box.bottom, box.right, box.top)
    }

    companion object {
        const val RECTANGLE = 0
        const val OVAL = 1
        const val LINE = 2
        const val RING = 3

        internal fun fromXml(node: XmlNode): GradientDrawable {
            val drawable = GradientDrawable()
            drawable.shape = when (node.attribute("shape")) {
                "oval" -> OVAL
                "line" -> LINE
                "ring" -> RING
                else -> RECTANGLE
            }
            for (child in node.children) {
                when (child.name) {
                    "solid" -> drawable.solid = ResourceFiles.colorStateList(child.attribute("color"))
                    "stroke" -> {
                        drawable.strokeWidth = ResourceFiles.dimension(child.attribute("width")) ?: 0f
                        drawable.stroke = ResourceFiles.colorStateList(child.attribute("color"))
                    }
                    "corners" -> {
                        val all = ResourceFiles.dimension(child.attribute("radius")) ?: 0f
                        val tl = ResourceFiles.dimension(child.attribute("topLeftRadius"))
                        val tr = ResourceFiles.dimension(child.attribute("topRightRadius"))
                        val br = ResourceFiles.dimension(child.attribute("bottomRightRadius"))
                        val bl = ResourceFiles.dimension(child.attribute("bottomLeftRadius"))
                        if (listOf(tl, tr, br, bl).any { it != null }) {
                            drawable.radii = floatArrayOf(
                                tl ?: all, tl ?: all, tr ?: all, tr ?: all, br ?: all, br ?: all, bl ?: all, bl ?: all,
                            )
                        } else {
                            drawable.radius = all
                        }
                    }
                    "gradient" -> {
                        val start = ResourceFiles.color(child.attribute("startColor")) ?: 0
                        val center = ResourceFiles.color(child.attribute("centerColor"))
                        val end = ResourceFiles.color(child.attribute("endColor")) ?: 0
                        drawable.gradientColors = if (center != null) intArrayOf(start, center, end) else intArrayOf(start, end)
                        drawable.orientation = when (((child.attribute("angle")?.toFloatOrNull() ?: 0f).toInt() % 360 + 360) % 360) {
                            45 -> Orientation.BL_TR
                            90 -> Orientation.BOTTOM_TOP
                            135 -> Orientation.BR_TL
                            180 -> Orientation.RIGHT_LEFT
                            225 -> Orientation.TR_BL
                            270 -> Orientation.TOP_BOTTOM
                            315 -> Orientation.TL_BR
                            else -> Orientation.LEFT_RIGHT
                        }
                    }
                }
            }
            return drawable
        }
    }
}

/** The first item whose states the View's state satisfies, Android's selector. */
open class StateListDrawable : Drawable() {
    private val specs = ArrayList<IntArray>()
    private val drawables = ArrayList<Drawable?>()
    private var current: Drawable? = null

    fun addState(stateSet: IntArray, drawable: Drawable?) {
        specs.add(stateSet)
        drawables.add(drawable)
        if (current == null) pick(state)
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean = pick(state)

    private fun pick(state: IntArray): Boolean {
        val index = specs.indices.firstOrNull { ColorStateList.matches(specs[it], state) }
        val next = index?.let { drawables[it] }
        next?.setState(state)
        val changed = next !== current
        current = next
        current?.setBounds(bounds)
        return changed || next?.isStateful() == true
    }

    override fun onBoundsChange(bounds: Rect) {
        current?.setBounds(bounds)
    }

    override fun getIntrinsicWidth(): Int = current?.getIntrinsicWidth() ?: -1

    override fun getIntrinsicHeight(): Int = current?.getIntrinsicHeight() ?: -1

    override fun draw(canvas: Canvas) {
        current?.draw(canvas)
    }

    companion object {
        internal fun fromXml(node: XmlNode): StateListDrawable {
            val list = StateListDrawable()
            for (item in node.children.filter { it.name == "item" }) {
                list.addState(ResourceFiles.stateSpec(item), itemDrawable(item))
            }
            return list
        }
    }
}

/** Drawables over each other, Android's layer list. */
open class LayerDrawable(layers: Array<Drawable?>) : Drawable() {
    protected val layers = layers.toMutableList()
    private val ids = MutableList(layers.size) { 0 }

    fun getNumberOfLayers(): Int = layers.size

    fun getDrawable(index: Int): Drawable? = layers[index]

    fun findDrawableByLayerId(id: Int): Drawable? = ids.indexOf(id).takeIf { it >= 0 }?.let { layers[it] }

    fun setId(index: Int, id: Int) {
        ids[index] = id
    }

    override fun isStateful(): Boolean = layers.any { it?.isStateful() == true }

    override fun onStateChange(state: IntArray): Boolean {
        var changed = false
        layers.forEach { if (it?.setState(state) == true) changed = true }
        return changed
    }

    override fun onBoundsChange(bounds: Rect) {
        layers.forEach { it?.setBounds(bounds) }
    }

    override fun getIntrinsicWidth(): Int = layers.maxOfOrNull { it?.getIntrinsicWidth() ?: -1 } ?: -1

    override fun getIntrinsicHeight(): Int = layers.maxOfOrNull { it?.getIntrinsicHeight() ?: -1 } ?: -1

    override fun draw(canvas: Canvas) {
        layers.forEachIndexed { index, layer -> if (ids[index] != android.R.id.mask) layer?.draw(canvas) }
    }

    companion object {
        internal fun fromXml(node: XmlNode): LayerDrawable {
            val items = node.children.filter { it.name == "item" }
            val drawable = LayerDrawable(items.map { itemDrawable(it) }.toTypedArray())
            items.forEachIndexed { index, item -> if (item.attribute("id") == "@android:id/mask") drawable.setId(index, android.R.id.mask) }
            return drawable
        }
    }
}

/**
 * Android's touch ripple: its content layers drawn, the mask kept out, and a press shown as the
 * ripple's colour over the mask's shape while the View is pressed.
 */
class RippleDrawable(private val color: ColorStateList, content: Drawable?, private val mask: Drawable?) :
    LayerDrawable(arrayOf(content)) {
    private var pressed = false

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        super.onStateChange(state)
        val now = android.R.attr.state_pressed in state
        val changed = now != pressed
        pressed = now
        return true
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        mask?.setBounds(bounds)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (!pressed) return
        val shape = mask ?: return canvas.drawRect(bounds, Paint().apply { this.color = this@RippleDrawable.color.defaultColor })
        val tinted = shape.mutate()
        tinted.setTintList(color)
        tinted.draw(canvas)
    }

    companion object {
        internal fun fromXml(node: XmlNode): RippleDrawable {
            val items = node.children.filter { it.name == "item" }
            val mask = items.firstOrNull { it.attribute("id") == "@android:id/mask" }?.let(::itemDrawable)
            val content = items.firstOrNull { it.attribute("id") != "@android:id/mask" }?.let(::itemDrawable)
            val color = ResourceFiles.colorStateList(node.attribute("color")) ?: ColorStateList.valueOf(0x33FFFFFF)
            return RippleDrawable(color, content, mask?.let { MaskShape(it) })
        }
    }

    /** The mask's shape filled in the ripple's colour. */
    private class MaskShape(private val shape: Drawable) : Drawable() {
        private var tint: ColorStateList? = null

        override fun setTintList(tint: ColorStateList?) {
            this.tint = tint
        }

        override fun onBoundsChange(bounds: Rect) {
            shape.setBounds(bounds)
        }

        override fun draw(canvas: Canvas) {
            val color = tint?.defaultColor ?: return
            val save = canvas.save()
            canvas.skia.saveLayer(null, org.jetbrains.skia.Paint())
            shape.draw(canvas)
            canvas.drawColor(color, PorterDuff.Mode.SRC_IN)
            canvas.restoreToCount(save)
        }
    }
}

/** Android's vector drawable: :app's res/drawable vector XML, its paths and groups, tinted. */
class VectorDrawable private constructor(
    private val widthPx: Int,
    private val heightPx: Int,
    private val viewportWidth: Float,
    private val viewportHeight: Float,
    private val root: XmlNode,
    private var tint: ColorStateList?,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var alphaValue = 255
    private val paths = HashMap<String, org.jetbrains.skia.Path>()

    override fun getIntrinsicWidth(): Int = widthPx

    override fun getIntrinsicHeight(): Int = heightPx

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun isStateful(): Boolean = tint?.isStateful() == true

    override fun onStateChange(state: IntArray): Boolean = isStateful()

    override fun mutate(): Drawable = this

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.isEmpty()) return
        val save = canvas.save()
        canvas.translate(box.left.toFloat(), box.top.toFloat())
        canvas.scale(box.width() / viewportWidth, box.height() / viewportHeight)
        val tintColor = tint?.getColorForState(state, tint?.defaultColor ?: 0)
        drawGroup(canvas, root, tintColor)
        canvas.restoreToCount(save)
    }

    private fun drawGroup(canvas: Canvas, group: XmlNode, tintColor: Int?) {
        for (child in group.children) {
            when (child.name) {
                "group" -> {
                    val save = canvas.save()
                    val px = child.attribute("pivotX")?.toFloatOrNull() ?: 0f
                    val py = child.attribute("pivotY")?.toFloatOrNull() ?: 0f
                    canvas.translate((child.attribute("translateX")?.toFloatOrNull() ?: 0f) + px, (child.attribute("translateY")?.toFloatOrNull() ?: 0f) + py)
                    child.attribute("rotation")?.toFloatOrNull()?.let { canvas.rotate(it) }
                    canvas.scale(child.attribute("scaleX")?.toFloatOrNull() ?: 1f, child.attribute("scaleY")?.toFloatOrNull() ?: 1f)
                    canvas.translate(-px, -py)
                    drawGroup(canvas, child, tintColor)
                    canvas.restoreToCount(save)
                }
                "path" -> drawPath(canvas, child, tintColor)
                "clip-path" -> child.attribute("pathData")?.let { canvas.skia.clipPath(pathOf(it), true) }
            }
        }
    }

    private fun drawPath(canvas: Canvas, node: XmlNode, tintColor: Int?) {
        val data = node.attribute("pathData") ?: return
        val path = pathOf(data)
        path.fillMode = if (node.attribute("fillType") == "evenOdd") org.jetbrains.skia.PathFillMode.EVEN_ODD else org.jetbrains.skia.PathFillMode.WINDING
        val fill = ResourceFiles.color(node.attribute("fillColor"))
        if (fill != null && android.graphics.Color.alpha(fill) > 0) {
            paint.style = Paint.Style.FILL
            paint.color = tintColor?.let { tinted(it, fill) } ?: fill
            paint.alpha = (paint.alpha * (node.attribute("fillAlpha")?.toFloatOrNull() ?: 1f) * alphaValue / 255).toInt()
            canvas.skia.drawPath(path, paint.skia)
        }
        val stroke = ResourceFiles.color(node.attribute("strokeColor"))
        val strokeWidth = node.attribute("strokeWidth")?.toFloatOrNull() ?: 0f
        if (stroke != null && strokeWidth > 0f && android.graphics.Color.alpha(stroke) > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.strokeCap = when (node.attribute("strokeLineCap")) {
                "round" -> Paint.Cap.ROUND
                "square" -> Paint.Cap.SQUARE
                else -> Paint.Cap.BUTT
            }
            paint.strokeJoin = when (node.attribute("strokeLineJoin")) {
                "round" -> Paint.Join.ROUND
                "bevel" -> Paint.Join.BEVEL
                else -> Paint.Join.MITER
            }
            paint.color = tintColor?.let { tinted(it, stroke) } ?: stroke
            paint.alpha = (paint.alpha * (node.attribute("strokeAlpha")?.toFloatOrNull() ?: 1f) * alphaValue / 255).toInt()
            canvas.skia.drawPath(path, paint.skia)
        }
    }

    /** A tint replaces the colour and keeps the path's own alpha, as Android's SRC_IN tint. */
    private fun tinted(tint: Int, color: Int): Int {
        val alpha = android.graphics.Color.alpha(tint) * android.graphics.Color.alpha(color) / 255
        return (alpha shl 24) or (tint and 0xFFFFFF)
    }

    private fun pathOf(data: String): org.jetbrains.skia.Path =
        paths.getOrPut(data) { runCatching { PathParser().parsePathString(data).toPath().asSkiaPath() }.getOrElse { org.jetbrains.skia.Path() } }

    companion object {
        internal fun fromXml(node: XmlNode): VectorDrawable {
            val width = ResourceFiles.dimension(node.attribute("width"))?.toInt() ?: 0
            val height = ResourceFiles.dimension(node.attribute("height"))?.toInt() ?: 0
            return VectorDrawable(
                width,
                height,
                node.attribute("viewportWidth")?.toFloatOrNull() ?: 24f,
                node.attribute("viewportHeight")?.toFloatOrNull() ?: 24f,
                node,
                node.attribute("tint")?.let(ResourceFiles::colorStateList),
            )
        }
    }
}

/** A tint for drawing a drawable in one colour, kept for the code that builds one. */
internal fun tintFilter(color: Int): ColorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

/** A shape filled with a paint, Android's; its shader can be sized to the bounds by a factory. */
open class ShapeDrawable(shape: android.graphics.drawable.shapes.Shape? = null) : Drawable() {
    /** Makes the shader for the drawable's size, Android's; called again when the size changes. */
    abstract class ShaderFactory {
        abstract fun resize(width: Int, height: Int): android.graphics.Shader
    }

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var shape: android.graphics.drawable.shapes.Shape? = shape
        set(value) {
            field = value
            invalidateSelf()
        }

    var shaderFactory: ShaderFactory? = null
        set(value) {
            field = value
            sizedFor = -1 to -1
            invalidateSelf()
        }

    private var sizedFor = -1 to -1

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty()) return
        val size = b.width() to b.height()
        val factory = shaderFactory
        if (factory != null && size != sizedFor) {
            paint.shader = factory.resize(size.first, size.second)
            sizedFor = size
        }
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val outline = shape ?: android.graphics.drawable.shapes.RectShape()
        outline.resize(b.width().toFloat(), b.height().toFloat())
        outline.draw(canvas, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
}

/** A ShapeDrawable that is a rectangle unless given rounded corners, Android's. */
open class PaintDrawable(color: Int = 0) : ShapeDrawable() {
    init {
        paint.color = color
    }

    fun setCornerRadius(radius: Float) {
        shape = if (radius <= 0f) null else android.graphics.drawable.shapes.RoundRectShape(FloatArray(8) { radius }, null, null)
    }
}

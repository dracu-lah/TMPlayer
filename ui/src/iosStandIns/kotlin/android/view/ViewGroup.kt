package android.view

import android.content.Context
import android.content.res.ResourceFiles
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.android

/** A View that holds Views, Android's: measuring, placing, drawing and touching them. */
abstract class ViewGroup(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr), ViewParent {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    private val children = ArrayList<View>()
    var clipChildren: Boolean = true
    var clipToPadding: Boolean = true
    private var touchTarget: View? = null
    private var disallowIntercept = false

    val childCount: Int get() = children.size

    /** Java's platform type: non-null to Kotlin, null passed through past the end, as Android's. */
    fun getChildAt(index: Int): View {
        @Suppress("UNCHECKED_CAST")
        return platformType<View>(children.getOrNull(index))
    }

    fun indexOfChild(child: View?): Int = children.indexOf(child)

    /** The children, as androidx.core.view's ViewGroup.children reads them. */
    val childList: List<View> get() = children.toList()

    fun addView(child: View) = addView(child, -1)

    fun addView(child: View, index: Int) {
        val params = child.layoutParams ?: generateDefaultLayoutParams()
        addView(child, index, params)
    }

    fun addView(child: View, width: Int, height: Int) = addView(child, -1, generateDefaultLayoutParams().also {
        it.width = width
        it.height = height
    })

    fun addView(child: View, params: LayoutParams) = addView(child, -1, params)

    open fun addView(child: View, index: Int, params: LayoutParams) {
        check(child.parent == null) { "The specified child already has a parent." }
        child.layoutParams = if (checkLayoutParams(params)) params else generateLayoutParams(params)
        if (index < 0 || index > children.size) children.add(child) else children.add(index, child)
        child.parent = this
        if (isAttachedToWindow) child.dispatchAttached()
        requestLayout()
        invalidate()
    }

    fun removeView(view: View) {
        val index = children.indexOf(view)
        if (index >= 0) removeViewAt(index)
    }

    fun removeViewAt(index: Int) {
        val child = children.removeAt(index)
        if (touchTarget === child) touchTarget = null
        child.dispatchDetached()
        child.parent = null
        requestLayout()
        invalidate()
    }

    fun removeAllViews() {
        while (children.isNotEmpty()) removeViewAt(children.size - 1)
    }

    override fun dispatchDetached() {
        children.forEach { it.dispatchDetached() }
        super.dispatchDetached()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        children.forEach { it.dispatchAttached() }
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        this.disallowIntercept = disallowIntercept
        parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun invalidateChild(child: View) = invalidate()

    // Layout parameters

    protected open fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    open fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = LayoutParams(context, attrs)

    protected open fun generateLayoutParams(p: LayoutParams?): LayoutParams = p ?: generateDefaultLayoutParams()

    protected open fun checkLayoutParams(p: LayoutParams?): Boolean = p != null

    // Measuring children

    protected fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) {
        val lp = child.layoutParams ?: generateDefaultLayoutParams()
        child.measure(
            getChildMeasureSpec(parentWidthMeasureSpec, paddingLeft + paddingRight, lp.width),
            getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom, lp.height),
        )
    }

    protected fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int, parentHeightMeasureSpec: Int, heightUsed: Int) {
        val lp = child.layoutParams as? MarginLayoutParams ?: return measureChild(child, parentWidthMeasureSpec, parentHeightMeasureSpec)
        child.measure(
            getChildMeasureSpec(parentWidthMeasureSpec, paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + widthUsed, lp.width),
            getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed, lp.height),
        )
    }

    protected open fun measureChildren(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        children.filter { it.visibility != GONE }.forEach { measureChild(it, widthMeasureSpec, heightMeasureSpec) }
    }

    // Drawing children

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        if (clipToPadding) canvas.clipRect(scrollX + paddingLeft, scrollY + paddingTop, scrollX + width - paddingRight, scrollY + height - paddingBottom)
        for (child in children) {
            if (child.visibility == VISIBLE || child.animatingOut()) drawChild(canvas, child)
        }
        canvas.restoreToCount(save)
    }

    /** One child at its place, moved, turned, scaled and faded as it says, as Android draws one. */
    protected open fun drawChild(canvas: Canvas, child: View): Boolean {
        if (child.alpha <= 0f) return false
        val save = canvas.save()
        canvas.translate(child.left - scrollX + child.translationX, child.top - scrollY + child.translationY)
        if (child.rotation != 0f || child.scaleX != 1f || child.scaleY != 1f) {
            canvas.translate(child.pivotX, child.pivotY)
            canvas.rotate(child.rotation)
            canvas.scale(child.scaleX, child.scaleY)
            canvas.translate(-child.pivotX, -child.pivotY)
        }
        if (clipChildren) canvas.clipRect(0, 0, child.width, child.height)
        if (child.clipToOutline) child.clipOutline(canvas)
        if (child.alpha < 1f) {
            canvas.saveLayerAlpha(0f, 0f, child.width.toFloat(), child.height.toFloat(), (child.alpha * 255).toInt())
        }
        child.draw(canvas)
        canvas.restoreToCount(save)
        return true
    }

    // Touch

    open fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

    /**
     * A touch to the child under it, front to back, in the child's coordinates; the rest of the
     * gesture to the child that took its start, as Android's ViewGroup.dispatchTouchEvent.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            touchTarget = null
            disallowIntercept = false
        }
        val intercepted = !disallowIntercept && (action == MotionEvent.ACTION_DOWN || touchTarget != null) && onInterceptTouchEvent(event)
        if (intercepted) {
            touchTarget?.let { target ->
                val cancel = MotionEvent.obtain(event).also { it.setAction(MotionEvent.ACTION_CANCEL) }
                dispatchToChild(target, cancel)
            }
            touchTarget = null
            return super.dispatchTouchEvent(event)
        }
        if (action == MotionEvent.ACTION_DOWN) {
            for (child in children.reversed()) {
                if (child.visibility != VISIBLE || !isTouchInside(child, event.x, event.y)) continue
                if (dispatchToChild(child, event)) {
                    touchTarget = child
                    return true
                }
            }
            return super.dispatchTouchEvent(event)
        }
        val target = touchTarget ?: return super.dispatchTouchEvent(event)
        val handled = dispatchToChild(target, event)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touchTarget = null
        return handled
    }

    private fun dispatchToChild(child: View, event: MotionEvent): Boolean {
        val local = MotionEvent.obtain(event)
        local.offsetLocation(scrollX - child.left - child.translationX, scrollY - child.top - child.translationY)
        return child.dispatchTouchEvent(local)
    }

    private fun isTouchInside(child: View, x: Float, y: Float): Boolean {
        val cx = x + scrollX - child.left - child.translationX
        val cy = y + scrollY - child.top - child.translationY
        return cx >= 0 && cy >= 0 && cx < child.width && cy < child.height
    }

    // Finding

    override fun findViewWithTagTraversal(tag: Any?): View? {
        if (this.tag == tag) return this
        for (child in children) child.findViewWithTagTraversal(tag)?.let { return it }
        return null
    }

    /** Moves [child] to the end of the children, drawn last and so on top, Android's. */
    fun bringChildToFront(child: View) {
        if (!children.remove(child)) return
        children.add(child)
        requestLayout()
        invalidate()
    }

    override fun findViewTraversal(id: Int): View? {
        if (this.id == id) return this
        for (child in children) child.findViewTraversal(id)?.let { return it }
        return null
    }

    override fun findFocus(): View? = if (isFocused) this else children.firstNotNullOfOrNull { it.findFocus() }

    override fun hasFocus(): Boolean = findFocus() != null

    /** Android's size of a View in its parent: a fixed size, MATCH_PARENT or WRAP_CONTENT. */
    open class LayoutParams(var width: Int, var height: Int) {
        constructor(source: LayoutParams) : this(source.width, source.height)

        constructor(context: Context?, attrs: AttributeSet?) : this(size(attrs?.android("layout_width")), size(attrs?.android("layout_height")))

        companion object {
            const val MATCH_PARENT = -1

            @Deprecated("Android's", ReplaceWith("MATCH_PARENT"))
            const val FILL_PARENT = -1
            const val WRAP_CONTENT = -2

            internal fun size(value: String?): Int = when (value) {
                "match_parent", "fill_parent" -> MATCH_PARENT
                "wrap_content", null -> WRAP_CONTENT
                else -> ResourceFiles.dimension(value)?.toInt() ?: WRAP_CONTENT
            }
        }
    }

    /** LayoutParams with margins, Android's. */
    open class MarginLayoutParams(width: Int, height: Int) : LayoutParams(width, height) {
        var leftMargin = 0
        var topMargin = 0
        var rightMargin = 0
        var bottomMargin = 0
        private var startMarginSet: Int? = null
        private var endMarginSet: Int? = null

        constructor(source: LayoutParams?) : this(source?.width ?: WRAP_CONTENT, source?.height ?: WRAP_CONTENT) {
            if (source is MarginLayoutParams) {
                leftMargin = source.leftMargin
                topMargin = source.topMargin
                rightMargin = source.rightMargin
                bottomMargin = source.bottomMargin
                startMarginSet = source.startMarginSet
                endMarginSet = source.endMarginSet
            }
        }

        constructor(context: Context?, attrs: AttributeSet?) : this(LayoutParams.size(attrs?.android("layout_width")), LayoutParams.size(attrs?.android("layout_height"))) {
            val all = ResourceFiles.dimension(attrs?.android("layout_margin"))?.toInt()
            val horizontal = ResourceFiles.dimension(attrs?.android("layout_marginHorizontal"))?.toInt()
            val vertical = ResourceFiles.dimension(attrs?.android("layout_marginVertical"))?.toInt()
            leftMargin = ResourceFiles.dimension(attrs?.android("layout_marginLeft"))?.toInt() ?: horizontal ?: all ?: 0
            rightMargin = ResourceFiles.dimension(attrs?.android("layout_marginRight"))?.toInt() ?: horizontal ?: all ?: 0
            topMargin = ResourceFiles.dimension(attrs?.android("layout_marginTop"))?.toInt() ?: vertical ?: all ?: 0
            bottomMargin = ResourceFiles.dimension(attrs?.android("layout_marginBottom"))?.toInt() ?: vertical ?: all ?: 0
            startMarginSet = ResourceFiles.dimension(attrs?.android("layout_marginStart"))?.toInt()
            endMarginSet = ResourceFiles.dimension(attrs?.android("layout_marginEnd"))?.toInt()
            resolveLayoutDirection(View.LAYOUT_DIRECTION_LTR)
        }

        var marginStart: Int
            get() = startMarginSet ?: leftMargin
            set(value) {
                startMarginSet = value
                leftMargin = value
            }

        var marginEnd: Int
            get() = endMarginSet ?: rightMargin
            set(value) {
                endMarginSet = value
                rightMargin = value
            }

        fun setMargins(left: Int, top: Int, right: Int, bottom: Int) {
            leftMargin = left
            topMargin = top
            rightMargin = right
            bottomMargin = bottom
            startMarginSet = null
            endMarginSet = null
        }

        fun setMarginsRelative(start: Int, top: Int, end: Int, bottom: Int) {
            startMarginSet = start
            endMarginSet = end
            topMargin = top
            bottomMargin = bottom
            resolveLayoutDirection(View.LAYOUT_DIRECTION_LTR)
        }

        /** Start and end as left and right for the direction the View lays out in. */
        fun resolveLayoutDirection(layoutDirection: Int) {
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            startMarginSet?.let { if (rtl) rightMargin = it else leftMargin = it }
            endMarginSet?.let { if (rtl) leftMargin = it else rightMargin = it }
        }
    }

    companion object {
        /** Android's spec for a child from its parent's spec, the room taken, and what the child asks for. */
        fun getChildMeasureSpec(spec: Int, padding: Int, childDimension: Int): Int {
            val specMode = MeasureSpec.getMode(spec)
            val size = maxOf(0, MeasureSpec.getSize(spec) - padding)
            return when {
                childDimension >= 0 -> MeasureSpec.makeMeasureSpec(childDimension, MeasureSpec.EXACTLY)
                childDimension == LayoutParams.MATCH_PARENT -> when (specMode) {
                    MeasureSpec.EXACTLY -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
                    MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
                    else -> MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                }
                else -> when (specMode) {
                    MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
                    else -> MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                }
            }
        }
    }
}

/** Whether a View fading out under its animator still draws, as Android's transitions let it. */
internal fun View.animatingOut(): Boolean = false

/** A value Java would hand Kotlin as a platform type: declared non-null, a null let through. */
@Suppress("UNCHECKED_CAST")
internal fun <T> platformType(value: Any?): T = value as T

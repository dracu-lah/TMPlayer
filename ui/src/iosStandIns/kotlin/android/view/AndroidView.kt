package android.view

import android.content.Context
import android.content.res.ResourceFiles
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.android

// Android's View on iOS: measure, layout, draw, touch, focus and state as Android does them, so
// Android's own Views and layouts (the player's controls, docs/IOS.md, layer 3) run as
// upstream wrote them. A tree of Views draws on Android's Canvas over Skia, in a host that puts it
// on screen (com.tmplayer.ios.AndroidViewHost). Sizes are pixels, one dp being one iOS point.

/** What holds a View, Android's. */
interface ViewParent {
    val parent: ViewParent?

    fun requestLayout()

    fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean)

    fun invalidateChild(child: View)
}

open class View(context: Context?, attrs: AttributeSet?, @Suppress("UNUSED_PARAMETER") defStyleAttr: Int) : Drawable.Callback {
    /** Never null, as on Android: a View made without one has the app's. */
    val context: Context = context ?: androidx.compose.ui.platform.IosAppContext

    // True while this constructor runs: what a subclass overrides is not called before it exists.
    private var constructing = true

    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    /** What a click does, Android's. */
    fun interface OnClickListener {
        fun onClick(v: View)
    }

    fun interface OnLongClickListener {
        fun onLongClick(v: View): Boolean
    }

    fun interface OnTouchListener {
        fun onTouch(v: View, event: MotionEvent): Boolean
    }

    fun interface OnFocusChangeListener {
        fun onFocusChange(v: View, hasFocus: Boolean)
    }

    fun interface OnLayoutChangeListener {
        fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int)
    }

    interface OnAttachStateChangeListener {
        fun onViewAttachedToWindow(v: View)

        fun onViewDetachedFromWindow(v: View)
    }

    // Identity

    var id: Int = NO_ID
    var tag: Any? = null

    /** The listeners for this tree's layout, drawing and focus, Android's (see [ViewTreeObserver]). */
    val viewTreeObserver: ViewTreeObserver = ViewTreeObserver()
    private val keyedTags = HashMap<Int, Any?>()
    var contentDescription: CharSequence? = null

    /** Accessibility actions' labels by Android's action id, from ViewCompat.replaceAccessibilityAction. */
    internal val accessibilityActionLabels = HashMap<Int, CharSequence?>()

    /** The window insets listener androidx's ViewCompat sets (an OnApplyWindowInsetsListener). */
    internal var windowInsetsListener: Any? = null

    /** The state read after the name (Playing, Paused), Android 11's; VoiceOver's value. */
    var stateDescription: CharSequence? = null

    /** The label a pointer's hover shows, Android 8's; iPadOS shows it for a trackpad's pointer. */
    var tooltipText: CharSequence? = null

    /**
     * Where, in this View's own coordinates, a swipe in from the screen's edge is the View's and
     * not the system's Back, Android's: the iOS shell asks before taking the swipe as Back.
     */
    var systemGestureExclusionRects: List<android.graphics.Rect> = emptyList()

    fun setSystemGestureExclusionRects(rects: List<android.graphics.Rect>) {
        systemGestureExclusionRects = rects.map { android.graphics.Rect(it) }
    }
    var importantForAccessibility: Int = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    var accessibilityLiveRegion: Int = 0
    var keepScreenOn: Boolean = false

    fun setTag(key: Int, tag: Any?) {
        keyedTags[key] = tag
    }

    fun getTag(key: Int): Any? = keyedTags[key]

    // The tree

    var parent: ViewParent? = null
        internal set
    val rootView: View get() = generateSequence(this) { it.parent as? View }.last()
    var isAttachedToWindow: Boolean = false
        private set
    private val attachListeners = ArrayList<OnAttachStateChangeListener>()

    /** The host that shows this tree, set on its root: told when the tree needs drawing or laying out. */
    var host: Host? = null

    /** What shows a tree of Views on iOS (com.tmplayer.ios.AndroidViewHost). */
    interface Host {
        fun invalidateTree()

        fun requestTreeLayout()

        /** A View that shows content of its own over the tree (ComposeView) joined it. */
        fun overlayAttached(view: View) {}

        fun overlayDetached(view: View) {}

        /** A SurfaceView with an iOS view of its own (the video engine's) joined or left the tree. */
        fun nativeAttached(view: SurfaceView) {}

        fun nativeDetached(view: SurfaceView) {}
    }

    internal fun dispatchAttached() {
        if (isAttachedToWindow) return
        isAttachedToWindow = true
        onAttachedToWindow()
        attachListeners.toList().forEach { it.onViewAttachedToWindow(this) }
    }

    internal open fun dispatchDetached() {
        if (!isAttachedToWindow) return
        isAttachedToWindow = false
        onDetachedFromWindow()
        attachListeners.toList().forEach { it.onViewDetachedFromWindow(this) }
    }

    /** Attaches this tree as a host's root. */
    fun attachAsRoot(host: Host) {
        this.host = host
        dispatchAttached()
    }

    fun detachAsRoot() {
        dispatchDetached()
        host = null
    }

    protected open fun onAttachedToWindow() {}

    protected open fun onDetachedFromWindow() {}

    fun addOnAttachStateChangeListener(listener: OnAttachStateChangeListener) {
        attachListeners.add(listener)
    }

    fun removeOnAttachStateChangeListener(listener: OnAttachStateChangeListener) {
        attachListeners.remove(listener)
    }

    val resources: Resources get() = context?.resources ?: Resources.Shared

    // Geometry

    var left: Int = 0
        private set
    var top: Int = 0
        private set
    var right: Int = 0
        private set
    var bottom: Int = 0
        private set
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    var measuredWidth: Int = 0
        private set
    var measuredHeight: Int = 0
        private set
    val measuredWidthAndState: Int get() = measuredWidth
    val measuredHeightAndState: Int get() = measuredHeight
    var minimumWidth: Int = 0
    var minimumHeight: Int = 0
    var layoutParams: ViewGroup.LayoutParams? = null
        set(value) {
            field = value
            requestLayout()
        }
    private val layoutListeners = ArrayList<OnLayoutChangeListener>()
    var isLayoutRequested: Boolean = true
        private set

    var paddingLeft: Int = 0
        private set
    var paddingTop: Int = 0
        private set
    var paddingRight: Int = 0
        private set
    var paddingBottom: Int = 0
        private set
    val paddingStart: Int get() = if (isLayoutRtl) paddingRight else paddingLeft
    val paddingEnd: Int get() = if (isLayoutRtl) paddingLeft else paddingRight

    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        if (paddingLeft == left && paddingTop == top && paddingRight == right && paddingBottom == bottom) return
        paddingLeft = left
        paddingTop = top
        paddingRight = right
        paddingBottom = bottom
        requestLayout()
        invalidate()
    }

    fun setPaddingRelative(start: Int, top: Int, end: Int, bottom: Int) =
        if (isLayoutRtl) setPadding(end, top, start, bottom) else setPadding(start, top, end, bottom)

    var layoutDirection: Int = LAYOUT_DIRECTION_INHERIT
        set(value) {
            field = value
            requestLayout()
        }

    /** Right to left, from this View or the nearest one above that says. */
    val isLayoutRtl: Boolean
        get() {
            var view: View? = this
            while (view != null) {
                when (view.layoutDirection) {
                    LAYOUT_DIRECTION_RTL -> return true
                    LAYOUT_DIRECTION_LTR -> return false
                }
                view = view.parent as? View
            }
            return false
        }

    // Drawing state

    var alpha: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            // The parent draws it with its alpha, and a view gone fully clear asks nothing itself.
            (parent as? View)?.invalidate() ?: invalidate()
        }
    var translationX: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    var translationY: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    var scaleX: Float = 1f
        set(value) {
            field = value
            invalidate()
        }
    var scaleY: Float = 1f
        set(value) {
            field = value
            invalidate()
        }
    var rotation: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    private var pivotXSet: Float? = null
    private var pivotYSet: Float? = null
    var pivotX: Float
        get() = pivotXSet ?: (width / 2f)
        set(value) {
            pivotXSet = value
            invalidate()
        }
    var pivotY: Float
        get() = pivotYSet ?: (height / 2f)
        set(value) {
            pivotYSet = value
            invalidate()
        }
    var elevation: Float = 0f
    var translationZ: Float = 0f
    var scrollX: Int = 0
        set(value) {
            field = value
            invalidate()
        }
    var scrollY: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    fun scrollTo(x: Int, y: Int) {
        scrollX = x
        scrollY = y
    }

    fun scrollBy(x: Int, y: Int) = scrollTo(scrollX + x, scrollY + y)

    var visibility: Int = VISIBLE
        set(value) {
            if (field == value) return
            val wasGone = field == GONE
            field = value
            if (wasGone || value == GONE) requestLayout()
            (parent as? View)?.invalidate()
            invalidate()
        }

    val isShown: Boolean
        get() {
            var view: View? = this
            while (view != null) {
                if (view.visibility != VISIBLE) return false
                view = view.parent as? View
            }
            return isAttachedToWindow
        }

    var background: Drawable? = null
        set(value) {
            if (field === value) return
            field?.callback = null
            field = value
            value?.callback = this
            value?.setState(drawableState)
            requestLayout()
            invalidate()
        }

    fun setBackgroundColor(color: Int) {
        val current = background
        if (current is ColorDrawable) current.color = color else background = ColorDrawable(color)
    }

    fun setBackgroundResource(resid: Int) {
        background = if (resid == 0) null else drawableForResource(resid)
    }

    fun setBackgroundDrawable(drawable: Drawable?) {
        background = drawable
    }

    var foreground: Drawable? = null
        set(value) {
            field = value
            value?.callback = this
            invalidate()
        }

    // States

    var isEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            refreshDrawableState()
        }
    var isClickable: Boolean = false
    var isLongClickable: Boolean = false
    var isFocusable: Boolean = false
    var isFocusableInTouchMode: Boolean = false

    /** Takes the parent's drawable state (the skip pill's words turn dark with its focused chip). */
    var isDuplicateParentStateEnabled: Boolean = false
    var isSelected: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshDrawableState()
        }
    var isActivated: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshDrawableState()
        }
    var isPressed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshDrawableState()
        }
    var isHovered: Boolean = false
    var isSoundEffectsEnabled: Boolean = true
    var isHapticFeedbackEnabled: Boolean = true
    var nextFocusUpId: Int = NO_ID
    var nextFocusDownId: Int = NO_ID
    var nextFocusLeftId: Int = NO_ID
    var nextFocusRightId: Int = NO_ID
    var nextFocusForwardId: Int = NO_ID
    var onFocusChangeListener: OnFocusChangeListener? = null
    private var focused = false

    val isFocused: Boolean get() = focused

    open fun hasFocus(): Boolean = focused

    fun requestFocus(): Boolean {
        if (!isFocusable || visibility != VISIBLE) return false
        rootView.findFocus()?.takeIf { it !== this }?.clearFocus()
        if (!focused) {
            focused = true
            refreshDrawableState()
            onFocusChanged(true, FOCUS_DOWN, null)
            onFocusChangeListener?.onFocusChange(this, true)
        }
        return true
    }

    fun clearFocus() {
        if (!focused) return
        focused = false
        refreshDrawableState()
        onFocusChanged(false, 0, null)
        onFocusChangeListener?.onFocusChange(this, false)
    }

    open fun findFocus(): View? = if (focused) this else null

    protected open fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {}

    /** The states a drawable or colour list picks by, Android's state ids. */
    val drawableState: IntArray
        get() = buildList {
            if (isEnabled) add(android.R.attr.state_enabled)
            if (focused) add(android.R.attr.state_focused)
            if (isPressed) add(android.R.attr.state_pressed)
            if (isSelected) add(android.R.attr.state_selected)
            if (isActivated) add(android.R.attr.state_activated)
            if (isHovered) add(android.R.attr.state_hovered)
            add(android.R.attr.state_window_focused)
        }.toIntArray()

    fun refreshDrawableState() {
        if (constructing) return
        drawableStateChanged()
        invalidate()
    }

    protected open fun drawableStateChanged() {
        background?.setState(drawableState)
        foreground?.setState(drawableState)
    }

    // Measure and layout

    fun measure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    protected open fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            getDefaultSize(suggestedMinimumHeight, heightMeasureSpec),
        )
    }

    protected fun setMeasuredDimension(measuredWidth: Int, measuredHeight: Int) {
        this.measuredWidth = measuredWidth
        this.measuredHeight = measuredHeight
    }

    protected val suggestedMinimumWidth: Int
        get() = maxOf(minimumWidth, background?.getIntrinsicWidth()?.coerceAtLeast(0) ?: 0)

    protected val suggestedMinimumHeight: Int
        get() = maxOf(minimumHeight, background?.getIntrinsicHeight()?.coerceAtLeast(0) ?: 0)

    fun layout(l: Int, t: Int, r: Int, b: Int) {
        val oldL = left
        val oldT = top
        val oldR = right
        val oldB = bottom
        val changed = oldL != l || oldT != t || oldR != r || oldB != b
        left = l
        top = t
        right = r
        bottom = b
        if (changed) {
            background?.setBounds(0, 0, r - l, b - t)
            if (oldR - oldL != r - l || oldB - oldT != b - t) onSizeChanged(r - l, b - t, oldR - oldL, oldB - oldT)
        }
        onLayout(changed, l, t, r, b)
        isLayoutRequested = false
        if (changed) layoutListeners.toList().forEach { it.onLayoutChange(this, l, t, r, b, oldL, oldT, oldR, oldB) }
    }

    protected open fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {}

    protected open fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {}

    fun addOnLayoutChangeListener(listener: OnLayoutChangeListener) {
        layoutListeners.add(listener)
    }

    fun removeOnLayoutChangeListener(listener: OnLayoutChangeListener) {
        layoutListeners.remove(listener)
    }

    open fun requestLayout() {
        isLayoutRequested = true
        if (constructing) return
        val up = parent
        if (up != null) up.requestLayout() else host?.requestTreeLayout()
    }

    fun forceLayout() {
        isLayoutRequested = true
    }

    // Drawing

    /**
     * Asks the window to draw again, as Android's does: not for a view that is not shown, or sits
     * in one that is not (Android skips it, and drops what a view not drawn asks), since nothing of
     * it is on the screen. A hidden view that animates (a spinner, a loader) would otherwise have
     * the whole tree drawn every frame. Nor for one fully clear, or in one that is: ViewGroup
     * leaves those out of the drawing. Showing, hiding or fading one asks its parent ([visibility],
     * [alpha]).
     */
    fun invalidate() {
        var view: View = this
        while (true) {
            if (view.visibility != VISIBLE || view.alpha <= 0f) return
            val up = view.parent as? View ?: break
            view = up
        }
        invalidationTrace?.invoke(this)
        view.host?.invalidateTree()
    }

    /** Whether anything of it is drawn: [isShown], and neither it nor a parent fully clear. */
    val isDrawn: Boolean
        get() {
            if (!isShown) return false
            var view: View? = this
            while (view != null) {
                if (view.alpha <= 0f) return false
                view = view.parent as? View
            }
            return true
        }

    fun invalidate(l: Int, t: Int, r: Int, b: Int) = invalidate()

    fun postInvalidate() = invalidate()

    fun postInvalidateOnAnimation() = invalidate()

    override fun invalidateDrawable(who: Drawable) = invalidate()

    /** Whether drawing stays inside [outlineProvider]'s outline (rounded corners), Android's. */
    var clipToOutline: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var outlineProvider: ViewOutlineProvider? = ViewOutlineProvider.BACKGROUND

    /** Clips [canvas], in this View's own coordinates, to its outline. */
    internal fun clipOutline(canvas: Canvas) {
        val outline = android.graphics.Outline()
        outlineProvider?.getOutline(this, outline) ?: return
        if (outline.isEmpty()) return
        val r = outline.rect
        canvas.clipRoundRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), outline.radius)
    }

    /** Background, content, children and foreground, as Android's View.draw. */
    open fun draw(canvas: Canvas) {
        background?.let { drawable ->
            drawable.setBounds(0, 0, width, height)
            if (scrollX == 0 && scrollY == 0) {
                drawable.draw(canvas)
            } else {
                canvas.translate(scrollX.toFloat(), scrollY.toFloat())
                drawable.draw(canvas)
                canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            }
        }
        onDraw(canvas)
        dispatchDraw(canvas)
        foreground?.let {
            it.setBounds(0, 0, width, height)
            it.draw(canvas)
        }
    }

    protected open fun onDraw(canvas: Canvas) {}

    protected open fun dispatchDraw(canvas: Canvas) {}

    // Touch

    private var onClick: OnClickListener? = null
    private var onLongClick: OnLongClickListener? = null
    private var onTouch: OnTouchListener? = null

    fun setOnClickListener(listener: OnClickListener?) {
        onClick = listener
        if (listener != null) isClickable = true
    }

    fun setOnLongClickListener(listener: OnLongClickListener?) {
        onLongClick = listener
        if (listener != null) isLongClickable = true
    }

    fun setOnTouchListener(listener: OnTouchListener?) {
        onTouch = listener
    }

    fun hasOnClickListeners(): Boolean = onClick != null

    open fun performClick(): Boolean {
        val listener = onClick ?: return false
        listener.onClick(this)
        return true
    }

    open fun performLongClick(): Boolean = onLongClick?.onLongClick(this) == true

    open fun callOnClick(): Boolean = performClick()

    open fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (onTouch?.onTouch(this, event) == true) return true
        return onTouchEvent(event)
    }

    /** A press and a click, as Android's View.onTouchEvent for a clickable View. */
    open fun onTouchEvent(event: MotionEvent): Boolean {
        val clickable = isClickable || isLongClickable
        if (!isEnabled) return clickable
        if (!clickable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> isPressed = true
            MotionEvent.ACTION_MOVE -> if (event.x < 0 || event.y < 0 || event.x > width || event.y > height) isPressed = false
            MotionEvent.ACTION_UP -> {
                val wasPressed = isPressed
                isPressed = false
                if (wasPressed) performClick()
            }
            MotionEvent.ACTION_CANCEL -> isPressed = false
        }
        return true
    }

    // Finding Views

    /**
     * Android's findViewById returns a Java platform type, which Kotlin code takes as nullable or
     * not as it likes (`val play: ImageButton = findViewById(R.id.play)`, or `?.` after it). The
     * closest Kotlin has is the declared non-null type with the null passed through, as Android's.
     */
    fun <T : View> findViewById(id: Int): T {
        @Suppress("UNCHECKED_CAST")
        return findViewTraversal(id) as T
    }

    fun <T : View> requireViewById(id: Int): T {
        @Suppress("UNCHECKED_CAST")
        return (findViewTraversal(id) ?: throw IllegalArgumentException("ID does not reference a View inside this View")) as T
    }

    internal open fun findViewTraversal(id: Int): View? = if (this.id == id) this else null

    /** The View below this one with [tag], as Java hands it to Kotlin: null passed through when none has it. */
    fun <T : View> findViewWithTag(tag: Any?): T {
        @Suppress("UNCHECKED_CAST")
        return findViewWithTagTraversal(tag) as T
    }

    internal open fun findViewWithTagTraversal(tag: Any?): View? = if (this.tag == tag) this else null

    /** Drawn over its siblings from now on, Android's: moved to the end of its parent's children. */
    fun bringToFront() {
        (parent as? ViewGroup)?.bringChildToFront(this)
    }

    // Where it is

    fun getLocationInWindow(outLocation: IntArray) {
        val (x, y) = mapToWindow(0f, 0f)
        outLocation[0] = x.toInt()
        outLocation[1] = y.toInt()
    }

    /**
     * A point in this View's coordinates in the window's, through every View's scale (around its
     * pivot) and translation up the tree, as Android maps it (rotation aside, which nothing here
     * turns while placing).
     */
    fun mapToWindow(localX: Float, localY: Float): Pair<Float, Float> {
        var x = localX
        var y = localY
        var view: View? = this
        while (view != null) {
            if (view.scaleX != 1f || view.scaleY != 1f) {
                x = view.pivotX + (x - view.pivotX) * view.scaleX
                y = view.pivotY + (y - view.pivotY) * view.scaleY
            }
            x += view.left + view.translationX - ((view.parent as? View)?.scrollX ?: 0)
            y += view.top + view.translationY - ((view.parent as? View)?.scrollY ?: 0)
            view = view.parent as? View
        }
        return x to y
    }

    fun getLocationOnScreen(outLocation: IntArray) = getLocationInWindow(outLocation)

    fun getHitRect(outRect: Rect) = outRect.set(left, top, right, bottom)

    fun getDrawingRect(outRect: Rect) = outRect.set(scrollX, scrollY, scrollX + width, scrollY + height)

    fun getGlobalVisibleRect(r: Rect): Boolean {
        val location = IntArray(2)
        getLocationInWindow(location)
        r.set(location[0], location[1], location[0] + width, location[1] + height)
        return isShown
    }

    // Animation and the main thread

    private var animator: ViewPropertyAnimator? = null

    fun animate(): ViewPropertyAnimator = animator ?: ViewPropertyAnimator(this).also { animator = it }

    fun clearAnimation() {
        animator?.cancel()
    }

    /** A key for a focused View. No remote reaches a View on iOS; here so FineTimeBar's override compiles. */
    open fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false

    // One Handler for every View, as a window's Views share one on Android, so removeCallbacks
    // takes back what any View posted.
    fun post(action: java.lang.Runnable): Boolean = ViewHandler.post(action)

    fun postDelayed(action: java.lang.Runnable, delayMillis: Long): Boolean = ViewHandler.postDelayed(action, delayMillis)

    fun removeCallbacks(action: java.lang.Runnable?): Boolean {
        if (action != null) ViewHandler.removeCallbacks(action)
        return true
    }

    fun postOnAnimation(action: java.lang.Runnable) {
        post(action)
    }

    // Feedback and accessibility

    fun performHapticFeedback(constant: Int): Boolean = false

    fun performHapticFeedback(constant: Int, flags: Int): Boolean = false

    fun announceForAccessibility(text: CharSequence?) {}

    fun sendAccessibilityEvent(eventType: Int) {}

    /** What a screen reader asks of a view group; the iOS port has no events to send. */
    open class AccessibilityDelegate {
        open fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: android.view.accessibility.AccessibilityEvent): Boolean = true

        /** Where a screen reader's description of [host] can be changed (its class name, state). */
        open fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {}
    }

    /** What a screen reader reads of this View; a subclass adds to [info]. */
    open fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {}

    /** A screen reader performing [action] (Android's id, 4096 forward, 8192 backward); false when unhandled. */
    open fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = false

    private var readerDelegate: AccessibilityDelegate? = null

    fun setAccessibilityDelegate(delegate: AccessibilityDelegate?) {
        readerDelegate = delegate
    }

    fun playSoundEffect(soundConstant: Int) {}

    fun setOnApplyWindowInsetsListener(listener: Any?) {}

    init {
        if (attrs != null) applyViewAttributes(attrs)
        constructing = false
    }

    /** The attributes every View reads from a layout, as Android's View constructor. */
    private fun applyViewAttributes(attrs: AttributeSet) {
        attrs.android("id")?.let { ref -> if (ref.startsWith("@+id/") || ref.startsWith("@id/")) id = ResourceFiles.idOf(ref.substringAfter('/')) }
        attrs.android("background")?.let { background = Drawable.fromReference(it) }
        attrs.android("foreground")?.let { foreground = Drawable.fromReference(it) }
        val all = ResourceFiles.dimension(attrs.android("padding"))?.toInt()
        val horizontal = ResourceFiles.dimension(attrs.android("paddingHorizontal"))?.toInt()
        val vertical = ResourceFiles.dimension(attrs.android("paddingVertical"))?.toInt()
        val start = ResourceFiles.dimension(attrs.android("paddingStart"))?.toInt()
        val end = ResourceFiles.dimension(attrs.android("paddingEnd"))?.toInt()
        val rtl = attrs.android("layoutDirection") == "rtl"
        paddingLeft = (if (rtl) end else start) ?: ResourceFiles.dimension(attrs.android("paddingLeft"))?.toInt() ?: horizontal ?: all ?: 0
        paddingRight = (if (rtl) start else end) ?: ResourceFiles.dimension(attrs.android("paddingRight"))?.toInt() ?: horizontal ?: all ?: 0
        paddingTop = ResourceFiles.dimension(attrs.android("paddingTop"))?.toInt() ?: vertical ?: all ?: 0
        paddingBottom = ResourceFiles.dimension(attrs.android("paddingBottom"))?.toInt() ?: vertical ?: all ?: 0
        attrs.android("visibility")?.let { visibility = when (it) { "gone" -> GONE; "invisible" -> INVISIBLE; else -> VISIBLE } }
        attrs.android("alpha")?.toFloatOrNull()?.let { alpha = it }
        attrs.android("contentDescription")?.let { contentDescription = ResourceFiles.string(it) }
        attrs.android("minWidth")?.let { minimumWidth = ResourceFiles.dimension(it)?.toInt() ?: 0 }
        attrs.android("minHeight")?.let { minimumHeight = ResourceFiles.dimension(it)?.toInt() ?: 0 }
        attrs.android("clickable")?.let { isClickable = it == "true" }
        attrs.android("longClickable")?.let { isLongClickable = it == "true" }
        attrs.android("focusable")?.let { isFocusable = it == "true" }
        attrs.android("focusableInTouchMode")?.let { isFocusableInTouchMode = it == "true" }
        attrs.android("enabled")?.let { isEnabled = it != "false" }
        attrs.android("layoutDirection")?.let {
            layoutDirection = when (it) {
                "rtl" -> LAYOUT_DIRECTION_RTL
                "ltr" -> LAYOUT_DIRECTION_LTR
                "locale" -> LAYOUT_DIRECTION_LOCALE
                else -> LAYOUT_DIRECTION_INHERIT
            }
        }
        attrs.android("importantForAccessibility")?.let {
            importantForAccessibility = when (it) {
                "yes" -> IMPORTANT_FOR_ACCESSIBILITY_YES
                "no" -> IMPORTANT_FOR_ACCESSIBILITY_NO
                "noHideDescendants" -> IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else -> IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
        }
        attrs.android("tag")?.let { tag = it }
        listOf("nextFocusUp", "nextFocusDown", "nextFocusLeft", "nextFocusRight", "nextFocusForward").forEach { name ->
            val ref = attrs.android(name) ?: return@forEach
            val target = ResourceFiles.idOf(ref.substringAfter('/'))
            when (name) {
                "nextFocusUp" -> nextFocusUpId = target
                "nextFocusDown" -> nextFocusDownId = target
                "nextFocusLeft" -> nextFocusLeftId = target
                "nextFocusRight" -> nextFocusRightId = target
                else -> nextFocusForwardId = target
            }
        }
    }

    companion object {
        /** For a developer's check (PerfProbe): told of every view that asks the window to draw. */
        var invalidationTrace: ((View) -> Unit)? = null

        private class FloatProperty(name: String, private val read: (View) -> Float, private val write: (View, Float) -> Unit) :
            android.util.Property<View, Float>(Float::class, name) {
            override fun get(target: View): Float = read(target)

            override fun set(target: View, value: Float) = write(target, value)
        }

        /** Android's View properties, for animators. */
        val ALPHA: android.util.Property<View, Float> = FloatProperty("alpha", { it.alpha }) { v, x -> v.alpha = x }
        val TRANSLATION_X: android.util.Property<View, Float> = FloatProperty("translationX", { it.translationX }) { v, x -> v.translationX = x }
        val TRANSLATION_Y: android.util.Property<View, Float> = FloatProperty("translationY", { it.translationY }) { v, x -> v.translationY = x }
        val SCALE_X: android.util.Property<View, Float> = FloatProperty("scaleX", { it.scaleX }) { v, x -> v.scaleX = x }
        val SCALE_Y: android.util.Property<View, Float> = FloatProperty("scaleY", { it.scaleY }) { v, x -> v.scaleY = x }
        val ROTATION: android.util.Property<View, Float> = FloatProperty("rotation", { it.rotation }) { v, x -> v.rotation = x }

        const val NO_ID = -1
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8

        const val LAYOUT_DIRECTION_LTR = 0
        const val LAYOUT_DIRECTION_RTL = 1
        const val LAYOUT_DIRECTION_INHERIT = 2
        const val LAYOUT_DIRECTION_LOCALE = 3

        const val TEXT_DIRECTION_LTR = 3
        const val TEXT_DIRECTION_RTL = 4
        const val TEXT_DIRECTION_LOCALE = 5

        const val IMPORTANT_FOR_ACCESSIBILITY_AUTO = 0
        const val IMPORTANT_FOR_ACCESSIBILITY_YES = 1
        const val IMPORTANT_FOR_ACCESSIBILITY_NO = 2
        const val IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 4
        const val ACCESSIBILITY_LIVE_REGION_NONE = 0
        const val ACCESSIBILITY_LIVE_REGION_POLITE = 1

        const val FOCUS_BACKWARD = 1
        const val FOCUS_FORWARD = 2
        const val FOCUS_LEFT = 17
        const val FOCUS_UP = 33
        const val FOCUS_RIGHT = 66
        const val FOCUS_DOWN = 130

        const val SYSTEM_UI_FLAG_VISIBLE = 0
        const val SYSTEM_UI_FLAG_HIDE_NAVIGATION = 2
        const val SYSTEM_UI_FLAG_FULLSCREEN = 4

        private var nextGeneratedId = 1

        fun generateViewId(): Int = nextGeneratedId++

        fun resolveSize(size: Int, measureSpec: Int): Int = resolveSizeAndState(size, measureSpec, 0)

        fun resolveSizeAndState(size: Int, measureSpec: Int, childMeasuredState: Int): Int {
            val mode = MeasureSpec.getMode(measureSpec)
            val specSize = MeasureSpec.getSize(measureSpec)
            return when (mode) {
                MeasureSpec.AT_MOST -> minOf(size, specSize)
                MeasureSpec.EXACTLY -> specSize
                else -> size
            }
        }

        fun getDefaultSize(size: Int, measureSpec: Int): Int = when (MeasureSpec.getMode(measureSpec)) {
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY -> MeasureSpec.getSize(measureSpec)
            else -> size
        }

        /** The View the app's Compose screens are given where Android hands its window's (LocalView). */
        val Shared = View(null)

        /** A drawable by a resource id of :app's R, through the names the shell installs. */
        internal var drawableForResource: (Int) -> Drawable? = { null }
    }

    /** Android's measure specs: a size and a mode packed in an int. */
    object MeasureSpec {
        private const val MODE_SHIFT = 30
        private const val MODE_MASK = 0x3 shl MODE_SHIFT
        const val UNSPECIFIED = 0
        const val EXACTLY = 1 shl MODE_SHIFT
        const val AT_MOST = 2 shl MODE_SHIFT

        fun makeMeasureSpec(size: Int, mode: Int): Int = (size and MODE_MASK.inv()) or (mode and MODE_MASK)

        fun getMode(measureSpec: Int): Int = measureSpec and MODE_MASK

        fun getSize(measureSpec: Int): Int = measureSpec and MODE_MASK.inv()
    }
}

/** Android's gravity flags and their resolution in a box. */
object Gravity {
    const val NO_GRAVITY = 0
    const val AXIS_SPECIFIED = 0x0001
    const val AXIS_PULL_BEFORE = 0x0002
    const val AXIS_PULL_AFTER = 0x0004
    const val AXIS_X_SHIFT = 0
    const val AXIS_Y_SHIFT = 4
    const val TOP = (AXIS_PULL_BEFORE or AXIS_SPECIFIED) shl AXIS_Y_SHIFT
    const val BOTTOM = (AXIS_PULL_AFTER or AXIS_SPECIFIED) shl AXIS_Y_SHIFT
    const val LEFT = (AXIS_PULL_BEFORE or AXIS_SPECIFIED) shl AXIS_X_SHIFT
    const val RIGHT = (AXIS_PULL_AFTER or AXIS_SPECIFIED) shl AXIS_X_SHIFT
    const val CENTER_VERTICAL = AXIS_SPECIFIED shl AXIS_Y_SHIFT
    const val CENTER_HORIZONTAL = AXIS_SPECIFIED shl AXIS_X_SHIFT
    const val CENTER = CENTER_VERTICAL or CENTER_HORIZONTAL
    const val FILL_VERTICAL = TOP or BOTTOM
    const val FILL_HORIZONTAL = LEFT or RIGHT
    const val FILL = FILL_VERTICAL or FILL_HORIZONTAL
    const val RELATIVE_LAYOUT_DIRECTION = 0x00800000
    const val START = RELATIVE_LAYOUT_DIRECTION or LEFT
    const val END = RELATIVE_LAYOUT_DIRECTION or RIGHT
    const val HORIZONTAL_GRAVITY_MASK = (AXIS_SPECIFIED or AXIS_PULL_BEFORE or AXIS_PULL_AFTER) shl AXIS_X_SHIFT
    const val VERTICAL_GRAVITY_MASK = (AXIS_SPECIFIED or AXIS_PULL_BEFORE or AXIS_PULL_AFTER) shl AXIS_Y_SHIFT
    const val RELATIVE_HORIZONTAL_GRAVITY_MASK = START or END

    /** START and END as LEFT and RIGHT for the direction. */
    fun getAbsoluteGravity(gravity: Int, layoutDirection: Int): Int {
        if (gravity and RELATIVE_LAYOUT_DIRECTION == 0) return gravity
        var result = gravity and RELATIVE_LAYOUT_DIRECTION.inv()
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        if (gravity and START == START) {
            result = result and START.inv()
            result = result or if (rtl) RIGHT else LEFT
        } else if (gravity and END == END) {
            result = result and END.inv()
            result = result or if (rtl) LEFT else RIGHT
        }
        return result
    }

    /** Where a [w] by [h] box goes inside [container], as Android's Gravity.apply. */
    fun apply(gravity: Int, w: Int, h: Int, container: Rect, outRect: Rect, layoutDirection: Int) {
        val absolute = getAbsoluteGravity(gravity, layoutDirection)
        val horizontal = absolute and HORIZONTAL_GRAVITY_MASK
        val vertical = absolute and VERTICAL_GRAVITY_MASK
        outRect.left = when {
            horizontal and (FILL_HORIZONTAL) == FILL_HORIZONTAL -> container.left
            horizontal == RIGHT -> container.right - w
            horizontal == CENTER_HORIZONTAL -> container.left + (container.width() - w) / 2
            else -> container.left
        }
        outRect.right = if (horizontal and FILL_HORIZONTAL == FILL_HORIZONTAL) container.right else outRect.left + w
        outRect.top = when {
            vertical and FILL_VERTICAL == FILL_VERTICAL -> container.top
            vertical == BOTTOM -> container.bottom - h
            vertical == CENTER_VERTICAL -> container.top + (container.height() - h) / 2
            else -> container.top
        }
        outRect.bottom = if (vertical and FILL_VERTICAL == FILL_VERTICAL) container.bottom else outRect.top + h
    }

    /** "center_vertical|start" as flags. */
    fun parse(value: String?): Int = value?.split('|')?.sumOf { name ->
        when (name.trim()) {
            "top" -> TOP
            "bottom" -> BOTTOM
            "left" -> LEFT
            "right" -> RIGHT
            "start" -> START
            "end" -> END
            "center" -> CENTER
            "center_vertical" -> CENTER_VERTICAL
            "center_horizontal" -> CENTER_HORIZONTAL
            "fill" -> FILL
            "fill_vertical" -> FILL_VERTICAL
            "fill_horizontal" -> FILL_HORIZONTAL
            else -> 0
        }
    } ?: NO_GRAVITY
}

/** The Handler every View posts with. */
private val ViewHandler = android.os.Handler()

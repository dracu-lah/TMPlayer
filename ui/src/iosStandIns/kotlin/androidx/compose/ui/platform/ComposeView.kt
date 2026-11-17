package androidx.compose.ui.platform

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Constraints

/**
 * Compose inside a tree of Android Views, Android's ComposeView: a View the layouts measure and
 * place like any other, whose content is a composition. On iOS the tree is drawn by one host
 * (com.tmplayer.ios.AndroidViewHost), which composes this View's content over its drawing at the
 * View's place and size, and gives it the touches there, as Android does.
 *
 * As on Android, the View's own size wraps its content: the host measures the content with the
 * room the View's parent gave it and tells the View, which lays out again to that size. The View's
 * padding is kept around the content, as Android's ComposeView lays its content out inside it.
 */
open class ComposeView(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr) {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    /** The content, read by the host's composition. */
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    private var contentWidth = 0
    private var contentHeight = 0
    /**
     * The room the View's parent last gave it, as Compose state: the host measures the content
     * before the Views are measured in a frame, so a View shown just now (a loader, a panel opened)
     * is first measured with no room known; reading this state there measures it again as soon as
     * the View has its size, as Android's ComposeView always lays its content out at its own.
     */
    private var specs by mutableStateOf(UNSPECIFIED_SPEC to UNSPECIFIED_SPEC)

    /** Whether the View's parent has measured it yet, as Compose state for the host. */
    var measured: Boolean by mutableStateOf(false)
        private set

    fun setContent(content: @Composable () -> Unit) {
        this.content = content
        requestLayout()
    }

    /** When the composition is let go: on iOS it goes with the View leaving the tree, whatever is set. */
    fun setViewCompositionStrategy(strategy: ViewCompositionStrategy) = Unit

    fun disposeComposition() {
        content = null
    }

    val hasComposition: Boolean get() = content != null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (specs.first != widthMeasureSpec || specs.second != heightMeasureSpec) specs = widthMeasureSpec to heightMeasureSpec
        if (!measured) measured = true
        setMeasuredDimension(
            resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    /** The room the View's parent gave it less its padding, as Compose constraints for the content. */
    fun contentConstraints(): Constraints {
        fun range(spec: Int, padding: Int): Pair<Int, Int> {
            val size = maxOf(0, MeasureSpec.getSize(spec) - padding)
            return when (MeasureSpec.getMode(spec)) {
                MeasureSpec.EXACTLY -> size to size
                MeasureSpec.AT_MOST -> 0 to size
                else -> 0 to Constraints.Infinity
            }
        }
        val (widthSpec, heightSpec) = specs
        val (minWidth, maxWidth) = range(widthSpec, paddingLeft + paddingRight)
        val (minHeight, maxHeight) = range(heightSpec, paddingTop + paddingBottom)
        return Constraints(minWidth, maxWidth, minHeight, maxHeight)
    }

    /** The content's measured size, from the host; a change lays the tree out again. */
    fun contentMeasured(width: Int, height: Int) {
        if (width == contentWidth && height == contentHeight) return
        contentWidth = width
        contentHeight = height
        post { requestLayout() }
    }

    /** Shown with every ancestor shown, and how opaque, all their alphas together. */
    fun effectiveAlpha(): Float {
        var alpha = 1f
        var view: View? = this
        while (view != null) {
            if (view.visibility != VISIBLE) return 0f
            alpha *= view.alpha
            view = view.parent as? View
        }
        return alpha
    }

    // The host it joined, kept: by the time it leaves, its parents may be gone already.
    private var shownBy: Host? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shownBy = rootView.host?.also { it.overlayAttached(this) }
    }

    override fun onDetachedFromWindow() {
        shownBy?.overlayDetached(this)
        shownBy = null
        super.onDetachedFromWindow()
    }
}

/** When a ComposeView lets its composition go, Android's choices; on iOS, when it leaves the tree. */
interface ViewCompositionStrategy {
    object DisposeOnDetachedFromWindow : ViewCompositionStrategy

    object DisposeOnDetachedFromWindowOrReleasedFromPool : ViewCompositionStrategy

    object DisposeOnViewTreeLifecycleDestroyed : ViewCompositionStrategy

    class DisposeOnLifecycleDestroyed(@Suppress("UNUSED_PARAMETER") lifecycle: Any?) : ViewCompositionStrategy

    companion object {
        val Default: ViewCompositionStrategy = DisposeOnDetachedFromWindowOrReleasedFromPool
    }
}

private val UNSPECIFIED_SPEC = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)

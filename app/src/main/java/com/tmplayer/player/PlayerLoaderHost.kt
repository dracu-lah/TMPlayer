package com.tmplayer.player

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tmplayer.ui.player.PlayerLoader
import com.tmplayer.ui.player.rememberLoaderContent
import com.tmplayer.ui.theme.TmMaterialTheme

/**
 * The pre-roll loader over the player, as a [ComposeView] laid over the status sheet: the shared
 * [PlayerLoader] from `:ui`, the same one the desktop draws.
 *
 * It covers only the opening wait. A failure, "Still watching?", the mobile data question and
 * the end card keep the status sheet underneath, which this fades off to reveal. The activity
 * feeds it through the fields below; nothing here decides anything about playback.
 *
 * Always in the dark scheme: it is the player's chrome, and a light sheet flashing up between a
 * dark grid and a dark film is the one thing a pre-roll must not do.
 */
class PlayerLoaderHost(
    private val root: FrameLayout,
    above: View,
    private val tv: Boolean,
    private val fileName: String,
    private val caption: String?,
    private val fallbackTitle: String,
    private val durationSec: Int,
) {
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("")
    var note by mutableStateOf<String?>(null)

    /** The video's own frame, set only when it is a real frame and not a channel's logo. */
    var thumbnail by mutableStateOf<ImageBitmap?>(null)

    /** While false nothing is composed, so the decoded pictures can go once playback starts. */
    private var composing by mutableStateOf(false)
    private var insets by mutableStateOf(PaddingValues(0.dp))

    /** When the loader last came up, for how long the wait has been. */
    var shownAt = 0L
        private set

    val shown: Boolean get() = view.visibility == View.VISIBLE && composing

    private val view = ComposeView(root.context).apply {
        visibility = View.GONE
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        // Not named Content: inside apply that would resolve to ComposeView.Content.
        setContent { LoaderScreen() }
    }

    init {
        val at = root.indexOfChild(above).takeIf { it >= 0 }?.plus(1) ?: root.childCount
        root.addView(view, at, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @androidx.compose.runtime.Composable
    private fun LoaderScreen() {
        if (!composing) return
        TmMaterialTheme(dark = true) {
            val widthPx = root.resources.displayMetrics.widthPixels
            val content = rememberLoaderContent(fileName, caption, fallbackTitle, durationSec, thumbnail, widthPx)
            PlayerLoader(
                content = content,
                progress = progress,
                status = status,
                note = note,
                tv = tv,
                motion = ValueAnimator.areAnimatorsEnabled(),
                insets = insets,
            )
        }
    }

    fun show(nowMs: Long) {
        view.animate().cancel()
        view.alpha = 1f
        if (view.visibility == View.VISIBLE && composing) return
        insets = readInsets()
        shownAt = nowMs
        composing = true
        view.visibility = View.VISIBLE
    }

    /** Fades off, the first frame or the status sheet showing through, then lets the pictures go. */
    fun hide() {
        if (view.visibility != View.VISIBLE) return
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            view.visibility = View.GONE
            composing = false
            note = null
        }.start()
    }

    /** The bars and the cutout, as padding for the words; the picture runs under them. */
    private fun readInsets(): PaddingValues {
        val all = ViewCompat.getRootWindowInsets(root)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ?: return PaddingValues(0.dp)
        val density = root.resources.displayMetrics.density
        fun dp(px: Int) = (px / density).dp
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return PaddingValues(
            start = dp(if (rtl) all.right else all.left),
            top = dp(all.top),
            end = dp(if (rtl) all.left else all.right),
            bottom = dp(all.bottom),
        )
    }

    private companion object {
        const val FADE_MS = 250L
    }
}

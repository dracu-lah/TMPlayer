package com.tmplayer.player

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tmplayer.R
import com.tmplayer.i18n.L
import com.tmplayer.online.SkipKind
import com.tmplayer.online.SkipSegment
import com.tmplayer.online.SkipSource

/**
 * The "Skip intro" pill: raised while the playhead is inside a stretch somebody has timed (a
 * recap, an intro or opening, credits, an ending, a preview), and gone once it leaves.
 *
 * It sits where the next-up card sits, bottom right and clear of the transport row, so it stays
 * where the eye left it whether the row is up or down; the two never show together, since a
 * stretch that runs to the end of an episode with a successor is the card's to cover.
 *
 * A phone taps it. A television never focuses it: focus moving to something that appeared on its
 * own would turn the next arrow press into a walk across the screen rather than the jump the
 * viewer meant. The activity hands it OK instead while the row is down, which the second line,
 * "OK to skip", says out loud.
 *
 * Built in code and added to [root] under [below], the overlay container, the same way as
 * [NextUpCard], so the gesture HUD and the phone's flashes still draw over it. The activity owns
 * when it shows and what pressing does.
 */
class SkipPill(
    root: FrameLayout,
    below: View,
    private val tv: Boolean,
    onSkip: () -> Unit,
    /** Cancel on the countdown of a stretch set to skip by itself; a television cancels with Back instead. */
    onCancel: () -> Unit = {},
) {
    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()

    private val label = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_primary))
        textSize = if (tv) 18f else 15f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** The remote's half of the offer; a phone's pill is its own button and needs no instruction. */
    private val hint = TextView(context).apply {
        setTextColor(context.getColor(R.color.accent))
        textSize = 13f
        maxLines = 1
        text = L.playerSkipOkHint
        visibility = if (tv) View.VISIBLE else View.GONE
    }

    /** The phone's Cancel under the words while a countdown runs; its own target, so a tap on it does not skip. */
    private val cancel = TextView(context).apply {
        setTextColor(context.getColor(R.color.accent))
        textSize = 14f
        maxLines = 1
        gravity = Gravity.CENTER
        text = L.commonCancel
        setPadding(px(16), px(6), px(16), px(2))
        visibility = View.GONE
        isClickable = true
        setOnClickListener { onCancel() }
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = context.getDrawable(R.drawable.bg_player_chip)
        setPadding(px(22), px(if (tv) 12 else 11), px(22), px(if (tv) 10 else 11))
        visibility = View.GONE
        isFocusable = false
        if (tv) {
            isClickable = false
        } else {
            isClickable = true
            setOnClickListener { onSkip() }
        }
        addView(label)
        addView(hint)
        addView(cancel)
    }

    init {
        root.addView(
            view,
            root.indexOfChild(below),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM,
            ).apply {
                rightMargin = NextUpCard.marginPx(root)
                bottomMargin = NextUpCard.bottomPx(root)
            },
        )
    }

    val isShown: Boolean get() = view.visibility == View.VISIBLE

    /**
     * Writes the words for [segment] and shows the pill: "Skip intro", or while a stretch set to
     * skip by itself counts down ([secondsLeft]) "Skipping in 3" with Cancel (Back on a television).
     */
    fun show(segment: SkipSegment, secondsLeft: Int? = null) {
        isCounting = secondsLeft != null
        if (secondsLeft == null) {
            label.text = words(segment)
            hint.text = L.playerSkipOkHint
            hint.visibility = if (tv) View.VISIBLE else View.GONE
            cancel.visibility = View.GONE
        } else {
            label.text = L.playerSkipIn(secondsLeft)
            hint.text = L.playerSkipBackCancel
            hint.visibility = if (tv) View.VISIBLE else View.GONE
            cancel.visibility = if (tv) View.GONE else View.VISIBLE
        }
        view.visibility = View.VISIBLE
    }

    /** Whether a countdown is what the pill shows now. */
    var isCounting: Boolean = false
        private set

    /** The pill's words; an intro the viewer marked says so. */
    private fun words(segment: SkipSegment): String =
        if (segment.source == SkipSource.Mark || segment.source == SkipSource.Guess) {
            L.playerSkipIntroMark
        } else {
            when (segment.kind) {
                SkipKind.Recap -> L.playerSkipRecap
                SkipKind.Intro -> L.playerSkipIntro
                SkipKind.Opening -> L.playerSkipOpening
                SkipKind.Credits -> L.playerSkipCredits
                SkipKind.Ending -> L.playerSkipEnding
                SkipKind.Preview -> L.playerSkipPreview
            }
        }

    fun hide() {
        isCounting = false
        view.visibility = View.GONE
    }
}

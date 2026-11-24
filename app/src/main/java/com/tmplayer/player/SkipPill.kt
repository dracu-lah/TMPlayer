package com.tmplayer.player

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import com.tmplayer.R
import com.tmplayer.i18n.L
import com.tmplayer.online.SkipController
import com.tmplayer.online.SkipKind
import com.tmplayer.online.SkipOffer
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
 * A phone taps it. A television never takes focus by itself: focus moving to something that
 * appeared on its own would turn the next arrow press into a walk across the screen rather than
 * the jump the viewer meant. The activity hands it OK instead while the row is down, which the
 * second line, "OK to skip", says out loud. With the row up the pill joins the focus chain (WP-19,
 * [reachable]): Up from the bar lands on it, OK skips, and its second line reads "Up, then OK to
 * skip" until the remote is on it.
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
        if (tv) {
            // Takes the pill's focused state, so its colour list turns dark on the white focused pill.
            isDuplicateParentStateEnabled = true
            setTextColor(context.getColorStateList(R.color.player_control_icon))
        } else {
            setTextColor(context.getColor(R.color.text_primary))
        }
        textSize = if (tv) 18f else 15f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** The remote's half of the offer; a phone's pill is its own button and needs no instruction. */
    private val hint = TextView(context).apply {
        if (tv) {
            isDuplicateParentStateEnabled = true
            setTextColor(context.getColorStateList(R.color.skip_pill_hint))
        } else {
            setTextColor(context.getColor(R.color.accent))
        }
        // 14 sp: the television's type floor (this hint only shows there).
        textSize = 14f
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
        background = context.getDrawable(if (tv) R.drawable.bg_player_chip_focus else R.drawable.bg_player_chip)
        setPadding(px(22), px(if (tv) 12 else 11), px(22), px(if (tv) 10 else 11))
        visibility = View.GONE
        id = View.generateViewId()
        isFocusable = false
        if (tv) {
            // Focusable only while the row is up ([reachable]); the remote cannot leave it sideways
            // or upward, only Down to the bar.
            isClickable = false
            nextFocusUpId = id
            nextFocusLeftId = id
            nextFocusRightId = id
            nextFocusDownId = R.id.controls_timebar
            setOnClickListener { onSkip() }
            onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                renderHint()
                if (focused) onFocused?.invoke()
            }
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
                SideInsets.atEnd(this, NextUpCard.marginPx(root))
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
            cancel.visibility = View.GONE
        } else {
            label.text = L.playerSkipIn(secondsLeft)
            cancel.visibility = if (tv) View.GONE else View.VISIBLE
        }
        renderHint()
        view.visibility = View.VISIBLE
        if (tv) {
            view.isFocusable = rowUp
            view.isClickable = rowUp
        }
    }

    /** True while the transport row is up on a television: the pill can then be walked onto. */
    private var rowUp = false

    /**
     * The second line: "OK to skip" where OK skips (row down, or the remote on the pill), "Up, then
     * OK to skip" with the row up and the remote still elsewhere, "Back to cancel" while a
     * countdown runs over the bare picture.
     */
    private fun renderHint() {
        hint.visibility = if (tv) View.VISIBLE else View.GONE
        hint.text = when {
            isCounting && !rowUp -> L.playerSkipBackCancel
            rowUp && !view.hasFocus() -> L.playerSkipHintRowUp
            else -> L.playerSkipOkHint
        }
    }

    /**
     * Whether the remote may walk onto the pill ([up]: the row is up on a television). The pill is
     * focusable only while it is shown with the row up; turning that off while the remote is on it
     * lets go of the focus. True when it did, so the caller puts the remote back on the row.
     */
    fun reachable(up: Boolean): Boolean {
        if (!tv) return false
        val had = view.hasFocus()
        rowUp = up
        val focusable = up && isShown
        view.isFocusable = focusable
        view.isClickable = focusable
        if (!focusable && had) view.clearFocus()
        renderHint()
        return !focusable && had
    }

    /** The view's id, for the bar's nextFocusUp. */
    val viewId: Int get() = view.id

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
        val had = view.hasFocus()
        if (tv) {
            view.isFocusable = false
            view.isClickable = false
        }
        view.visibility = View.GONE
        if (had) onLostFocus?.invoke()
    }

    /** Called when the remote lands on the pill, so the activity keeps the row up while it is there. */
    var onFocused: (() -> Unit)? = null

    /** Called when the pill goes while the remote stands on it, so the activity can put it back on the row. */
    var onLostFocus: (() -> Unit)? = null

    /**
     * One tick of the pill for [engine]'s place: asks [skip] what to offer and shows it, or hides
     * the pill. [engine] is whichever player is on screen, ExoPlayer's or VLC's: the loop once read
     * ExoPlayer alone, so a video on VLC never had a Skip button. [blocked] is what keeps the pill
     * away (the lock, picture in picture, the failure sheet, the next-up card); [holdCount] stops a
     * countdown while a television's remote is on the row and could not cancel it.
     *
     * Returns the offer, so the caller knows what OK skips; a [SkipOffer.Fire] is the caller's to
     * carry out (it seeks), and the pill is left as it was for it.
     */
    fun follow(
        skip: SkipController,
        engine: Player,
        blocked: Boolean,
        holdCount: Boolean,
        nowMs: Long,
        skipped: Set<Long>,
        cardCoversTheEnd: Boolean,
    ): SkipOffer? {
        val duration = engine.duration
        val offer = if (duration <= 0 || blocked) {
            null
        } else {
            skip.offer(engine.currentPosition, duration, engine.isPlaying && !holdCount, nowMs, skipped, cardCoversTheEnd)
        }
        when (offer) {
            null -> hide()
            is SkipOffer.Ask -> show(offer.segment)
            is SkipOffer.Counting -> show(offer.segment, offer.secondsLeft)
            is SkipOffer.Fire -> Unit
        }
        return offer
    }
}

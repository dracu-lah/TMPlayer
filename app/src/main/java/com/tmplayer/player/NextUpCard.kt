package com.tmplayer.player

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tmplayer.R
import com.tmplayer.i18n.L

/**
 * The bottom-right card that offers the next episode before this one ends: "Next: S01E05,
 * starting in 12 s", with Hide (phone only) and Play now.
 *
 * On a television it comes up with D-pad focus on Play now, so OK starts the episode and Back puts
 * the card away, which is why it has no Hide button there; the activity routes the remote's keys to
 * it while it is up and the row is down. The button takes the row's own focus treatment, a solid
 * white seat with dark text.
 *
 * Built in code and added to [root] under [below], the overlay container, so the gesture HUD and
 * the phone's flashes still draw over it. The activity owns when it shows and what pressing does.
 */
class NextUpCard(
    root: FrameLayout,
    below: View,
    private val tv: Boolean,
    onHide: () -> Unit,
    onPlay: () -> Unit,
) {
    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()

    private val text = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_primary))
        textSize = if (tv) 18f else 14f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = px(if (tv) 340 else 260)
    }

    /** Play now, which a television focuses as the card comes up. */
    val play: Button = button(L.playerPlayNow, onPlay)

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.getDrawable(R.drawable.bg_player_chip)
        setPadding(px(18), px(14), px(18), px(12))
        visibility = View.GONE
        isClickable = true
        addView(text)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                // On a television Back puts the card away, which is the shorter path than steering
                // across to a second button with the D-pad.
                if (!tv) addView(button(L.commonHide, onHide))
                addView(play)
            },
        )
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
                rightMargin = MARGIN_PX
                bottomMargin = BOTTOM_PX
            },
        )
    }

    val isShown: Boolean get() = view.visibility == View.VISIBLE

    /** Writes [label] and the seconds left, and shows the card. True when it has just come up. */
    fun show(label: String, secondsLeft: Long): Boolean {
        text.text = L.playerNextUpCard(label, secondsLeft)
        if (isShown) return false
        view.visibility = View.VISIBLE
        return true
    }

    /** Hides the card. True when it held focus, which the caller then has to put somewhere. */
    fun hide(): Boolean {
        val hadFocus = view.hasFocus()
        view.visibility = View.GONE
        return hadFocus
    }

    private fun button(label: String, action: () -> Unit) =
        Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            setTextColor(context.getColor(R.color.accent))
            isAllCaps = false
            setOnClickListener { action() }
            if (tv) {
                textSize = 16f
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_focused),
                        GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = px(20).toFloat()
                        },
                    )
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
                setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(Color.BLACK, context.getColor(R.color.accent)),
                    ),
                )
                setPadding(px(20), 0, px(20), 0)
            }
        }

    companion object {
        /** Clear of the screen edge, before the system's own insets are added. */
        const val MARGIN_PX = 48

        /** Clear of the transport row's cluster. */
        const val BOTTOM_PX = 220
    }
}

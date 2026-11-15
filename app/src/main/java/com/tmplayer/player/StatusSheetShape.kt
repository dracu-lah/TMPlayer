package com.tmplayer.player

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import com.tmplayer.R
import com.tmplayer.data.FormFactor

/**
 * The loading and failure sheet in one of three shapes, chosen by the width it is given.
 *
 * A phone held sideways has around 390dp of height, and the sheet is a poster, a title, a message
 * and up to three buttons: stacked, that overflows and the buttons fall off the bottom of the
 * display. Side by side it is a picture on the left and everything readable on the right, with the
 * buttons in a row under the words. The same phone held upright is the opposite case: beside a
 * 176dp poster the words had about 145dp, the title ran out mid word and a two word button broke
 * over two lines, so there it stacks again, with a smaller poster than a television's and the
 * buttons in a centred row that wraps. A television goes side by side too, at its own size: the
 * large poster and the name on the left, and on the right the message over the buttons in one
 * column, which is the direction a remote moves in. Stacked, as it was until 2026-10-23, a failure
 * with five ways out ran the last of them off the bottom of a 540dp screen while two thirds of
 * its width stood empty.
 *
 * Decided at measure time by [StatusSheet] rather than once at startup: the player chooses its own
 * orientation and is not recreated when it changes, so the screen it was built against is regularly
 * not the screen it ends up on. Kept out of the activity so the render tests draw the sheet in the
 * shape the player gives it.
 */
object StatusSheetShape {

    enum class Shape {
        /** A television: the large poster beside the words, the buttons one under another. */
        Tv,

        /** A narrow handset: a smaller poster over the words, the buttons in a centred row. */
        Stacked,

        /** A wide handset: the poster beside the words, the buttons in a row under them. */
        SideBySide,
    }

    /**
     * The shape for a sheet [widthDp] wide.
     *
     * The width is the one the sheet is actually laid out in, which is the honest answer on almost
     * every phone. It is not on all of them: one test phone presents the player as a portrait
     * window rotated onto a landscape panel, so the views, the display metrics, the rotation and
     * Configuration.orientation all say a 393dp portrait while the sheet is plainly drawn sideways.
     * [askedSideways] covers that phone: when the player itself has asked for landscape, the screen
     * is sideways whatever the window claims, so the sheet goes side by side regardless of width.
     */
    fun choose(tv: Boolean, widthDp: Float, askedSideways: Boolean): Shape = when {
        tv -> Shape.Tv
        askedSideways || widthDp >= SIDE_BY_SIDE_MIN_WIDTH_DP -> Shape.SideBySide
        else -> Shape.Stacked
    }

    /** [overlay] is `status_overlay` from activity_player.xml, or the sheet inside it. */
    fun apply(overlay: View, shape: Shape) {
        val sheet = overlay.findViewById<LinearLayout>(R.id.status_sheet) ?: return
        val column = overlay.findViewById<LinearLayout>(R.id.status_column) ?: return
        val side = overlay.findViewById<LinearLayout>(R.id.status_side) ?: return
        val sideBySide = shape == Shape.SideBySide || shape == Shape.Tv
        sheet.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        val resources = overlay.resources
        val density = resources.displayMetrics.density
        fun px(dp: Float) = (dp * density).toInt()
        val poster = overlay.findViewById<ImageView>(R.id.status_poster)
        val name = overlay.findViewById<TextView>(R.id.status_name)
        val text = overlay.findViewById<TextView>(R.id.status_text)
        val detail = overlay.findViewById<TextView>(R.id.status_detail)
        val format = overlay.findViewById<TextView>(R.id.status_format)

        // The picture side takes a narrow, fixed slice: it is there to be recognised, not read, so
        // every point it does not use goes to the message and the buttons.
        side.updateLayoutParams<LinearLayout.LayoutParams> {
            marginEnd = when (shape) {
                Shape.Tv -> px(TV_POSTER_GAP_DP)
                Shape.SideBySide -> px(POSTER_GAP_DP)
                Shape.Stacked -> 0
            }
        }
        poster?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = when (shape) {
                Shape.SideBySide -> px(SIDE_POSTER_WIDTH_DP)
                Shape.Stacked -> px(STACKED_POSTER_WIDTH_DP)
                Shape.Tv -> resources.getDimensionPixelSize(R.dimen.player_poster_width)
            }
            height = when (shape) {
                Shape.SideBySide -> px(SIDE_POSTER_HEIGHT_DP)
                Shape.Stacked -> px(STACKED_POSTER_HEIGHT_DP)
                Shape.Tv -> resources.getDimensionPixelSize(R.dimen.player_poster_height)
            }
            // The gap between the two moves with them: what was space underneath the poster is now
            // space to the right of the pair.
            bottomMargin = when (shape) {
                Shape.SideBySide, Shape.Tv -> px(NAME_GAP_DP)
                Shape.Stacked -> px(STACKED_POSTER_GAP_DP)
            }
        }
        // The name wraps to the width of the picture it belongs to rather than running the width of
        // the screen, which is what keeps the two of them reading as one thing on the left.
        name?.apply {
            maxWidth = when (shape) {
                Shape.SideBySide -> px(SIDE_POSTER_WIDTH_DP)
                Shape.Tv -> resources.getDimensionPixelSize(R.dimen.player_poster_width)
                Shape.Stacked -> px(NAME_STACK_MAX_WIDTH_DP)
            }
            maxLines = if (sideBySide) SIDE_NAME_LINES else STACK_NAME_LINES
        }
        // Weighted rather than wrapped, so a long message takes the width that is left over
        // instead of pushing the picture off the side of the screen. A television's column has a
        // fixed width instead, so the pair sits centred on the screen as one block and does not
        // jump sideways as the loading words change.
        column.updateLayoutParams<LinearLayout.LayoutParams> {
            width = when (shape) {
                Shape.SideBySide -> 0
                Shape.Tv -> px(TV_COLUMN_WIDTH_DP)
                Shape.Stacked -> LinearLayout.LayoutParams.WRAP_CONTENT
            }
            weight = if (shape == Shape.SideBySide) 1f else 0f
        }
        // The bars run the width of the column they are in rather than a width chosen for a screen
        // with nothing beside them.
        listOfNotNull<View>(overlay.findViewById(R.id.status_progress), overlay.findViewById(R.id.status_spinner)).forEach { bar ->
            bar.updateLayoutParams<LinearLayout.LayoutParams> {
                width = if (sideBySide) {
                    LinearLayout.LayoutParams.MATCH_PARENT
                } else {
                    resources.getDimensionPixelSize(R.dimen.player_progress_width)
                }
            }
        }
        // Both columns read from the same left edge, and the words run into the width they have
        // been given. Centred is right for a stacked sheet, where the column is the whole screen,
        // and wrong beside a picture.
        val flow = if (sideBySide) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        sheet.gravity = if (shape == Shape.Tv) Gravity.CENTER else flow
        column.gravity = flow
        side.gravity = flow
        listOfNotNull<TextView>(name, overlay.findViewById(R.id.status_title), overlay.findViewById(R.id.status_meta), text, detail, format).forEach {
            it.gravity = if (sideBySide) Gravity.START else Gravity.CENTER
        }
        // On a handset the buttons go in a row, each as wide as its words, so all of them are on
        // the screen at once instead of a column of wide pills running off the bottom: from the
        // start edge beside a picture, centred under words that are centred themselves.
        val actions = overlay.findViewById<SheetActions>(R.id.status_actions)
        actions?.inRow = shape == Shape.SideBySide || shape == Shape.Stacked
        actions?.centred = shape == Shape.Stacked
        if (actions != null) {
            for (i in 0 until actions.childCount) {
                val button = actions.getChildAt(i) as? Button ?: continue
                button.minWidth = if (shape == Shape.Tv) px(TV_BUTTON_MIN_WIDTH_DP) else px(ROW_BUTTON_MIN_WIDTH_DP)
                button.minimumWidth = button.minWidth
            }
        }
        // The message and its hint read as one paragraph beside the poster, not two blocks.
        text?.updateLayoutParams<LinearLayout.LayoutParams> {
            bottomMargin = if (shape == Shape.SideBySide) px(ROW_TEXT_GAP_DP) else px(STACK_TEXT_GAP_DP)
        }
        // The failed format hangs under the sentence it belongs to: the gap below the sentence is
        // taken back for it, and the gap to the buttons moves under it.
        format?.updateLayoutParams<LinearLayout.LayoutParams> {
            val gap = if (shape == Shape.SideBySide) px(ROW_TEXT_GAP_DP) else px(STACK_TEXT_GAP_DP)
            topMargin = px(FORMAT_GAP_DP) - gap
            bottomMargin = gap
        }
        detail?.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = if (shape == Shape.SideBySide) px(ROW_TEXT_GAP_DP) else px(STACK_DETAIL_GAP_DP)
        }
    }

    /**
     * The narrowest sheet that goes side by side. A phone held sideways is 640dp and up, and held
     * upright 360 to 430: at this width the words beside a 176dp poster still have about 300dp,
     * enough for the title on one line and the buttons in a row.
     */
    const val SIDE_BY_SIDE_MIN_WIDTH_DP = 560f

    /** The gap between the poster and the words, beside them and above them. */
    private const val POSTER_GAP_DP = 24f
    private const val TV_POSTER_GAP_DP = 56f

    /**
     * A television's words and buttons: wide enough for the longest message in three or four
     * lines and a button label on one, narrow enough that the poster beside it and the pair
     * centred still leave the screen's edges clear on a 960dp television.
     */
    private const val TV_COLUMN_WIDTH_DP = 440f

    /** The picture's slice of a sideways sheet, and the name that sits under it. */
    private const val SIDE_POSTER_WIDTH_DP = 176f
    private const val SIDE_POSTER_HEIGHT_DP = 99f
    private const val NAME_GAP_DP = 10f
    private const val SIDE_NAME_LINES = 3
    private const val STACK_NAME_LINES = 2
    private const val NAME_STACK_MAX_WIDTH_DP = 720f

    /** An upright phone's poster: smaller than a television's, so the words and buttons fit under it. */
    private const val STACKED_POSTER_WIDTH_DP = 160f
    private const val STACKED_POSTER_HEIGHT_DP = 90f
    private const val STACKED_POSTER_GAP_DP = 16f

    /** The sheet's buttons: one width down a television's column, as wide as its words in a row. */
    private const val TV_BUTTON_MIN_WIDTH_DP = 300f
    private const val ROW_BUTTON_MIN_WIDTH_DP = 112f

    /** Between the message, its hint and the buttons, stacked and beside a picture. */
    private const val STACK_TEXT_GAP_DP = 20f
    private const val STACK_DETAIL_GAP_DP = 14f
    private const val ROW_TEXT_GAP_DP = 8f

    /** The failed format's line sits this far under the sentence. */
    private const val FORMAT_GAP_DP = 6f
}

/**
 * `status_sheet`: the sheet that picks its own [StatusSheetShape.Shape] from the width it is
 * measured at, so the decision follows the space the sheet really has rather than what the
 * configuration says, and is made again whenever that space changes.
 */
class StatusSheet @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val tv = FormFactor.isTv(context)

    /** The shape last applied; null until the first measure. */
    var shape: StatusSheetShape.Shape? = null
        private set

    /** Set by the player while it has asked for landscape ([StatusSheetShape.choose]). */
    var askedSideways: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthDp = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.configuration.screenWidthDp.toFloat()
        } else {
            MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density
        }
        val wanted = StatusSheetShape.choose(tv, widthDp, askedSideways)
        // Only on a change: the children's parameters are rewritten here, inside this measure, so
        // the pass that follows reads the new shape and the next one has nothing to redo.
        if (wanted != shape) {
            shape = wanted
            StatusSheetShape.apply(this, wanted)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

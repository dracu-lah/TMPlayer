package android.view

import android.content.res.ResourceFiles
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The View toolkit's stand-ins against Android's documented behaviour, for what the player's own
 * Views lean on: a press on a clickable group is a click, and a button made with a theme style
 * attribute has that style's size and padding.
 */
class ToolkitTest {
    private fun down(x: Float, y: Float) = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)

    private fun up(x: Float, y: Float) = MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, x, y, 0)

    private fun FrameLayout.laidOut(size: Int = 1000) = apply {
        val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        measure(spec, spec)
        layout(0, 0, size, size)
    }

    /**
     * The player's "Back to" chip: a clickable LinearLayout around a plain TextView, with a touch
     * listener that animates the press and returns false, moved up by a translation, under an
     * empty full-size FrameLayout (the overlay container) drawn over it.
     */
    @Test
    fun aPressOnAClickableGroupUnderAnEmptyOverlayIsAClick() {
        val root = FrameLayout(null)
        var clicks = 0
        var touches = 0
        val chip = LinearLayout(null).apply {
            isClickable = true
            setOnClickListener { clicks++ }
            setOnTouchListener { _, _ ->
                touches++
                false
            }
            addView(TextView(null).apply { text = "Back to 0:08" })
        }
        root.addView(chip, FrameLayout.LayoutParams(300, 100, Gravity.START or Gravity.BOTTOM).apply { bottomMargin = 200 })
        root.addView(FrameLayout(null), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.laidOut()
        chip.translationY = -150f

        // Where the chip is drawn: its laid out place (700 to 800) moved up by 150.
        assertTrue(root.dispatchTouchEvent(down(50f, 600f)))
        root.dispatchTouchEvent(up(50f, 600f))

        assertEquals(2, touches)
        assertEquals(1, clicks)
    }

    @Test
    fun aPressWhereTheGroupWasBeforeItsTranslationMisses() {
        val root = FrameLayout(null)
        var clicks = 0
        val chip = LinearLayout(null).apply {
            isClickable = true
            setOnClickListener { clicks++ }
        }
        root.addView(chip, FrameLayout.LayoutParams(300, 100, Gravity.START or Gravity.BOTTOM).apply { bottomMargin = 200 })
        root.laidOut()
        chip.translationY = -150f

        root.dispatchTouchEvent(down(50f, 760f))
        root.dispatchTouchEvent(up(50f, 760f))

        assertEquals(0, clicks)
    }

    /** NextUpCard's Hide and Play now: Theme.Material's Widget.Material.Button.Borderless. */
    @Test
    fun aBorderlessButtonHasTheThemesSizeAndPadding() {
        val dp = ResourceFiles.density
        val button = Button(null, null, android.R.attr.borderlessButtonStyle)

        assertEquals((88 * dp).toInt(), button.minWidth)
        assertEquals((48 * dp).toInt(), button.minHeight)
        assertEquals((12 * dp).toInt(), button.paddingLeft)
        assertEquals((10 * dp).toInt(), button.paddingTop)
        assertTrue(button.isAllCaps)
    }

    @Test
    fun aButtonWithoutAStyleAttributeTakesNone() {
        val button = Button(null, null, 0)

        assertEquals(0, button.minWidth)
        assertEquals(0, button.paddingLeft)
    }

    /**
     * A ComposeView keeps its padding around its content, as Android's lays its child out inside
     * it: the content is given the room less the padding, and the View wraps the content plus it.
     */
    @Test
    fun aComposeViewsContentIsLaidOutInsideItsPadding() {
        val compose = androidx.compose.ui.platform.ComposeView(null)
        compose.setPadding(62, 10, 62, 21)
        compose.measure(
            View.MeasureSpec.makeMeasureSpec(874, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(402, View.MeasureSpec.AT_MOST),
        )
        val room = compose.contentConstraints()
        assertEquals(874 - 124, room.minWidth)
        assertEquals(874 - 124, room.maxWidth)
        assertEquals(0, room.minHeight)
        assertEquals(402 - 31, room.maxHeight)

        compose.contentMeasured(750, 200)
        compose.measure(
            View.MeasureSpec.makeMeasureSpec(874, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(402, View.MeasureSpec.AT_MOST),
        )
        assertEquals(874, compose.measuredWidth)
        assertEquals(200 + 31, compose.measuredHeight)
    }

    /**
     * A view nobody sees asks for no drawing, as on Android: hidden, in a hidden parent, fully
     * clear or in a clear parent (the player's controls fade out). Showing, hiding and fading one
     * still draw the window once, through its parent.
     */
    @Test
    fun aViewNobodySeesAsksForNoDrawing() {
        var draws = 0
        val root = FrameLayout(null)
        val controls = FrameLayout(null)
        val spinner = View(null)
        controls.addView(spinner)
        root.addView(controls)
        root.attachAsRoot(object : View.Host {
            override fun invalidateTree() {
                draws++
            }

            override fun requestTreeLayout() = Unit
        })
        spinner.invalidate()
        assertEquals(1, draws, "a shown view draws")

        controls.alpha = 0f
        assertEquals(2, draws, "fading the controls out draws once")
        spinner.invalidate()
        assertEquals(2, draws, "a view in a clear parent asks nothing")
        assertTrue(!spinner.isDrawn)

        controls.alpha = 1f
        assertEquals(3, draws, "fading them in draws once")
        assertTrue(spinner.isDrawn)

        spinner.visibility = View.INVISIBLE
        val hidden = draws
        spinner.invalidate()
        assertEquals(hidden, draws, "a hidden view asks nothing")
        spinner.visibility = View.VISIBLE
        assertTrue(draws > hidden, "showing it draws")
    }
}

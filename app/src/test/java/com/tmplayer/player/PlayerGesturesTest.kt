package com.tmplayer.player

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * WP-35: drags work with the row up and hide it on their first step, a palm never starts anything,
 * the hold is not armed on the bezel, and one 35 / 30 / 35 split serves taps and levels alike.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xxhdpi")
class PlayerGesturesTest {

    private val width = 2400
    private val height = 1080
    private val levels = mutableListOf<Pair<Boolean, Float>>()
    private val holds = mutableListOf<Boolean>()
    private var dragBegins = 0
    private var scrubs = 0
    private var edges = 0 to 0
    private lateinit var gestures: PlayerGestures
    private var clock = 0L
    private var downTime = 0L

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        gestures = PlayerGestures(
            context = activity,
            window = activity.window,
            onSkip = {},
            onLevel = { brightness, fraction, _ -> levels += brightness to fraction },
            onScrub = { _, _, _, _ -> scrubs++ },
            durationMs = { 3_600_000L },
            onHold = { holds += it },
            systemEdges = { edges },
            onDragBegin = { dragBegins++ },
        )
        clock = SystemClock.uptimeMillis()
    }

    private fun send(action: Int, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_FINGER) {
        clock += STEP_MS
        if (action == MotionEvent.ACTION_DOWN) downTime = clock
        val props = arrayOf(MotionEvent.PointerProperties().also { it.id = 0; it.toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().also { it.x = x; it.y = y; it.pressure = 1f; it.size = 1f })
        val event = MotionEvent.obtain(downTime, clock, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        gestures.onTouchEvent(event, width, height)
        event.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(STEP_MS))
        clock = SystemClock.uptimeMillis()
    }

    /** A vertical drag of 40 steps from [fromY] up to [toY] at [x]. */
    private fun dragUp(x: Float, fromY: Float = 900f, toY: Float = 300f, tool: Int = MotionEvent.TOOL_TYPE_FINGER) {
        send(MotionEvent.ACTION_DOWN, x, fromY, tool)
        for (i in 1..40) send(MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * i / 40f, tool)
        send(MotionEvent.ACTION_UP, x, toY, tool)
    }

    @Test
    fun aDragWorksWithTheRowUpAndHidesIt() {
        gestures.controlsVisible = true
        dragUp(x = width * 0.15f)
        assertTrue("brightness changed with the row up", levels.any { it.first })
        assertEquals("the row is told once", 1, dragBegins)
    }

    @Test
    fun volumeWorksOnTheRightWithTheRowUp() {
        gestures.controlsVisible = true
        dragUp(x = width * 0.85f)
        assertTrue(levels.any { !it.first })
        assertEquals(1, dragBegins)
    }

    @Test
    fun theSplitIsThirtyFiveThirtyThirtyFiveForLevelsToo() {
        // 40 percent in: the middle for a tap and now for a drag as well (it was brightness at 43).
        dragUp(x = width * 0.40f)
        assertTrue(levels.isEmpty())
        assertEquals(0, dragBegins)
        // 30 percent in: a side.
        dragUp(x = width * 0.30f)
        assertTrue(levels.any { it.first })
        // 60 percent: middle, 70 percent: volume.
        levels.clear()
        dragUp(x = width * 0.60f)
        assertTrue(levels.isEmpty())
        dragUp(x = width * 0.70f)
        assertTrue(levels.any { !it.first })
    }

    @Test
    fun aPalmStartsNothing() {
        dragUp(x = width * 0.15f, tool = PlayerGestures.TOOL_TYPE_PALM)
        assertTrue(levels.isEmpty())
        assertEquals(0, dragBegins)
        // The next, real touch is not stuck behind the palm.
        dragUp(x = width * 0.15f)
        assertTrue(levels.any { it.first })
    }

    @Test
    fun aHoldOnTheBezelIsNotArmed() {
        send(MotionEvent.ACTION_DOWN, 10f, 500f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
        send(MotionEvent.ACTION_UP, 10f, 500f)
        assertTrue("no hold within 16 dp of the edge", holds.isEmpty())
    }

    @Test
    fun aHoldInTheSystemGestureStripIsNotArmed() {
        edges = 120 to 120
        send(MotionEvent.ACTION_DOWN, 100f, 500f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
        send(MotionEvent.ACTION_UP, 100f, 500f)
        assertTrue(holds.isEmpty())
    }

    @Test
    fun aHoldOnThePictureStillArms() {
        send(MotionEvent.ACTION_DOWN, width / 2f, 500f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900))
        assertEquals(listOf(true), holds)
        send(MotionEvent.ACTION_UP, width / 2f, 500f)
        assertEquals(listOf(true, false), holds)
        assertFalse(gestures.controlsVisible)
    }

    private companion object {
        const val STEP_MS = 16L
    }
}

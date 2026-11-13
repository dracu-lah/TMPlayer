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
import kotlin.math.abs

/**
 * [PlayerGestures] fed the touch events a real hand makes (down, second finger down, many moves,
 * one finger up, the last up), on a phone sized screen at 480 dpi, so [android.view.ScaleGestureDetector]
 * has the same minimum span and slop as on a device. A small harness plays the part of the
 * activity: [onZoom], [onZoomEnd] and [onPan] go through [PictureZoom] exactly as PlayerActivity's
 * zoomBy, zoomEnded and panZoom do.
 *
 * Bug report (2026-11-13): after zooming in, volume and brightness stopped and a pinch would not
 * zoom back out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xxhdpi")
class PlayerGesturesZoomTest {

    private val width = 2400
    private val height = 1080
    private val frame = PictureZoom.Frame(width.toFloat(), height.toFloat(), 1920f, 1080f)

    // The activity's side of the callbacks.
    private var zoom = PictureZoom.NONE
    private var zoomAllowedNow = true
    private var onChromeNow: (Float, Float) -> Boolean = { _, _ -> false }
    private val levels = mutableListOf<Triple<Boolean, Float, Boolean>>()
    private val pans = mutableListOf<Pair<Float, Float>>()
    private val pinchSteps = mutableListOf<Boolean>()
    private val holds = mutableListOf<Boolean>()
    private val skips = mutableListOf<Long>()
    private val toggles = mutableListOf<Boolean>()
    private var zoomEnds = 0
    private var zoomSteps = 0
    private var scrubs = 0

    private lateinit var gestures: PlayerGestures

    // The hand's side: which fingers are down, and where.
    private val fingers = linkedMapOf<Int, Pair<Float, Float>>()
    private var downTime = 0L
    private var clock = 0L

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        gestures = PlayerGestures(
            context = activity,
            window = activity.window,
            onSkip = { skips += it },
            onLevel = { left, fraction, brightness -> levels += Triple(left, fraction, brightness) },
            onScrub = { _, _, _, _ -> scrubs++ },
            onPinch = { pinchSteps += it },
            zoomAllowed = { zoomAllowedNow },
            zoomed = { zoom.zoomed },
            onZoom = { factor, fx, fy, dx, dy ->
                zoomSteps++
                zoom = zoom.pinch(factor, fx, fy, frame).panBy(dx, dy, frame)
            },
            onZoomEnd = {
                zoomEnds++
                zoom = zoom.settled(frame)
            },
            onPan = { dx, dy ->
                pans += dx to dy
                zoom = zoom.panBy(dx, dy, frame)
            },
            durationMs = { 3_600_000L },
            onHold = { holds += it },
            onTogglePlay = { toggles += it },
            isOnChrome = { x, y -> onChromeNow(x, y) },
        )
        clock = SystemClock.uptimeMillis()
    }

    // ---- the hand ---------------------------------------------------------------------------

    private fun send(action: Int, index: Int = 0) {
        clock += STEP_MS
        val ids = fingers.keys.toList()
        val props = Array(ids.size) { i -> MotionEvent.PointerProperties().also { it.id = ids[i]; it.toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(ids.size) { i ->
            MotionEvent.PointerCoords().also {
                it.x = fingers.getValue(ids[i]).first
                it.y = fingers.getValue(ids[i]).second
                it.pressure = 1f
                it.size = 1f
            }
        }
        val code = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            action
        }
        val event = MotionEvent.obtain(downTime, clock, code, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        gestures.onTouchEvent(event, width, height)
        event.recycle()
        // Lets the detector's own timers (long press, tap confirm) run against the same clock.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(STEP_MS))
        clock = SystemClock.uptimeMillis()
    }

    private fun down(id: Int, x: Float, y: Float) {
        val first = fingers.isEmpty()
        fingers[id] = x to y
        if (first) {
            downTime = SystemClock.uptimeMillis().also { clock = it }
            send(MotionEvent.ACTION_DOWN)
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN, fingers.keys.indexOf(id))
        }
    }

    /** Moves the named fingers to where they should be at [t] (0 to 1) between two positions, in [steps] events. */
    private fun move(steps: Int, vararg paths: Triple<Int, Pair<Float, Float>, Pair<Float, Float>>) {
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            for ((id, from, to) in paths) {
                fingers[id] = (from.first + (to.first - from.first) * t) to (from.second + (to.second - from.second) * t)
            }
            send(MotionEvent.ACTION_MOVE)
        }
    }

    private fun path(id: Int, fx: Float, fy: Float, tx: Float, ty: Float) = Triple(id, fx to fy, tx to ty)

    private fun up(id: Int) {
        if (fingers.size == 1) {
            send(MotionEvent.ACTION_UP)
            fingers.clear()
        } else {
            val index = fingers.keys.indexOf(id)
            send(MotionEvent.ACTION_POINTER_UP, index)
            fingers.remove(id)
        }
    }

    private fun pause(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
        clock = SystemClock.uptimeMillis()
    }

    /** A two finger spread around ([cx], [cy]) from [from] to [to] pixels apart, fingers left down. */
    private fun spread(cx: Float, cy: Float, from: Float, to: Float, steps: Int = 30) {
        down(0, cx - from / 2, cy)
        down(1, cx + from / 2, cy)
        move(steps, path(0, cx - from / 2, cy, cx - to / 2, cy), path(1, cx + from / 2, cy, cx + to / 2, cy))
    }

    /** From the two fingers' present spread to [to], fingers left down. */
    private fun squeeze(cx: Float, cy: Float, from: Float, to: Float, steps: Int = 30) {
        move(steps, path(0, cx - from / 2, cy, cx - to / 2, cy), path(1, cx + from / 2, cy, cx + to / 2, cy))
    }

    private fun liftBoth() {
        up(1)
        up(0)
    }

    /** One full pinch out to roughly three times, both fingers lifted afterwards. */
    private fun zoomInToAbout300() {
        spread(1200f, 540f, from = 300f, to = 1500f)
        liftBoth()
    }

    // ---- zooming back out -------------------------------------------------------------------

    @Test
    fun `a pinch out zooms in to about 300 per cent`() {
        zoomInToAbout300()
        assertTrue("scale ${zoom.scale}", zoom.scale in 2.5f..4f)
        assertEquals(1, zoomEnds)
        assertTrue(pinchSteps.isEmpty())
    }

    @Test
    fun `a pinch in on a 300 per cent picture zooms out and settles on 100`() {
        zoomInToAbout300()
        val zoomedScale = zoom.scale
        // One generous pinch in, from a wide span down to the fingers nearly touching.
        down(0, 600f, 540f)
        down(1, 1800f, 540f)
        squeeze(1200f, 540f, from = 1200f, to = 120f, steps = 40)
        liftBoth()
        assertTrue("went from $zoomedScale to ${zoom.scale}", zoom.scale < zoomedScale)
        assertEquals("a single pinch in from 1200 px to 120 px", PictureZoom.NONE, zoom)
        assertTrue(pinchSteps.isEmpty())
    }

    @Test
    fun `a pinch in repeated reaches 100 per cent`() {
        zoomInToAbout300()
        var tries = 0
        while (zoom.zoomed && tries < 6) {
            down(0, 1000f, 540f)
            down(1, 1400f, 540f)
            squeeze(1200f, 540f, from = 400f, to = 100f, steps = 20)
            liftBoth()
            tries++
        }
        assertEquals(PictureZoom.NONE, zoom)
        println("pinches in from a 400 px start needed to go from about 300 to 100: $tries")
    }

    @Test
    fun `a short pinch in at a real thumb distance shrinks the picture each time`() {
        zoomInToAbout300()
        var last = zoom.scale
        repeat(3) {
            down(0, 900f, 540f)
            down(1, 1500f, 540f)
            squeeze(1200f, 540f, from = 600f, to = 300f, steps = 20)
            liftBoth()
            assertTrue("pinch ${it + 1}: ${zoom.scale} should be under $last", zoom.scale < last || zoom.scale == 1f)
            last = zoom.scale
        }
    }

    @Test
    fun `a pinch in with the picture panned off centre still zooms out`() {
        zoomInToAbout300()
        // Drag the picture to a corner first.
        down(0, 1200f, 540f)
        move(10, path(0, 1200f, 540f, 600f, 300f))
        up(0)
        val panned = zoom
        assertTrue(panned.panX != 0f || panned.panY != 0f)
        down(0, 600f, 540f)
        down(1, 1800f, 540f)
        squeeze(1200f, 540f, from = 1200f, to = 120f, steps = 40)
        liftBoth()
        assertEquals(PictureZoom.NONE, zoom)
    }

    @Test
    fun `a pinch in that starts as a one finger pan zooms out`() {
        zoomInToAbout300()
        val before = zoom.scale
        // One finger drags the picture, then the second lands while the first is still moving.
        down(0, 600f, 540f)
        move(8, path(0, 600f, 540f, 700f, 560f))
        val panCalls = pans.size
        assertTrue("the drag pans", panCalls > 0)
        fingers[1] = 1800f to 540f
        send(MotionEvent.ACTION_POINTER_DOWN, 1)
        squeeze(1250f, 550f, from = 1100f, to = 120f, steps = 40)
        liftBoth()
        assertTrue("zoomed out from $before to ${zoom.scale}", zoom.scale < before)
        assertTrue(pinchSteps.isEmpty())
    }

    @Test
    fun `lifting one finger of a pinch pans with the other and never changes volume`() {
        zoomInToAbout300()
        down(0, 1900f, 400f)
        down(1, 1500f, 400f)
        squeeze(1700f, 400f, from = 400f, to = 700f, steps = 10)
        up(0)
        val levelsBefore = levels.size
        move(10, path(1, fingers.getValue(1).first, 400f, fingers.getValue(1).first, 900f))
        up(1)
        assertEquals("a finger left down after a pinch is never a level drag", levelsBefore, levels.size)
    }

    @Test
    fun `a finger left down after the span fell under the minimum pans and the next pinch works`() {
        zoomInToAbout300()
        down(0, 1100f, 540f)
        down(1, 1300f, 540f)
        // The span is already below the detector's minimum: nothing is a pinch, two fingers pan.
        move(10, path(0, 1100f, 540f, 1000f, 500f), path(1, 1300f, 540f, 1200f, 500f))
        assertEquals(0, pinchSteps.size)
        // Now they spread wide and come back in: that is a pinch.
        val scaleBefore = zoom.scale
        move(20, path(0, 1000f, 500f, 500f, 500f), path(1, 1200f, 500f, 1900f, 500f))
        val grown = zoom.scale
        assertTrue("spread zooms in or stays at the cap ($scaleBefore to $grown)", grown >= scaleBefore)
        move(30, path(0, 500f, 500f, 1150f, 500f), path(1, 1900f, 500f, 1250f, 500f))
        liftBoth()
        assertTrue("pinched in again: $grown then ${zoom.scale}", zoom.scale < grown)
    }

    // ---- when free zoom is not allowed ------------------------------------------------------

    @Test
    fun `a pinch in while zoomed still drives the free zoom when zoomAllowed flips false`() {
        zoomInToAbout300()
        val before = zoom.scale
        zoomAllowedNow = false
        down(0, 600f, 540f)
        down(1, 1800f, 540f)
        squeeze(1200f, 540f, from = 1200f, to = 120f, steps = 40)
        liftBoth()
        assertTrue("a zoomed picture must zoom out whatever zoomAllowed says: $before to ${zoom.scale}", zoom.scale < before)
        assertTrue("shape stepping must not stand in for the free zoom", pinchSteps.isEmpty())
    }

    @Test
    fun `a pinch at 100 per cent with zoomAllowed false steps the shape`() {
        zoomAllowedNow = false
        spread(1200f, 540f, from = 300f, to = 1500f)
        liftBoth()
        assertEquals(PictureZoom.NONE, zoom)
        assertTrue("spreading steps the shape out, never in", pinchSteps.isNotEmpty() && pinchSteps.all { it })
    }

    // ---- after a long press or a double tap -------------------------------------------------

    @Test
    fun `a pinch in after a long press zooms out and drops the hold`() {
        zoomInToAbout300()
        val before = zoom.scale
        down(0, 600f, 540f)
        pause(700)
        assertEquals(listOf(true), holds)
        fingers[1] = 1800f to 540f
        send(MotionEvent.ACTION_POINTER_DOWN, 1)
        squeeze(1200f, 540f, from = 1200f, to = 120f, steps = 40)
        liftBoth()
        assertTrue("zoomed out from $before to ${zoom.scale}", zoom.scale < before)
        assertEquals(listOf(true, false), holds)
    }

    @Test
    fun `a pinch in that starts on the second tap of a double tap zooms out`() {
        zoomInToAbout300()
        val before = zoom.scale
        // First tap.
        down(0, 1200f, 540f)
        up(0)
        pause(80)
        // Second tap lands, and while it is down a second finger joins and pinches in.
        downTime = SystemClock.uptimeMillis()
        down(0, 600f, 540f)
        fingers[1] = 1800f to 540f
        send(MotionEvent.ACTION_POINTER_DOWN, 1)
        squeeze(1200f, 540f, from = 1200f, to = 120f, steps = 40)
        liftBoth()
        assertTrue("zoomed out from $before to ${zoom.scale}", zoom.scale < before)
    }

    @Test
    fun `a plain double tap in the middle toggles play and never touches the zoom`() {
        zoomInToAbout300()
        val before = zoom
        down(0, 1200f, 540f)
        up(0)
        pause(80)
        down(0, 1200f, 540f)
        up(0)
        pause(400)
        assertEquals(listOf(false), toggles)
        assertEquals(before, zoom)
    }

    // ---- a pinch that begins on the chrome --------------------------------------------------

    @Test
    fun `a touch whose first finger is on the controls is theirs and not a pinch`() {
        zoomInToAbout300()
        val before = zoom.scale
        onChromeNow = { _, y -> y > 900f }
        down(0, 600f, 1000f)
        down(1, 1800f, 500f)
        squeeze(1200f, 750f, from = 1200f, to = 120f, steps = 40)
        liftBoth()
        assertEquals("by design the controls own the whole touch", before, zoom.scale, 0f)
        // And the next touch, on the picture, is not poisoned by the last.
        onChromeNow = { _, _ -> false }
        down(0, 600f, 540f)
        down(1, 1800f, 540f)
        squeeze(1200f, 540f, from = 1200f, to = 120f, steps = 40)
        liftBoth()
        assertEquals(PictureZoom.NONE, zoom)
    }

    @Test
    fun `a pinch whose second finger lands on the controls still zooms`() {
        zoomInToAbout300()
        onChromeNow = { _, y -> y > 900f }
        down(0, 600f, 540f)
        down(1, 1800f, 1000f)
        squeeze(1200f, 770f, from = 1200f, to = 120f, steps = 40)
        liftBoth()
        assertTrue(zoom.scale < 2.5f)
    }

    // ---- brightness and volume in the strips of a zoomed picture ----------------------------

    @Test
    fun `a vertical drag in the left strip of a zoomed picture is brightness`() {
        zoomInToAbout300()
        down(0, 200f, 800f)
        move(20, path(0, 200f, 800f, 200f, 300f))
        up(0)
        assertTrue(levels.isNotEmpty())
        assertTrue(levels.all { it.first && it.third })
        assertTrue("the picture did not pan", pans.isEmpty())
        assertTrue("brightness went up with the drag", levels.last().second > levels.first().second)
    }

    @Test
    fun `a vertical drag in the right strip of a zoomed picture is volume`() {
        zoomInToAbout300()
        down(0, 2200f, 300f)
        move(20, path(0, 2200f, 300f, 2200f, 800f))
        up(0)
        assertTrue(levels.isNotEmpty())
        assertTrue(levels.all { !it.first && !it.third })
        assertTrue(pans.isEmpty())
    }

    @Test
    fun `a vertical drag away from the strips pans the picture`() {
        zoomInToAbout300()
        for (x in listOf(500f, 1200f, 1900f)) {
            pans.clear()
            levels.clear()
            down(0, x, 800f)
            move(20, path(0, x, 800f, x, 400f))
            up(0)
            assertTrue("x=$x pans", pans.isNotEmpty())
            assertTrue("x=$x no level", levels.isEmpty())
        }
    }

    @Test
    fun `a diagonal drag in the strip pans and loses none of the held back pixels`() {
        zoomInToAbout300()
        down(0, 200f, 800f)
        move(30, path(0, 200f, 800f, 420f, 560f))
        up(0)
        assertTrue(levels.isEmpty())
        val sx = pans.sumOf { it.first.toDouble() }
        val sy = pans.sumOf { it.second.toDouble() }
        assertEquals("pan x equals the finger's travel", 220.0, sx, 1.0)
        assertEquals("pan y equals the finger's travel", -240.0, sy, 1.0)
    }

    @Test
    fun `a sideways drag in the strip pans and does not scrub`() {
        zoomInToAbout300()
        down(0, 200f, 800f)
        move(20, path(0, 200f, 800f, 700f, 790f))
        up(0)
        assertEquals(0, scrubs)
        assertTrue(pans.isNotEmpty())
        assertTrue(levels.isEmpty())
    }

    @Test
    fun `a level drag in the strip starts from where the finger was with no jump`() {
        zoomInToAbout300()
        down(0, 200f, 800f)
        move(30, path(0, 200f, 800f, 200f, 500f))
        up(0)
        val first = levels.first().second
        val second = levels[1].second
        // The held back pixels are a few percent of the range, never a leap, and each step is smooth.
        assertTrue("first step ${first} to ${second}", abs(second - first) < 0.05f)
        val steps = levels.map { it.second }.zipWithNext { a, b -> abs(b - a) }
        assertTrue("largest step ${steps.maxOrNull()}", (steps.maxOrNull() ?: 0f) < 0.06f)
    }

    @Test
    fun `brightness and volume in the strips behave as before at 100 per cent`() {
        down(0, 1200f, 800f)
        move(20, path(0, 1200f, 800f, 1200f, 300f))
        up(0)
        assertTrue("the middle band is neither", levels.isEmpty())
        down(0, 200f, 800f)
        move(20, path(0, 200f, 800f, 200f, 300f))
        up(0)
        assertTrue(levels.isNotEmpty())
    }

    @Test
    fun `a pinch after a drag in the strip works and the strip drag does not come back`() {
        zoomInToAbout300()
        down(0, 200f, 800f)
        move(20, path(0, 200f, 800f, 200f, 400f))
        val count = levels.size
        fingers[1] = 1800f to 540f
        send(MotionEvent.ACTION_POINTER_DOWN, 1)
        squeeze(1000f, 470f, from = 1600f, to = 120f, steps = 40)
        liftBoth()
        assertEquals("the pinch is not a level drag", count, levels.size)
        assertEquals(PictureZoom.NONE, zoom)
    }

    // ---- the end of a touch ------------------------------------------------------------------

    @Test
    fun `a cancelled pinch settles the zoom and the next touch starts clean`() {
        zoomInToAbout300()
        down(0, 600f, 540f)
        down(1, 1800f, 540f)
        squeeze(1200f, 540f, from = 1200f, to = 800f, steps = 20)
        send(MotionEvent.ACTION_CANCEL)
        fingers.clear()
        val after = zoom
        assertFalse(after.scale in 1.0001f..1.0499f)
        down(0, 600f, 540f)
        move(10, path(0, 600f, 540f, 700f, 560f))
        up(0)
        assertTrue("one finger pans after a cancelled pinch", pans.isNotEmpty())
    }

    companion object {
        private const val STEP_MS = 12L
    }
}

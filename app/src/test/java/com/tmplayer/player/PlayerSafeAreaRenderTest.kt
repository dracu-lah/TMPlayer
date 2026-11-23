package com.tmplayer.player

import android.graphics.Rect
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import com.tmplayer.R
import com.tmplayer.online.SkipKind
import com.tmplayer.online.SkipSegment
import com.tmplayer.data.FormFactor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * WP-05: on a television every control of the player stands inside the 5 percent title safe area,
 * 48 dp from the sides and 27 dp from the top and bottom: the row, the title and bar, the clock,
 * the corner chips, the next-up card, the skip pill and the "Back to" chip. Drawn at 960 x 540 dp
 * (1080p at density 2) and at 1920 x 1080 dp (density 1), the row up with every button and its
 * words. A phone keeps the figures it always had.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerSafeAreaRenderTest {

    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
            "getDuration" -> 2_634_000L
            "getCurrentPosition" -> 1_260_000L
            "getBufferedPosition" -> 1_700_000L
            "isPlaying", "getPlayWhenReady" -> true
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as Player

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        FormFactor.override(false)
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    private class Screen(val root: FrameLayout, val offers: List<View>)

    private fun screen(tv: Boolean): Screen {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        activity.setContentView(R.layout.activity_player)
        val root = activity.findViewById<FrameLayout>(R.id.player_root)
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        val controls = PlayerControls(
            root = root, isTv = tv, player = { player }, onVisibility = {}, onTogglePlay = {}, onPickSubtitles = {},
            onPickAudio = {}, onPickSpeed = {}, onCycleOrientation = {}, onPlayEpisode = {}, onSeekButton = {}, showLabels = true,
        )
        controls.show()
        val below = root.getChildAt(0)
        val next = NextUpCard(root, below, tv, onHide = {}, onPlay = {})
        next.show("S01E02", 8)
        val skip = SkipPill(root, below, tv, onSkip = {})
        skip.show(SkipSegment(SkipKind.Intro, 0, 60_000))
        val undo = UndoSeekChip(root, below, tv, onUndo = {})
        undo.show(300_000L)
        // The corner column's chips (the download figure and the rebuffer chip) as they appear.
        (root.findViewById<View>(R.id.top_right_stack) as ViewGroup).let { g ->
            for (i in 0 until g.childCount) g.getChildAt(i).visibility = View.VISIBLE
        }
        // Lay it out at the screen's size, the way the window does.
        val dm = root.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        idle()
        return Screen(root, listOf(next.view, skip.view, undo.view))
    }

    /** Every drawn control: a leaf view that is shown, has size and is no full-screen scrim, tint or surface. */
    private fun controlsOf(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            // The subtitles draw over the whole picture and keep their own bottom clearance.
            if (v is androidx.media3.ui.SubtitleView) return
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
                return
            }
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull().orEmpty()
            val wide = v.width >= root.width - 2 && v.height >= root.height - 2
            val scrim = name.startsWith("controls_scrim") || name == "controls_tint"
            if (v.isShown && v.width > 0 && v.height > 0 && !wide && !scrim) out += v
        }
        walk(root)
        return out
    }

    private fun boundsDp(v: View): Rect {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        val d = v.resources.displayMetrics.density
        return Rect(
            (at[0] / d).toInt(), (at[1] / d).toInt(),
            Math.ceil(((at[0] + v.width) / d).toDouble()).toInt(), Math.ceil(((at[1] + v.height) / d).toDouble()).toInt(),
        )
    }

    private fun assertInside(s: Screen, widthDp: Int, heightDp: Int) {
        val density = s.root.resources.displayMetrics.density
        assertEquals(widthDp.toFloat(), s.root.width / density, 1f)
        val shown = controlsOf(s.root)
        assertTrue("the row has its controls (${shown.size})", shown.size > 8)
        for (id in listOf(R.id.control_subtitles, R.id.control_audio, R.id.control_speed, R.id.control_shape, R.id.control_tv_more, R.id.controls_clock, R.id.controls_timebar)) {
            assertTrue("view $id is drawn", s.root.findViewById<View>(id).isShown)
        }
        for (offer in s.offers) assertTrue("an offer is drawn", offer.isShown && offer.width > 0)
        val bad = shown.map { it to boundsDp(it) }.filter { (_, b) ->
            b.left < 48 || b.top < 27 || b.right > widthDp - 48 || b.bottom > heightDp - 27
        }.map { (v, b) ->
            val n = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: v.javaClass.simpleName
            "$n at ${b.left},${b.top} to ${b.right},${b.bottom} dp"
        }
        assertTrue("outside the 48 x 27 dp safe area of $widthDp x $heightDp dp: $bad", bad.isEmpty())
        for (offer in s.offers) {
            val b = boundsDp(offer)
            assertTrue("an offer at $b is inside", b.left >= 48 && b.right <= widthDp - 48 && b.bottom <= heightDp - 27 && b.top >= 27)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the television's controls stand inside 48 and 27 dp at 960 x 540 dp`() {
        assertInside(screen(tv = true), 960, 540)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun `the television's controls stand inside 48 and 27 dp at 1920 x 1080 dp`() {
        assertInside(screen(tv = true), 1920, 1080)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `at font 1_3 the cluster grows upward and still stands inside the safe area`() {
        RuntimeEnvironment.setFontScale(1.3f)
        assertInside(screen(tv = true), 960, 540)
    }

    private fun assertOffersClearTheRow(widthDp: Int, heightDp: Int, font: Float) {
        RuntimeEnvironment.setFontScale(font)
        val s = screen(tv = true)
        val cluster = s.root.findViewById<ViewGroup>(R.id.controls_cluster)
        assertTrue("the row is up", cluster.isShown && cluster.height > 0)
        for (offer in s.offers) {
            // What PlayerActivity.liftCornerOffers does once the offer has a height.
            offer.translationY = -NextUpCard.liftOverRow(s.root, cluster, offer, controlsUp = true).toFloat()
            val at = IntArray(2).also { offer.getLocationInWindow(it) }
            val offerRect = Rect(at[0], at[1], at[0] + offer.width, at[1] + offer.height)
            // The drawn children of the row, the cluster's own padding aside.
            for (i in 0 until cluster.childCount) {
                val child = cluster.getChildAt(i)
                if (child.visibility != View.VISIBLE || child.height == 0) continue
                val c = IntArray(2).also { child.getLocationInWindow(it) }
                val childRect = Rect(c[0], c[1], c[0] + child.width, c[1] + child.height)
                assertTrue("an offer $offerRect overlaps row child $childRect at font $font, $widthDp x $heightDp dp", !Rect.intersects(offerRect, childRect))
            }
            val d = s.root.resources.displayMetrics.density
            assertTrue("the lifted offer keeps its 27 dp top margin (${offerRect.top / d} dp)", offerRect.top >= 27 * d - 1)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `no offer overlaps the row at font 1_3, 960 x 540 dp`() = assertOffersClearTheRow(960, 540, 1.3f)

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun `no offer overlaps the row at font 1_3, 1920 x 1080 dp`() = assertOffersClearTheRow(1920, 1080, 1.3f)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the television's dimens are the 5 percent margins`() {
        val r = RuntimeEnvironment.getApplication().resources
        val d = r.displayMetrics.density
        fun dp(id: Int) = r.getDimensionPixelSize(id) / d
        assertEquals(48f, dp(R.dimen.player_safe_h), 0.1f)
        assertEquals(27f, dp(R.dimen.player_safe_v), 0.1f)
        assertEquals(27f, dp(R.dimen.player_clock_top), 0.1f)
        assertEquals(48f, dp(R.dimen.player_chip_margin_h), 0.1f)
        assertEquals(27f, dp(R.dimen.player_chip_margin_v), 0.1f)
        assertEquals(48f, dp(R.dimen.player_offer_margin), 0.1f)
        // The subtitles keep their 48 dp, above the 27 dp the raised row stands on.
        assertTrue(dp(R.dimen.player_subtitle_bottom) >= dp(R.dimen.player_safe_v))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun `a phone keeps the figures it always had`() {
        val s = screen(tv = false)
        val d = s.root.resources.displayMetrics.density
        val cluster = s.root.findViewById<View>(R.id.controls_cluster)
        assertEquals(24f, cluster.paddingLeft / d, 0.1f)
        assertEquals(24f, cluster.paddingRight / d, 0.1f)
        assertEquals(12f, cluster.paddingBottom / d, 0.1f)
        val clock = s.root.findViewById<View>(R.id.controls_clock).layoutParams as FrameLayout.LayoutParams
        assertEquals(24f, clock.marginStart / d, 0.1f)
        assertEquals(16f, clock.topMargin / d, 0.1f)
        val stack = s.root.findViewById<View>(R.id.top_right_stack).layoutParams as FrameLayout.LayoutParams
        assertEquals(16f, stack.leftMargin / d, 0.1f)
        assertEquals(16f, stack.topMargin / d, 0.1f)
        assertEquals(24, (NextUpCard.marginPx(s.root) / d).toInt())
        assertEquals(110, (NextUpCard.bottomPx(s.root) / d).toInt())
    }
}

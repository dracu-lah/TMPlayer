package com.tmplayer.tvux

import android.graphics.Color
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import com.tmplayer.R
import com.tmplayer.player.PlayerControls
import com.tmplayer.ui.theme.FocusStyle
import org.junit.After
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
 * The tv-ux-review checklist on the player's row (WP-16): `player_controls` is Views, so the same
 * bounds and label rules as [TvUxChecklistTest] read the views and their [android.view.accessibility.AccessibilityNodeInfo]:
 * every focusable shown has words and a role, a focus rect 48 dp tall, a place inside the safe
 * area, the same size focused as at rest and no scale past the design's, and text of 14 sp or
 * more at font 1.0 and 1.3. Behind `-Ptvux=true` with the rest of the suite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvUxPlayerRowTest {

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
    fun tearDown() = RuntimeEnvironment.setFontScale(1f)

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }

    private fun show(fontScale: Float): FrameLayout {
        RuntimeEnvironment.setFontScale(fontScale)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply {
            id = R.id.player_root
            setBackgroundColor(Color.rgb(0x20, 0x30, 0x40))
        }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        activity.setContentView(root)
        PlayerControls(
            root = root, isTv = true, player = { player }, onVisibility = {}, onTogglePlay = {}, onPickSubtitles = {},
            onPickAudio = {}, onPickSpeed = {}, onCycleOrientation = {}, onPlayEpisode = {}, onSeekButton = {}, showLabels = true,
        ).show()
        idle()
        return root
    }

    private fun walk(v: View, out: MutableList<View>) {
        if (v.visibility != View.VISIBLE) return
        out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), out)
    }

    /** Every view that can be drawn later: not gone, so a time that has no text yet and an invisible caption count. */
    private fun walkNotGone(v: View, out: MutableList<View>) {
        if (v.visibility == View.GONE) return
        out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) walkNotGone(v.getChildAt(i), out)
    }

    private fun name(v: View): String {
        val info = v.createAccessibilityNodeInfo()
        val words = info.contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: info.text?.toString()?.takeIf { it.isNotBlank() }
        return words?.replace('|', '/')?.replace('\n', ' ')?.take(60)?.trim() ?: runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: "unnamed ${v.javaClass.simpleName}"
    }

    private fun shown(v: View): Boolean = v.isShown && v.width > 0 && v.height > 0

    @Test
    fun checklist() {
        val screen = "playerRow"
        val findings = mutableListOf<Finding>()
        fun add(rule: String, node: String, detail: String) { findings += Finding(screen, rule, node, detail) }

        for (font in listOf(1f, 1.3f)) {
            val root = show(font)
            val density = root.resources.displayMetrics.density
            val all = mutableListOf<View>().also { walk(root, it) }.filter(::shown)
            val w = root.rootView.width; val h = root.rootView.height
            if (font == 1f) {
                for (v in all.filter { it.isFocusable }) {
                    val n = name(v)
                    val info = v.createAccessibilityNodeInfo()
                    if (info.contentDescription.isNullOrBlank() && info.text.isNullOrBlank()) add("labelled", n, "no text or contentDescription")
                    if (info.className.isNullOrBlank() || info.className == View::class.java.name) add("labelled", n, "no role (class ${info.className})")
                    if (v.height / density + 0.5f < 48f) add("targets", n, "focus rect ${"%.1f".format(v.height / density)} dp tall, want 48.0")
                    val at = IntArray(2).also { v.getLocationInWindow(it) }
                    val left = at[0] / density; val top = at[1] / density
                    val right = (at[0] + v.width) / density; val bottom = (at[1] + v.height) / density
                    if (left < 48f - 0.5f || top < 27f - 0.5f || right > w / density - 48f + 0.5f || bottom > h / density - 27f + 0.5f) {
                        add("safeArea", n, "at ${left.toInt()},${top.toInt()} ${(right - left).toInt()}x${(bottom - top).toInt()} dp, outside the 48 x 27 dp safe area")
                    }
                    val restW = v.width; val restH = v.height
                    v.requestFocus()
                    idle()
                    if (v.isFocused) {
                        if (v.width != restW || v.height != restH) add("focusSizeStable", n, "layout ${restW}x$restH px at rest, ${v.width}x${v.height} px focused")
                        val scale = maxOf(v.scaleX, v.scaleY)
                        if (scale > FocusStyle.TvScale + 0.005f) add("focusSizeStable", n, "drawn at scale $scale focused, more than the design scale ${FocusStyle.TvScale}")
                    }
                }
            }
            // WP-04 review: a text with no words yet (the times before the first tick) sets its size
            // in the layout all the same, and the seek figure and the labels are set in code.
            val names = mutableSetOf<String>()
            for (t in mutableListOf<View>().also { walkNotGone(root, it) }.filterIsInstance<TextView>()) {
                val sp = t.textSize / density
                val id = runCatching { t.resources.getResourceEntryName(t.id) }.getOrNull() ?: "unnamed ${t.javaClass.simpleName}"
                if (sp + 0.05f < 14f && names.add(id)) add("textFloor@Default,${"%.1f".format(font)}", "view $id", "laid out at ${"%.1f".format(sp)} sp of the screen, want 14.0")
            }
            for (t in all.filterIsInstance<TextView>()) {
                if (t.text.isNullOrBlank()) continue
                // textSize is px with the font scale in it; the floor is the screen's own 14 sp at font 1.0.
                val sp = t.textSize / density
                if (sp + 0.05f < 14f) add("textFloor@Default,${"%.1f".format(font)}", name(t), "laid out at ${"%.1f".format(sp)} sp of the screen, want 14.0")
            }
        }
        assertNoFindings(screen, findings)
    }
}

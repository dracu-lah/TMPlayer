package com.tmplayer.player

import android.graphics.Color
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.tmplayer.R
import com.tmplayer.data.MediaItem

/**
 * The transport overlay in player_controls.xml, given its behaviour.
 *
 * One of these serves the phone and the television, which is the point of it: the two used to run
 * different control surfaces (Media3's stock row and leanback's transport fragment) and looked
 * like two different apps. Everything here works from clicks and focus, so a thumb and a D-pad
 * drive the same buttons, and the only per-device difference is which extras are offered: the
 * orientation button is a phone thing, and the automatic focus grab on show is a D-pad thing.
 *
 * That focus grab lands on the scrub bar, not on a button. On a remote the arrows over the bare
 * picture jump on their own, the activity's business; here they matter once the row is up, where
 * a row that opened onto play or pause made scrubbing a two-step trip: Up to the bar, then the
 * arrow. Opening onto the bar means left and right stride it the moment the row appears, with
 * the buttons one press Down away. The bar's stride is [Skip.BAR_MS], deliberately coarser than
 * the bare-picture jumps: whoever opened the row to scrub is travelling, not nudging. The bar
 * folds a burst of presses into one committed seek, which matters here more than in most
 * players: every committed seek moves TDLib's streaming download window, so ten presses landing
 * as one seek is nine downloads not restarted. [okOnTimeBar] gives OK on the focused bar the
 * Netflix meaning, play or pause, unless a scrub is mid-flight, in which case the bar itself
 * takes the press and commits.
 *
 * The activity stays the owner of every action. This class decides nothing about playback; it
 * raises, lowers and repaints the furniture, and forwards each press to the lambda wired for it.
 */
class PlayerControls(
    root: View,
    private val isTv: Boolean,
    private val player: () -> Player?,
    private val onVisibility: (Boolean) -> Unit,
    private val onTogglePlay: () -> Unit,
    private val onSkip: (Long) -> Unit,
    private val onPickSubtitles: () -> Unit,
    private val onPickAudio: () -> Unit,
    private val onCycleSpeed: () -> Unit,
    private val onCycleScale: () -> Unit,
    private val onCycleOrientation: () -> Unit,
    private val onPlayEpisode: (MediaItem) -> Unit,
) {

    private val container: View = root.findViewById(R.id.player_controls)
    private val title: TextView = root.findViewById(R.id.controls_title)
    private val subtitle: TextView = root.findViewById(R.id.controls_subtitle)
    private val timeBar: DefaultTimeBar = root.findViewById(R.id.controls_timebar)
    private val time: TextView = root.findViewById(R.id.controls_time)
    private val clock: TextView = root.findViewById(R.id.controls_clock)
    private val playPause: ImageButton = root.findViewById(R.id.control_play_pause)
    private val previous: ImageButton = root.findViewById(R.id.control_previous)
    private val next: ImageButton = root.findViewById(R.id.control_next)
    private val rotate: ImageButton = root.findViewById(R.id.control_rotate)

    private val accent = ContextCompat.getColor(root.context, R.color.accent)

    val visible: Boolean get() = container.visibility == View.VISIBLE

    /** True while a finger or the D-pad is on the bar; the clock leaves the bar alone then. */
    private var scrubbing = false

    private val hide = Runnable { hideAnimated() }

    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            renderProgress()
            container.postDelayed(this, TICK_MS)
        }
    }

    init {
        wire(playPause) { onTogglePlay(); renderPlayPause() }
        wire(root.findViewById(R.id.control_rewind)) { onSkip(-Skip.BACK_MS) }
        wire(root.findViewById(R.id.control_forward)) { onSkip(Skip.FORWARD_MS) }
        wire(root.findViewById(R.id.control_subtitles)) { onPickSubtitles() }
        wire(root.findViewById(R.id.control_audio)) { onPickAudio() }
        wire(root.findViewById(R.id.control_speed)) { onCycleSpeed() }
        wire(root.findViewById(R.id.control_scale)) { onCycleScale() }
        wire(rotate) { onCycleOrientation() }
        rotate.visibility = if (isTv) View.GONE else View.VISIBLE

        // A press of D-pad left or right on the bar steps the position by this much: coarser than
        // the bare-picture arrow jumps on purpose, because opening the row to scrub is travelling,
        // and fixed rather than Media3's twentieth-of-a-film so a press means the same thing on a
        // short clip as on a long film.
        timeBar.setKeyTimeIncrement(Skip.BAR_MS)
        // The pill behind the bar comes from its state-list background; the scrubber joins it by
        // turning white, because from a sofa the accent-blue dot alone does not read as "focused"
        // against the accent-blue played run it sits on.
        timeBar.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            timeBar.setScrubberColor(if (focused) Color.WHITE else accent)
            poke()
        }
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(bar: TimeBar, position: Long) {
                scrubbing = true
                container.removeCallbacks(hide)
                renderClock(position)
            }

            override fun onScrubMove(bar: TimeBar, position: Long) = renderClock(position)

            override fun onScrubStop(bar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled) player()?.seekTo(position)
                poke()
            }
        })
    }

    /** A press does its thing and buys the row more time on screen; so does focus landing. */
    private fun wire(button: View, action: () -> Unit) {
        button.setOnClickListener {
            action()
            poke()
        }
        button.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused) poke()
        }
    }

    fun setTitle(name: String, detail: String) {
        title.text = name
        title.visibility = if (name.isBlank()) View.GONE else View.VISIBLE
        subtitle.text = detail
        subtitle.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Offers the episode steps once the chat search has answered, and never before: a button that
     * does nothing is worse than no button.
     */
    fun setEpisodes(
        previousEpisode: MediaItem?,
        nextEpisode: MediaItem?,
        previousLabel: String,
        nextLabel: String,
    ) {
        previous.visibility = if (previousEpisode != null) View.VISIBLE else View.GONE
        previous.contentDescription = previousLabel
        previous.setOnClickListener {
            previousEpisode?.let(onPlayEpisode)
        }
        next.visibility = if (nextEpisode != null) View.VISIBLE else View.GONE
        next.contentDescription = nextLabel
        next.setOnClickListener {
            nextEpisode?.let(onPlayEpisode)
        }
    }

    /** The orientation button carries its current state, drawn by the activity that owns it. */
    fun setOrientationIcon(resId: Int, label: String) {
        rotate.setImageResource(resId)
        rotate.contentDescription = label
    }

    fun show() {
        container.removeCallbacks(hide)
        if (!visible) {
            container.visibility = View.VISIBLE
            container.animate().alpha(1f).setDuration(FADE_MS).start()
            onVisibility(true)
            renderPlayPause()
            renderProgress()
            container.postDelayed(tick, TICK_MS)
            // A remote has no pointer, so something must hold focus the moment the row appears,
            // and the bar is what earns it: the arrows then seek at once, which is what an arrow
            // over a video means everywhere else, and the buttons are one press Down away.
            if (isTv) timeBar.requestFocus()
        }
        poke()
    }

    /**
     * OK on the focused bar toggles playback, the way Netflix reads it: two presses of OK from
     * the bare picture pause the film. Mid-scrub it stays out of the way and answers false, so
     * the press falls through to the bar itself, which commits the scrub.
     */
    fun okOnTimeBar(): Boolean {
        if (scrubbing || !timeBar.hasFocus()) return false
        onTogglePlay()
        renderPlayPause()
        poke()
        return true
    }

    /**
     * Puts the D-pad back on the buttons, for when a picker above the row closes and focus falls
     * wherever the system drops it: left alone it lands on the scrub bar, and the next press
     * seeks instead of walking the row. Play or pause is the deliberate choice here, not the
     * bar: someone leaving a picker was working the buttons, and is put back among them.
     */
    fun focusRow() {
        if (!isTv || !visible) return
        playPause.requestFocus()
        poke()
    }

    fun toggle() = if (visible) hideAnimated() else show()

    fun hideAnimated() {
        container.removeCallbacks(hide)
        container.removeCallbacks(tick)
        if (!visible) return
        container.animate().alpha(0f).setDuration(FADE_MS)
            .withEndAction { container.visibility = View.GONE }
            .start()
        onVisibility(false)
    }

    /** For picture in picture, where a fade would play out in a thumbnail nobody is touching. */
    fun hideNow() {
        container.removeCallbacks(hide)
        container.removeCallbacks(tick)
        container.animate().cancel()
        container.alpha = 0f
        container.visibility = View.GONE
        onVisibility(false)
    }

    /**
     * Pausing pins the row up, the way every player does it: the viewer stopped to look at
     * something, and the something includes the clock and the buttons. Play starts the timer.
     */
    fun onPlayingChanged() {
        renderPlayPause()
        poke()
    }

    /** Buys the row its timeout again. Called by every interaction, however it arrived. */
    private fun poke() {
        container.removeCallbacks(hide)
        if (player()?.isPlaying == true) container.postDelayed(hide, TIMEOUT_MS)
    }

    private fun renderPlayPause() {
        val playing = player()?.isPlaying == true
        playPause.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        playPause.contentDescription = if (playing) "Pause" else "Play"
    }

    private fun renderProgress() {
        val exo = player() ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        timeBar.setDuration(duration)
        timeBar.setBufferedPosition(exo.bufferedPosition)
        if (!scrubbing) {
            timeBar.setPosition(exo.currentPosition)
            renderClock(exo.currentPosition)
        }
        renderWallClock()
        renderPlayPause()
    }

    private fun renderClock(position: Long) {
        val exo = player() ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        time.text = "${StreamStats.formatClock(position)} / ${StreamStats.formatClock(duration)}"
    }

    private fun renderWallClock() {
        clock.text = timeOfDay(System.currentTimeMillis())
    }

    private fun timeOfDay(atMillis: Long): String =
        android.text.format.DateFormat.getTimeFormat(container.context)
            .format(java.util.Date(atMillis))

    private companion object {
        /** Nuvio's figure, and Netflix's: long enough to read as calm, short enough to feel live. */
        const val FADE_MS = 200L

        /** Long enough to read the row and reach for something on it, short enough to get out. */
        const val TIMEOUT_MS = 3_500L

        /** Twice a second: faster than the eye needs on a scrub bar this size. */
        const val TICK_MS = 500L
    }
}

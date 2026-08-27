package com.tmplayer.player

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
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

        // A press of D-pad left or right on the bar steps the position by this much, which keeps
        // remote scrubbing in step with the seek buttons rather than Media3's twentieth-of-a-film.
        timeBar.setKeyTimeIncrement(Skip.FORWARD_MS)
        timeBar.onFocusChangeListener = View.OnFocusChangeListener { _, _ -> poke() }
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
            // and play or pause is what a thumb on a remote is nearly always reaching for.
            if (isTv) playPause.requestFocus()
        }
        poke()
    }

    /**
     * Puts the D-pad back on the buttons, for when a picker above the row closes and focus falls
     * wherever the system drops it: left alone it lands on the scrub bar, and the next press
     * seeks instead of walking the row.
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
        val counter = "${StreamStats.formatClock(position)} / ${StreamStats.formatClock(duration)}"
        // What the position means for the evening. Scrubbing reads it against the scrubbed-to
        // position, which is exactly the question a scrub is asking: "if I start here, when am
        // I done". The playback speed is honoured; watching at 1.5x ends earlier on the clock.
        val speed = exo.playbackParameters.speed.takeIf { it > 0f } ?: 1f
        time.text = if (duration > 0 && position <= duration) {
            val endsAt = System.currentTimeMillis() + ((duration - position) / speed).toLong()
            "$counter  ·  ends ${timeOfDay(endsAt)}"
        } else {
            counter
        }
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

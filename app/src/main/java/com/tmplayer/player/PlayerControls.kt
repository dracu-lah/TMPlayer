package com.tmplayer.player

import android.graphics.Color
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.tmplayer.R
import com.tmplayer.data.MediaItem
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator

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
 * A phone shows more of the layout than a television does: a top bar with the way back and an
 * overflow menu, a centre cluster carrying the transport (previous, back, play or pause, forward,
 * next), and an elapsed and total line over the bar. With the transport moved there, the bottom row
 * keeps five buttons, which fit a phone held upright without scrolling; lock and picture in
 * picture join them when the row is wide enough. A television shows none of the phone pieces and
 * keeps one row, but without the transport: the remote already plays, pauses and jumps, and the
 * episode steps are its media keys and lines in More. What a television keeps of the centre is
 * the play disc as a cue rather than a button, drawn only while the video is paused.
 *
 * The activity stays the owner of every action. This class decides nothing about playback; it
 * raises, lowers and repaints the furniture, and forwards each press to the lambda wired for it.
 */
@OptIn(UnstableApi::class)
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
    /**
     * The phone's top bar back arrow and lock button; the overflow (the phone's top bar, the end of
     * the television's row) and picture in picture, on both.
     */
    private val onBack: () -> Unit = {},
    private val onMore: (anchor: View) -> Unit = {},
    private val onLock: () -> Unit = {},
    private val onPictureInPicture: () -> Unit = {},
    /** The total was flipped between total and remaining: a tap on a phone, OK on a television. */
    private val onRemainingToggled: (Boolean) -> Unit = {},
) {

    private val container: View = root.findViewById(R.id.player_controls)
    private val title: TextView = root.findViewById(R.id.controls_title)
    private val subtitle: TextView = root.findViewById(R.id.controls_subtitle)
    private val timeBar: DefaultTimeBar = root.findViewById(R.id.controls_timebar)
    private val clock: TextView = root.findViewById(R.id.controls_clock)
    private val playPause: ImageButton = root.findViewById(R.id.control_play_pause)
    private val previous: ImageButton = root.findViewById(R.id.control_previous)
    private val next: ImageButton = root.findViewById(R.id.control_next)
    private val rotate: ImageButton = root.findViewById(R.id.control_rotate)
    private val time: TextView get() = timeText

    // The phone's pieces. Present in the layout on every device, shown only on a phone.
    private val timeText: TextView = root.findViewById(R.id.controls_time)
    private val tint: View = root.findViewById(R.id.controls_tint)
    private val topBar: View = root.findViewById(R.id.controls_topbar)
    private val topTitle: TextView = root.findViewById(R.id.topbar_title)
    private val topSubtitle: TextView = root.findViewById(R.id.topbar_subtitle)
    private val center: View = root.findViewById(R.id.controls_center)
    private val centerPrevious: ImageButton = root.findViewById(R.id.center_previous)
    private val centerNext: ImageButton = root.findViewById(R.id.center_next)
    private val centerPlay: View = root.findViewById(R.id.center_play_pause)
    private val centerIcon: PlayPauseIcon = root.findViewById(R.id.center_play_icon)
    private val centerBuffering: View = root.findViewById(R.id.center_buffering)
    private val times: View = root.findViewById(R.id.controls_times)
    private val timesPosition: TextView = root.findViewById(R.id.times_position)
    private val timesDuration: TextView = root.findViewById(R.id.times_duration)
    private val cluster: View = root.findViewById(R.id.controls_cluster)
    private val lock: View = root.findViewById(R.id.control_lock)
    private val pip: View = root.findViewById(R.id.control_pip)

    /** The phone's times line shows what is left rather than the total. */
    var showRemaining = false
        set(value) {
            field = value
            if (visible) renderProgress()
        }

    /** How long the row stays up while playing; zero keeps it up until tapped away. */
    var timeoutMs = TIMEOUT_MS

    /** True while buffering mid-play, which the phone draws as a spinner in the play disc. */
    private var buffering = false

    /** True once the centre glyph has been drawn once, so the first state is snapped, not morphed. */
    private var centerDrawn = false

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
        describeFromCatalog(root)
        if (!isTv) setUpPhone(root) else setUpTv(root)

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

    /**
     * The phone's layout: the transport moves to the middle, the name to the top, and the bottom row
     * is left with the five buttons that fit an upright phone.
     */
    private fun setUpPhone(root: View) {
        listOf(tint, topBar, center, times).forEach { it.visibility = View.VISIBLE }
        listOf(
            playPause,
            root.findViewById<View>(R.id.control_rewind),
            root.findViewById<View>(R.id.control_forward),
            previous,
            next,
            timeText,
            clock,
            title,
            subtitle,
        ).forEach { it.visibility = View.GONE }

        wire(centerPlay) { onTogglePlay(); renderPlayPause(animate = true) }
        wire(root.findViewById(R.id.center_rewind)) { onSkip(-skipBackMs) }
        wire(root.findViewById(R.id.center_forward)) { onSkip(skipForwardMs) }
        wire(root.findViewById(R.id.control_back)) { onBack() }
        val more = root.findViewById<View>(R.id.control_more)
        wire(more) { onMore(more) }
        wire(lock) { onLock() }
        wire(pip) { onPictureInPicture() }
        timesDuration.setOnClickListener {
            showRemaining = !showRemaining
            onRemainingToggled(showRemaining)
            poke()
        }

        // Lock and picture in picture earn a seat on the row only where it is wide enough: a
        // phone held sideways. Measured on the cluster itself, never on the window, because some
        // phones report a portrait window while drawing sideways.
        val wideEnough = (WIDE_ROW_DP * root.resources.displayMetrics.density).toInt()
        cluster.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val wide = view.width >= wideEnough
            val want = if (wide) View.VISIBLE else View.GONE
            if (lock.visibility != want) {
                view.post {
                    lock.visibility = want
                    pip.visibility = if (wide && pictureInPictureAvailable) View.VISIBLE else View.GONE
                }
            }
        }

        // A long press names the button, the way every phone app's icons do. The names already
        // exist for TalkBack, so the tooltip is the same text; [describe] keeps the two together
        // when a label changes later.
        nameButtons(root)
    }

    /**
     * The television's row: no transport, since the remote's own keys play, pause and jump and a
     * button for each was a second way to do what a thumb never needed. More goes at the end,
     * picture in picture where the device has it, and the time readout becomes something OK can
     * land on and press, which flips the total to the time left the way a tap on the phone's
     * total does.
     *
     * The centre disc stays, stripped to the glyph: not focusable, not pressable, shown only while
     * paused. With no play button on the row it is the one thing on screen that says paused.
     */
    private fun setUpTv(root: View) {
        listOf(
            playPause,
            root.findViewById<View>(R.id.control_rewind),
            root.findViewById<View>(R.id.control_forward),
            previous,
            next,
            centerPrevious,
            root.findViewById<View>(R.id.center_rewind),
            root.findViewById<View>(R.id.center_forward),
            centerNext,
        ).forEach { it.visibility = View.GONE }
        centerPlay.isFocusable = false
        centerPlay.isClickable = false
        centerPlay.contentDescription = L.playerPaused
        // Down from the bar lands on the first button the row still has; the layout's own pointer
        // names the play button, which a television no longer shows.
        timeBar.nextFocusDownId = R.id.control_subtitles
        val more = root.findViewById<View>(R.id.control_tv_more)
        more.visibility = View.VISIBLE
        wire(more) { onMore(more) }
        wire(pip) { onPictureInPicture() }
        timeText.isFocusable = true
        timeText.isClickable = true
        timeText.setBackgroundResource(R.drawable.bg_timebar_focus)
        wire(timeText) {
            showRemaining = !showRemaining
            onRemainingToggled(showRemaining)
        }
    }

    private fun nameButtons(view: View) {
        if (view.isClickable && !view.contentDescription.isNullOrEmpty()) {
            view.tooltipText = view.contentDescription
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) nameButtons(view.getChildAt(i))
        }
    }

    /**
     * A button's name: read aloud by TalkBack and, on a phone, shown when it is long pressed. Not
     * on a television, where a held OK on a focused button would otherwise pop a tooltip.
     */
    /**
     * The layout's buttons named in the UI language. The XML keeps English for the layout preview;
     * these replace it before anything is drawn. Play, pause, the skips and the time are named
     * again as they change.
     */
    private fun describeFromCatalog(root: View) {
        val names = listOf(
            R.id.control_back to L.commonBack,
            R.id.control_more to L.playerMoreOptions,
            R.id.center_previous to L.playerPreviousEpisode,
            R.id.center_rewind to L.playerBackSeconds(Skip.BACK_MS / 1000),
            R.id.center_play_pause to L.commonPause,
            R.id.center_forward to L.playerForwardSeconds(Skip.FORWARD_MS / 1000),
            R.id.center_next to L.playerNextEpisode,
            R.id.times_duration to L.playerShowTimeRemaining,
            R.id.control_play_pause to L.commonPause,
            R.id.control_rewind to L.playerBackSeconds(Skip.BACK_MS / 1000),
            R.id.control_forward to L.playerForwardSeconds(Skip.FORWARD_MS / 1000),
            R.id.control_previous to L.playerPreviousEpisode,
            R.id.control_next to L.playerNextEpisode,
            R.id.control_subtitles to L.tracksSubtitles,
            R.id.control_audio to L.playerAudio,
            R.id.control_speed to L.playerPlaybackSpeed,
            R.id.control_scale to L.playerPictureShape,
            R.id.control_rotate to L.playerScreenOrientation,
            R.id.control_lock to L.playerLockScreen,
            R.id.control_pip to L.playerPictureInPicture,
            R.id.control_tv_more to L.commonMore,
        )
        for ((id, name) in names) root.findViewById<View>(id)?.contentDescription = name
    }

    private fun describe(view: View?, text: String) {
        if (view == null || view.contentDescription == text) return
        view.contentDescription = text
        if (!isTv) view.tooltipText = text
    }

    /**
     * Whether this device can do picture in picture at all; the PiP button is hidden if not. A
     * television's row has no width rule, so there the answer alone decides.
     */
    var pictureInPictureAvailable = true
        set(value) {
            field = value
            if (isTv) pip.visibility = if (value) View.VISIBLE else View.GONE
        }

    /** What the jump buttons say and do. The phone's follow the double tap setting. */
    private var skipBackMs = Skip.BACK_MS
    private var skipForwardMs = Skip.FORWARD_MS

    /**
     * Sets the jump the buttons make and the figure drawn inside their arrows. The television's row
     * jumps by [Skip], back five and forward ten; its glyphs used to say ten both ways.
     */
    fun setSkip(backMs: Long, forwardMs: Long) {
        skipBackMs = backMs
        skipForwardMs = forwardMs
        val root = container
        fun label(id: Int, ms: Long) { root.findViewById<TextView>(id)?.text = Translator.messages.formatter.number(ms / 1000) }
        label(R.id.control_rewind_label, backMs)
        label(R.id.control_forward_label, forwardMs)
        label(R.id.center_rewind_label, backMs)
        label(R.id.center_forward_label, forwardMs)
        describe(root.findViewById(R.id.control_rewind), L.playerBackSeconds(backMs / 1000))
        describe(root.findViewById(R.id.control_forward), L.playerForwardSeconds(forwardMs / 1000))
        describe(root.findViewById(R.id.center_rewind), L.playerBackSeconds(backMs / 1000))
        describe(root.findViewById(R.id.center_forward), L.playerForwardSeconds(forwardMs / 1000))
    }

    /** Mid-play buffering: the phone swaps the play glyph for a spinner in the same disc. */
    fun setBuffering(value: Boolean) {
        buffering = value
        if (isTv) return
        centerBuffering.visibility = if (value) View.VISIBLE else View.GONE
        centerIcon.visibility = if (value) View.INVISIBLE else View.VISIBLE
    }

    /**
     * True when a touch at these window coordinates lands on something the controls own, so the
     * picture's gestures should leave it alone. Only meaningful while the row is up.
     */
    fun isOnChrome(rawX: Float, rawY: Float): Boolean {
        if (!visible) return false
        val views = if (isTv) listOf(cluster) else listOf(topBar, center, cluster)
        val at = IntArray(2)
        return views.any { view ->
            if (view.visibility != View.VISIBLE) return@any false
            view.getLocationInWindow(at)
            rawX >= at[0] && rawX < at[0] + view.width && rawY >= at[1] && rawY < at[1] + view.height
        }
    }

    fun setTitle(name: String, detail: String) {
        val tv = isTv
        topTitle.text = name
        topSubtitle.text = detail
        topSubtitle.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        if (!tv) return
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
        describe(previous, previousLabel)
        previous.setOnClickListener {
            previousEpisode?.let(onPlayEpisode)
        }
        next.visibility = if (nextEpisode != null) View.VISIBLE else View.GONE
        describe(next, nextLabel)
        next.setOnClickListener {
            nextEpisode?.let(onPlayEpisode)
        }
        if (isTv) {
            // The remote's media keys and the More menu carry the steps; the row has no seat for
            // them.
            previous.visibility = View.GONE
            next.visibility = View.GONE
            return
        }
        // The phone's transport lives in the centre; the row's two copies stay hidden. Invisible
        // rather than gone keeps the play disc dead centre whether or not there are neighbours.
        previous.visibility = View.GONE
        next.visibility = View.GONE
        centerPrevious.visibility = if (previousEpisode != null) View.VISIBLE else View.INVISIBLE
        describe(centerPrevious, previousLabel)
        centerPrevious.setOnClickListener { previousEpisode?.let(onPlayEpisode) }
        centerNext.visibility = if (nextEpisode != null) View.VISIBLE else View.INVISIBLE
        describe(centerNext, nextLabel)
        centerNext.setOnClickListener { nextEpisode?.let(onPlayEpisode) }
    }

    /** The orientation button carries its current state, drawn by the activity that owns it. */
    fun setOrientationIcon(resId: Int, label: String) {
        rotate.setImageResource(resId)
        describe(rotate, label)
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
     * seeks instead of walking the row. The first button is the deliberate choice here, not the
     * bar: someone leaving a picker was working the buttons, and is put back among them.
     */
    fun focusRow() {
        if (!isTv || !visible) return
        container.findViewById<View>(R.id.control_subtitles).requestFocus()
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
        renderPlayPause(animate = true)
        poke()
    }

    /** Buys the row its timeout again. Called by every interaction, however it arrived. */
    fun poke() {
        container.removeCallbacks(hide)
        if (player()?.isPlaying == true && timeoutMs > 0) container.postDelayed(hide, timeoutMs)
    }

    /**
     * The glyph follows the player's intent rather than whether frames are moving, so a video
     * stalled on the network still shows pause: the viewer asked for it to play, and a tap on the
     * disc should stop that, not start it.
     */
    private fun renderPlayPause(animate: Boolean = false) {
        val exo = player()
        val playing = exo?.isPlaying == true || (exo?.playWhenReady == true && buffering)
        playPause.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        describe(playPause, if (playing) L.commonPause else L.commonPlay)
        if (isTv) {
            renderPausedCue(paused = exo != null && !exo.playWhenReady, animate = animate)
            return
        }
        centerIcon.setShowsPlay(!playing, animate = animate && centerDrawn && visible)
        centerDrawn = true
        describe(centerPlay, if (playing) L.commonPause else L.commonPlay)
    }

    /** True while the television's disc is folding back into play on its way out. */
    private var cueLeaving = false

    /**
     * The television's centre disc, which shows state where the phone's shows the action: the
     * pause bars while paused. Read from the player's intent, so a stall mid-play is not a pause.
     * Pausing folds the triangle into the bars, resuming folds them back and fades the disc, so
     * the change itself is the feedback; a row raised over a video already paused just shows the
     * bars.
     */
    private fun renderPausedCue(paused: Boolean, animate: Boolean) {
        val morph = animate && visible
        if (paused) {
            if (center.visibility != View.VISIBLE || cueLeaving) {
                center.animate().cancel()
                cueLeaving = false
                center.alpha = 1f
                if (center.visibility != View.VISIBLE) centerIcon.setShowsPlay(true, animate = false)
                center.visibility = View.VISIBLE
            }
            centerIcon.setShowsPlay(false, animate = morph)
            return
        }
        if (center.visibility != View.VISIBLE || cueLeaving) return
        if (!morph) {
            center.visibility = View.GONE
            return
        }
        cueLeaving = true
        centerIcon.setShowsPlay(true, animate = true)
        center.animate().alpha(0f).setStartDelay(CUE_HOLD_MS).setDuration(FADE_MS)
            .withEndAction {
                cueLeaving = false
                center.visibility = View.GONE
                center.alpha = 1f
            }
            .start()
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
        if (isTv) {
            val end = if (showRemaining && duration > 0) {
                "-" + Translator.messages.formatter.clock((duration - position).coerceAtLeast(0))
            } else {
                Translator.messages.formatter.clock(duration)
            }
            time.text = "${Translator.messages.formatter.clock(position)} / $end"
            return
        }
        timesPosition.text = Translator.messages.formatter.clock(position)
        timesDuration.text = if (showRemaining && duration > 0) {
            "-" + Translator.messages.formatter.clock((duration - position).coerceAtLeast(0))
        } else {
            Translator.messages.formatter.clock(duration)
        }
        describe(timesDuration, if (showRemaining) L.playerShowTotalLength else L.playerShowTimeRemaining)
    }

    /**
     * The television's clock. A phone has none of its own: the status bar comes down with the
     * controls and already shows the time, and a second clock beside it was just noise.
     */
    private fun renderWallClock() {
        if (isTv) clock.text = timeOfDay(System.currentTimeMillis())
    }

    private fun timeOfDay(atMillis: Long): String =
        android.text.format.DateFormat.getTimeFormat(container.context)
            .format(java.util.Date(atMillis))

    private companion object {
        /** Nuvio's figure, and Netflix's: long enough to read as calm, short enough to feel live. */
        const val FADE_MS = 200L

        /** Long enough to read the row and reach for something on it, short enough to get out. */
        const val TIMEOUT_MS = 3_500L

        /** A row at least this wide (a phone held sideways) also seats lock and PiP. */
        const val WIDE_ROW_DP = 560

        /** How long the television's disc holds the play triangle before it fades on a resume. */
        const val CUE_HOLD_MS = 500L

        /** Twice a second: faster than the eye needs on a scrub bar this size. */
        const val TICK_MS = 500L
    }
}

/** A view's layout direction for a language that is, or is not, written right to left. */
internal fun layoutDirectionFor(rtl: Boolean): Int =
    if (rtl) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR

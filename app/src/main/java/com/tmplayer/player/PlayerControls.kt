package com.tmplayer.player

import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.tmplayer.R
import com.tmplayer.data.MediaItem
import com.tmplayer.data.recommendedControlsTimeout
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.Haptic
import com.tmplayer.ui.components.haptic

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
 * the buttons one press Down away. On a TV the bar steps 10 s a press, the bare picture's jump; held,
 * it ramps up through larger steps ([FineTimeBar]). The bar folds a burst of presses into one
 * committed seek, which matters here more than in most
 * players: every committed seek moves TDLib's streaming download window, so ten presses landing
 * as one seek is nine downloads not restarted. [okOnTimeBar] gives OK on the focused bar the
 * Netflix meaning, play or pause, unless a scrub is mid-flight, in which case the bar itself
 * takes the press and commits.
 *
 * A phone shows more of the layout than a television does: a top bar with the way back and an
 * overflow menu, a centre cluster carrying the transport (previous episode, play or pause, next
 * episode), and an elapsed and total line over the bar. The bottom row keeps four buttons:
 * subtitles, audio, speed and rotate, and given the room (held sideways, a tablet) picture shape
 * after speed and the lock at the end ([fitRow]). The jumps are the double tap and the keys; picture
 * in picture, and whatever the row has no room for, sit in the overflow. A television shows none
 * of the phone pieces and keeps one row: subtitles, audio, speed, picture shape and More at the end. Play, pause, the jumps
 * and the episode steps are the remote's keys. What a television keeps of the centre is the play
 * disc as a cue rather than a button, drawn only while the video is paused.
 *
 * Both rows end in Episodes, on a video that belongs to a series: the episode list with the
 * previous and next steps at its top ([PlayerEpisodes]).
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
    private val onPickSubtitles: () -> Unit,
    private val onPickAudio: () -> Unit,
    private val onPickSpeed: () -> Unit,
    private val onCycleOrientation: () -> Unit,
    private val onPlayEpisode: (MediaItem) -> Unit,
    /** The phone's top bar back arrow, and the overflow (the phone's top bar, the end of the television's row). */
    private val onBack: () -> Unit = {},
    private val onMore: (anchor: View) -> Unit = {},
    /** The total was flipped between total and remaining: a tap on a phone, OK on a television. */
    private val onRemainingToggled: (Boolean) -> Unit = {},
    /** The row's Episodes button: opens the episode list. */
    private val onEpisodes: () -> Unit = {},
    /** The row's copy button: opens the list of this video's copies. */
    private val onCopies: () -> Unit = {},
    /** The row's picture shape button: the next of Fit, Crop and Stretch. */
    private val onCycleShape: () -> Unit = {},
    /** The phone row's lock, shown while the row has room for it. */
    private val onLock: () -> Unit = {},
    /**
     * A tap on the phone's speed button: swaps between the last two speeds. Null, or returning
     * false for nothing to swap to, opens the list as before; a long press always opens it.
     */
    private val onQuickSpeed: (() -> Boolean)? = null,
    /** The back and forward buttons beside play ([Skip.BUTTON_MS] each way): true for forward. */
    private val onSeekButton: (forward: Boolean) -> Unit = {},
    /** Whether this opening of the player says the words under the six icons ([ControlLabels]). */
    showLabels: Boolean = false,
) {

    private val container: View = root.findViewById(R.id.player_controls)
    private val title: TextView = root.findViewById(R.id.controls_title)
    private val subtitle: TextView = root.findViewById(R.id.controls_subtitle)
    private val timeBar: DefaultTimeBar = root.findViewById(R.id.controls_timebar)
    private val clock: TextView = root.findViewById(R.id.controls_clock)
    private val rotate: ImageButton = root.findViewById(R.id.control_rotate)
    private val shapeButton: ImageButton = root.findViewById(R.id.control_shape)

    private val lockButton: ImageButton = root.findViewById(R.id.control_lock)

    /** The lock in the phone's top bar, beside cast and More: shown while the row has no room for its own. */
    private val topLockButton: ImageButton = root.findViewById(R.id.control_lock_top)

    /**
     * Back and forward beside play, on the phone and the television alike: the same buttons for a
     * thumb and a remote (a remote's Left and Right do the same jump over the bare picture, a
     * touchscreen's double tap too, but nobody has to know that).
     */
    private val seekBack: ImageButton = root.findViewById(R.id.center_seek_back)
    private val seekForward: ImageButton = root.findViewById(R.id.center_seek_forward)

    /** Each labelled button's word, in the order of [labelledButtons]. */
    private val labelViews: Map<View, TextView> = mapOf(
        root.findViewById<View>(R.id.control_subtitles) to root.findViewById<TextView>(R.id.label_subtitles),
        root.findViewById<View>(R.id.control_audio) to root.findViewById(R.id.label_audio),
        root.findViewById<View>(R.id.control_speed) to root.findViewById(R.id.label_speed),
        root.findViewById<View>(R.id.control_shape) to root.findViewById(R.id.label_shape),
        root.findViewById<View>(R.id.control_rotate) to root.findViewById(R.id.label_rotate),
        root.findViewById<View>(R.id.control_lock) to root.findViewById(R.id.label_lock),
    )

    /** The viewer's haptics setting (Settings, Playback gestures); the activity sets it with the other touch settings. */
    var haptics = true

    /**
     * One light tick for a press on the row, the seek buttons or play (WP-35: every control answers
     * its press). Never on a television, and only when the setting is on. A seam so a test can count them.
     */
    internal var onTick: (View) -> Unit = { it.haptic(Haptic.Tick, haptics) }

    /** Whether the words under the icons show: the first few openings of the player. */
    var labelsShown = false
        private set

    /** The slot a labelled button sits in with its word: what the row shows and hides. */
    private fun slotOf(button: View): View = button.parent as? View ?: button
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
    private val centerPreviousCaption: TextView = root.findViewById(R.id.center_previous_caption)
    private val centerNextCaption: TextView = root.findViewById(R.id.center_next_caption)
    private val centerPlay: View = root.findViewById(R.id.center_play_pause)
    private val centerIcon: PlayPauseIcon = root.findViewById(R.id.center_play_icon)
    private val centerBuffering: View = root.findViewById(R.id.center_buffering)
    private val times: View = root.findViewById(R.id.controls_times)
    private val timesPosition: TextView = root.findViewById(R.id.times_position)
    private val timesDuration: TextView = root.findViewById(R.id.times_duration)
    private val cluster: View = root.findViewById(R.id.controls_cluster)

    /** The row's buttons, without the bar and the readout. */
    private val buttons: View = root.findViewById(R.id.controls_buttons)
    private val episodesButton: TextView = root.findViewById(R.id.control_episodes)
    private val copyButton: TextView = root.findViewById(R.id.control_copy)
    private val speedButton: ImageButton = root.findViewById(R.id.control_speed)
    private val speedIcon = speedButton.drawable
    private val speedPadding = speedButton.paddingLeft

    /** The television's Next episode button; shown only while there is a next episode. */
    private val nextEpisodeButton: View = root.findViewById(R.id.control_tv_next_episode)
    private val previousEpisodeButton: View = root.findViewById(R.id.control_tv_previous_episode)

    /** The next episode the row's button plays, or null. */
    private var nextEpisodeToPlay: MediaItem? = null
    private var previousEpisodeToPlay: MediaItem? = null

    /**
     * The row button the remote was last on, so a sheet or picker that closes puts it back on that
     * button and not on the first one ([focusRow]). Null on a fresh appearance of the row.
     */
    private var lastRowButton: View? = null

    private fun isRowButton(view: View): Boolean =
        view.parent === buttons || view.parent?.parent === buttons || view === episodesButton || view === copyButton

    /** The scroll view the round buttons sit in, and the row that holds it beside the pills. */
    private val buttonsScroll: View = buttons.parent as View
    private val row: ViewGroup = buttonsScroll.parent as ViewGroup

    // A pill's padding and gap with its words, as the layout gives them (the copy's are the same).
    private val pillLeft = episodesButton.paddingLeft
    private val pillRight = episodesButton.paddingRight
    private val pillGap = episodesButton.compoundDrawablePadding

    /** The copy button's words ("1080p"), kept while the row shows its glyph alone. */
    private var copyLabel = ""

    /** The phone's times line shows what is left rather than the total. */
    var showRemaining = false
        set(value) {
            field = value
            if (visible) renderProgress()
        }

    /**
     * The A-B repeat marked so far, or null. It shows twice, both small: its marks on the bar, as
     * the desktop draws them, and "A-B" (or "A" while only the start is set) after the elapsed
     * time. Nothing on the button rows, which CP37 cleared on purpose.
     */
    var loop: AbLoop? = null
        set(value) {
            if (field == value) return
            field = value
            renderMarks()
            if (visible) renderProgress()
        }

    /**
     * Whether the video can be sought in. Off for a file with no seek index, where a seek would
     * only start it again from the beginning: the bar still shows the way through, but takes no
     * drag or key.
     */
    var seekable: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            timeBar.isEnabled = value
        }

    /**
     * The file's chapters ([MkvChapters]), or none. Their starts cut the bar into its parts, as
     * the desktop's bar and YouTube's are cut, and the chapter playing (or, during a scrub, the
     * one the scrubber is over) is named after the time.
     */
    var chapters: List<Chapter> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            renderMarks()
            if (visible) renderProgress()
        }

    /** The length the marks were last placed for: the chapter cuts wait for a known one. */
    private var marksFor = -1L

    /**
     * Media3's bar has one kind of mark, the ad marker, drawn in one of two colours by whether it
     * is "played". The repeat's two marks are the unplayed kind, in amber; the chapter cuts are
     * the played kind, drawn in the bar's dark gap colour, so the two never read as one another.
     */
    private fun renderMarks() {
        val duration = player()?.duration?.takeIf { it > 0 } ?: 0L
        marksFor = duration
        val loopMarks = listOfNotNull(loop?.startMs, loop?.endMs)
        val cuts = if (duration > 0) Chapters.marks(chapters, duration) else emptyList()
        val times = (loopMarks + cuts).toLongArray()
        val kinds = BooleanArray(times.size) { it >= loopMarks.size }
        timeBar.setAdGroupTimesMs(times, kinds, times.size)
    }

    /** How long the row stays up while playing; zero keeps it up until tapped away. */
    var timeoutMs = TIMEOUT_MS

    /**
     * True while a menu or sheet opened from the row is up: the overflow, a track picker, the
     * television's More menu. The row stays while it is, whatever the timeout says, and the clock
     * starts again once it closes. Hiding underneath an open menu left it pointing at nothing.
     */
    var held = false
        set(value) {
            if (field == value) return
            field = value
            poke()
        }

    /** True while buffering mid-play, which the phone draws as a spinner in the play disc. */
    private var buffering = false

    /** True once the centre glyph has been drawn once, so the first state is snapped, not morphed. */
    private var centerDrawn = false

    private val accent = ContextCompat.getColor(root.context, R.color.accent)

    /**
     * Whether the row is up, as the viewer and the activity's touch handling understand it.
     * Not the view's visibility: the row stays VISIBLE through its fade out, and a tap in that
     * fifth of a second was swallowed, the activity already treating the row as down while
     * [show] still thought it was up and did nothing.
     */
    val visible: Boolean get() = shown

    private var shown = container.visibility == View.VISIBLE

    /** True while a finger or the D-pad is on the bar; the clock leaves the bar alone then. */
    private var scrubbing = false

    /** The viewer is dragging the scrub bar now. */
    val isScrubbing: Boolean get() = scrubbing

    private val hide = Runnable { hideAnimated() }

    /** True once a stall has lasted [BUFFERING_SPOKEN_MS] and the play disc says so to a screen reader. */
    private var bufferingSpoken = false
    private val speakBuffering = Runnable {
        if (!buffering || bufferingSpoken) return@Runnable
        bufferingSpoken = true
        // The disc is a polite live region (its layout), so the new state is spoken once.
        renderPlayPause()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            renderProgress()
            container.postDelayed(this, TICK_MS)
        }
    }

    /**
     * The television's tooltip: the name of the icon button the remote is on, in the player's chip
     * over the button, as the rail's folded icons say theirs (a remote has no long press to ask
     * with, and a phone's buttons have their long press tooltips from [nameButtons]). It sits in
     * the controls' own frame, so it fades with them. Declared before [init], which wires it.
     */
    private val focusTip: TextView? = if (!isTv) null else TextView(root.context).apply {
        val density = resources.displayMetrics.density
        background = ContextCompat.getDrawable(context, R.drawable.bg_player_chip)
        setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        setTextColor(Color.WHITE)
        textSize = 15f
        maxLines = 1
        visibility = View.GONE
        // The button's own description already reaches TalkBack.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        (container as android.view.ViewGroup).addView(
            this,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** The button [focusTip] is naming, or null while it is down. */
    private var tipFor: View? = null

    init {
        wire(root.findViewById(R.id.control_subtitles)) { onPickSubtitles() }
        wire(root.findViewById(R.id.control_audio)) { onPickAudio() }
        wire(speedButton) {
            // A tap swaps between the last two speeds on a phone; a television's OK opens the
            // list (a held OK there is the hand-over to another app).
            if (isTv || onQuickSpeed?.invoke() != true) onPickSpeed()
        }
        if (!isTv) {
            speedButton.setOnLongClickListener {
                onPickSpeed()
                poke()
                true
            }
        }
        wire(rotate) { onCycleOrientation() }
        wire(shapeButton) {
            onCycleShape()
            holdFocus(shapeButton)
        }
        wire(lockButton) { onLock() }
        wire(topLockButton) { onLock() }
        wire(episodesButton) { onEpisodes() }
        wire(copyButton) { onCopies() }
        wire(nextEpisodeButton) { nextEpisodeToPlay?.let(onPlayEpisode) }
        wire(previousEpisodeButton) { previousEpisodeToPlay?.let(onPlayEpisode) }
        episodesButton.text = L.episodesTitle
        if (isTv) {
            container.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
                if (now != null && isRowButton(now)) lastRowButton = now
            }
        }
        slotOf(rotate).visibility = if (isTv) View.GONE else View.VISIBLE
        wire(seekBack) { onSeekButton(false) }
        wire(seekForward) { onSeekButton(true) }
        listOf(R.id.center_seek_back_frame, R.id.center_seek_forward_frame).forEach { root.findViewById<View>(it).visibility = View.VISIBLE }
        // Down from either lands on the bar, as from the play disc.
        seekBack.nextFocusDownId = R.id.controls_timebar
        seekForward.nextFocusDownId = R.id.controls_timebar
        setLabelsShown(showLabels)
        // The scrims on five stops, which their XML stand-ins cannot hold without a seam.
        root.findViewById<View>(R.id.controls_scrim_top).background = ControlsScrim.top()
        root.findViewById<View>(R.id.controls_scrim_bottom).background = ControlsScrim.bottom()
        describeFromCatalog(root)
        setShape(VideoScale.Fit)
        if (!isTv) setUpPhone(root) else setUpTv(root)

        // TalkBack's scroll actions on the bar move by this much, fixed rather than Media3's
        // twentieth of a film, and a phone keyboard strides by it. A TV remote steps 10 s and ramps, one
        // commit a second after the last key ([FineTimeBar.onKeyDown]).
        timeBar.setKeyTimeIncrement(Skip.BAR_MS)
        // A finger away from the bar scrubs finer; the line over it says how. Phone only: a remote
        // steps the bar with its keys, which never reach the fine scrub.
        if (!isTv) (timeBar as? FineTimeBar)?.onRate = ::showScrubRate
        // The remote's 10 s steps and ramp are the television's; a keyboard elsewhere keeps the stride.
        (timeBar as? FineTimeBar)?.remoteSteps = isTv
        // The chapter cuts: see [renderMarks].
        timeBar.setPlayedAdMarkerColor(CHAPTER_CUT)
        // A chapter's name rides after the time: one line, cut short rather than wrapping the row.
        timesPosition.maxLines = 1
        timesPosition.ellipsize = android.text.TextUtils.TruncateAt.END
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
                preview(position)
            }

            override fun onScrubMove(bar: TimeBar, position: Long) {
                renderClock(position)
                preview(position)
            }

            override fun onScrubStop(bar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                hidePreview()
                if (!canceled) player()?.seekTo(position)
                poke()
            }
        })
    }

    // ---- trickplay ---------------------------------------------------------------------------

    /**
     * Where scrub pictures come from: Telegram's storyboard, the video's own frames, both, or
     * null where there is neither (the card then shows its still fallback). See
     * [ScrubPreviewCard] for what the card shows while they look.
     */
    var thumbnails: ScrubThumbnails? = null

    /**
     * The viewer turned scrub pictures off in Settings. A phone then shows no card (its clock
     * under the bar already says the time); a television, whose clock sits apart from the bar,
     * keeps the card's still face with the time on it.
     */
    var previewsOff = !com.tmplayer.data.Trickplay.PICTURES_SHOWN

    /** The video's own thumbnail, for the card's fallback; null draws a plain cover. */
    var previewArt: android.graphics.Bitmap?
        get() = scrubCard.art
        set(value) {
            scrubCard.art = value
        }

    private val scrubCard = ScrubPreviewCard(root as android.view.ViewGroup, isTv) {
        com.tmplayer.ui.theme.Motion.reduced || !com.tmplayer.ui.theme.systemAnimatorsEnabled(container.context)
    }

    /** The card over the bar, for the screenshot fixture and its test. */
    internal val previewCard: View get() = scrubCard.view

    /** The position the card is up for, or -1 with it down. */
    private var previewAt = -1L

    init {
        // A turn of the phone or a window resized mid-scrub moves the bar; the card follows it.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (previewAt >= 0 && scrubCard.isShown) placePreview(previewAt)
        }
    }

    /**
     * Shows the card for [position] over the bar, centred on where the scrubber is: the time at
     * once, the picture when a source has one (see [ScrubPreviewCard]).
     */
    fun preview(position: Long, pictures: Boolean = true) {
        if (previewsOff && !isTv) return
        previewAt = position
        val duration = player()?.duration?.takeIf { it > 0 } ?: 0L
        val label = Translator.messages.formatter.clock(position)
        val source = if (previewsOff || !pictures) null else thumbnails
        scrubCard.show(position, duration, label, source) { scrubbing || previewPinned }
        placePreview(position)
    }

    /**
     * The scan option (WP-79): the row raised in scrub mode at a virtual [position]. The thumb, the
     * clock and the card move there and nothing is sought; the card shows the time only, never a
     * frame (nothing is fetched while a stick scans). [endScan] puts it all away; the activity does
     * the one seek.
     */
    fun scanTo(position: Long) {
        if (!isTv) return
        scrubbing = true
        container.removeCallbacks(hide)
        if (!shown) show()
        timeBar.setPosition(position)
        renderClock(position)
        preview(position, pictures = false)
    }

    /** The scan is over: the card goes, the bar follows the video again, the row gets its usual time. */
    fun endScan() {
        if (!scrubbing) return
        scrubbing = false
        hidePreview()
        poke()
    }

    /** For the screenshot fixture: keep the card up without a scrub in progress. */
    var previewPinned = false

    private fun hidePreview() {
        previewAt = -1L
        scrubCard.hide()
    }

    private fun placePreview(position: Long) {
        val card = scrubCard.view
        val parent = card.parent as? View ?: return
        val duration = player()?.duration?.takeIf { it > 0 } ?: 0L
        val bar = IntArray(2)
        val origin = IntArray(2)
        timeBar.getLocationInWindow(bar)
        parent.getLocationInWindow(origin)
        val width = scrubCard.widthPx
        val height = scrubCard.heightPx
        val density = parent.resources.displayMetrics.density
        val gap = 12 * density
        // Right to left, the bar fills from the right, and so does the scrub point.
        val fraction = ScrubPreviewRules.fraction(position, duration)
        val along = if (timeBar.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f - fraction else fraction
        val centre = bar[0] - origin[0] + timeBar.width * along
        // Kept off the screen's edges and out of the cutout and the system bars, so the card's
        // hairline is whole at both ends of the bar.
        val safe = ViewCompat.getRootWindowInsets(parent)?.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
        )
        // A television keeps it inside the overscan margin the bar itself keeps.
        val edge = (if (isTv) 32 else 8) * density
        card.translationX = ScrubPreviewRules.left(centre, width, parent.width, edge, safe?.left ?: 0, safe?.right ?: 0)
        // On a phone the times sit between the card and the bar; the card clears them rather than
        // landing on the position at either end.
        // On a television the title and its line stand over the bar, and the card clears them too.
        var top = bar[1]
        val above = if (isTv) title else times
        if (above.isShown && (!isTv || title.text.isNotEmpty())) {
            val line = IntArray(2)
            above.getLocationInWindow(line)
            if (line[1] < top) top = line[1]
        }
        val minY = (safe?.top ?: 0) + 8 * density
        card.translationY = (top - origin[1] - height - gap).coerceAtLeast(minY)
    }

    // ---- fine scrubbing ----------------------------------------------------------------------

    /**
     * The line over the bar while a finger drags it: how to scrub finer, then which slower rate
     * the finger is at ([FineScrub]). Above the scrub frame when there is one. Fades in and out,
     * and changes its words with a small pop; with less motion asked for it only appears.
     */
    private val scrubHint = android.widget.TextView(root.context).apply {
        val density = resources.displayMetrics.density
        background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_player_chip)
        setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
        setTextColor(Color.WHITE)
        textSize = 13f
        maxLines = 1
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        (root as android.view.ViewGroup).addView(
            this,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun hintMoves(): Boolean =
        !com.tmplayer.ui.theme.Motion.reduced && com.tmplayer.ui.theme.systemAnimatorsEnabled(container.context)

    /** [rate] the finger is scrubbing at, or null once it lifts. */
    fun showScrubRate(rate: Float?) {
        val hint = scrubHint
        hint.animate().cancel()
        if (rate == null) {
            if (hint.visibility != View.VISIBLE) return
            if (!hintMoves()) {
                hint.visibility = View.GONE
                return
            }
            hint.animate().alpha(0f).setDuration(HINT_FADE_MS).withEndAction { hint.visibility = View.GONE }.start()
            return
        }
        val slowed = FineScrub.hint(rate)
        hint.text = slowed ?: L.playerScrubFiner
        hint.setTextColor(if (slowed != null) accent else Color.WHITE)
        val wasShown = hint.visibility == View.VISIBLE
        hint.visibility = View.VISIBLE
        placeScrubHint()
        if (!hintMoves()) {
            hint.alpha = 1f
            hint.scaleX = 1f
            hint.scaleY = 1f
            return
        }
        if (!wasShown) hint.alpha = 0f
        hint.scaleX = if (wasShown) 0.9f else 1f
        hint.scaleY = hint.scaleX
        hint.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(HINT_FADE_MS).start()
        // Its width is known after the next layout, for the centring.
        hint.post { placeScrubHint() }
    }

    private fun placeScrubHint() {
        val parent = scrubHint.parent as? View ?: return
        val bar = IntArray(2)
        val origin = IntArray(2)
        timeBar.getLocationInWindow(bar)
        parent.getLocationInWindow(origin)
        val density = parent.resources.displayMetrics.density
        scrubHint.translationX = ((parent.width - scrubHint.width) / 2f).coerceAtLeast(0f)
        // Over the card when it is up, else over the bar.
        scrubHint.translationY = if (scrubCard.isShown) {
            scrubCard.view.translationY - scrubHint.height - 8 * density
        } else {
            bar[1] - origin[1] - scrubHint.height - 12 * density
        }
    }

    /**
     * Gives [buttons] the television's [focusTip]: their name over them while the remote is on
     * them, as well as the row's extra time that [wire] gives on focus.
     */
    private fun tipOnFocus(vararg buttons: View) {
        for (button in buttons) {
            button.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                if (focused) {
                    poke()
                    showTip(view)
                } else if (tipFor === view) {
                    hideTip()
                }
            }
        }
    }

    private fun showTip(anchor: View) {
        val tip = focusTip ?: return
        val name = anchor.contentDescription?.toString().orEmpty()
        if (name.isEmpty() || (labelsShown && anchor in labelViews)) return hideTip()
        tipFor = anchor
        tip.text = name
        tip.animate().cancel()
        val wasShown = tip.visibility == View.VISIBLE
        tip.visibility = View.VISIBLE
        placeTip(anchor)
        // Its width is known after the next layout, for the centring.
        tip.post { if (tipFor === anchor) placeTip(anchor) }
        if (!hintMoves()) {
            tip.alpha = 1f
            return
        }
        if (!wasShown) tip.alpha = 0f
        tip.animate().alpha(1f).setDuration(HINT_FADE_MS).start()
    }

    private fun hideTip() {
        val tip = focusTip ?: return
        tipFor = null
        tip.animate().cancel()
        if (tip.visibility != View.VISIBLE) return
        if (!hintMoves()) {
            tip.visibility = View.GONE
            return
        }
        tip.animate().alpha(0f).setDuration(HINT_FADE_MS).withEndAction { if (tipFor == null) tip.visibility = View.GONE }.start()
    }

    /** Centred over [anchor], kept inside the frame. */
    private fun placeTip(anchor: View) {
        val tip = focusTip ?: return
        val at = IntArray(2)
        val origin = IntArray(2)
        anchor.getLocationInWindow(at)
        container.getLocationInWindow(origin)
        val gap = 10 * container.resources.displayMetrics.density
        val maxX = (container.width - tip.width).toFloat().coerceAtLeast(0f)
        tip.translationX = (at[0] - origin[0] + (anchor.width - tip.width) / 2f).coerceIn(0f, maxX)
        tip.translationY = (at[1] - origin[1] - tip.height - gap).coerceAtLeast(0f)
    }

    /** A press does its thing and buys the row more time on screen; so does focus landing. */
    private fun wire(button: View, action: () -> Unit) {
        button.setOnClickListener {
            if (!isTv) onTick(button)
            action()
            poke()
        }
        button.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused) poke()
        }
    }

    /**
     * The phone's layout: the transport moves to the middle, the name to the top, and the bottom row
     * is left with subtitles, audio, speed and rotate, plus picture shape and the lock when wide.
     */
    private fun setUpPhone(root: View) {
        listOf(tint, topBar, center, times).forEach { it.visibility = View.VISIBLE }
        listOf(timeText, title, subtitle).forEach { it.visibility = View.GONE }

        wire(centerPlay) { onTogglePlay(); renderPlayPause(animate = true) }
        wire(root.findViewById(R.id.control_back)) { onBack() }
        val more = root.findViewById<View>(R.id.control_more)
        wire(more) { onMore(more) }
        timesDuration.setOnClickListener {
            showRemaining = !showRemaining
            onRemainingToggled(showRemaining)
            poke()
        }

        // A long press names the button, the way every phone app's icons do. The names already
        // exist for TalkBack, so the tooltip is the same text; [describe] keeps the two together
        // when a label changes later.
        nameButtons(root)

        // The row's extras come and go with its width: the activity keeps the turn itself
        // (configChanges), so the row is measured rather than the configuration read once.
        cluster.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) cluster.post { fitRow() }
        }
        fitRow()
    }

    /**
     * True while the row carries picture shape and the lock. On a television always for picture
     * shape (the lock stays first in More there, where a held OK undoes it); on a phone only when
     * the row is at least [WIDE_ROW_DP] across, which a phone held sideways or a tablet is and a
     * phone held upright is not. The overflow leaves out what the row shows, and offers what it
     * does not.
     */
    val shapeOnRow: Boolean get() = slotOf(shapeButton).visibility == View.VISIBLE
    val lockOnRow: Boolean get() = slotOf(lockButton).visibility == View.VISIBLE

    /** True while the lock sits in the top bar (a phone whose row is too narrow for it). */
    val lockInTopBar: Boolean get() = topLockButton.visibility == View.VISIBLE

    /**
     * Whether the screen orientation button shows (WP-37). Not on a television, and not where Android
     * ignores what an app asks for (a tablet, a window shared with another app): a button that says
     * "Landscape" and turns nothing is worse than none.
     */
    fun setRotateAvailable(available: Boolean) {
        slotOf(rotate).visibility = if (!isTv && available) View.VISIBLE else View.GONE
        row.requestLayout()
    }

    /** False while the round buttons run past the room their scroll view has: the row did not fit. */
    val rowFits: Boolean
        get() = buttons.width <= buttonsScroll.width - buttonsScroll.paddingLeft - buttonsScroll.paddingRight

    /**
     * Fits the phone's row to the room it has, giving up a little more at each step until what is
     * left fits: everything (only when the row is at least [WIDE_ROW_DP] across), then without
     * picture shape and the lock, then Episodes as its glyph alone, then the copy as well, then
     * without speed. Words go before buttons. What leaves the row is still in More (the lock,
     * picture shape and speed always are), and a glyph alone keeps its name for TalkBack and a
     * long press. Upright with a series and a second copy, the pills had cut the round buttons
     * off mid icon (Android at 411 dp, an iPhone with Display Zoom at 320 points, 2026-11-15);
     * the scroll under the buttons is only the last resort now. Before the first layout the
     * width is the screen's, less the row's padding.
     */
    private fun fitRow() {
        if (isTv) {
            slotOf(shapeButton).visibility = View.VISIBLE
            topLockButton.visibility = View.GONE
            return
        }
        val metrics = cluster.resources.displayMetrics
        val width = cluster.width.takeIf { it > 0 } ?: metrics.widthPixels
        val room = width - cluster.paddingLeft - cluster.paddingRight
        val first = if (room / metrics.density >= WIDE_ROW_DP) 0 else 1
        for (step in first..LAST_ROW_STEP) {
            giveUp(step)
            if (step == LAST_ROW_STEP || rowNeeds() <= room) break
        }
        // The lock has one home at a time: the row's, or (when the row had to give it up) the top bar's.
        topLockButton.visibility = if (slotOf(lockButton).visibility == View.VISIBLE) View.GONE else View.VISIBLE
        // Measuring left each part at its natural size: the next pass sizes them for the row.
        row.requestLayout()
    }

    /** The row as [fitRow]'s [step] has it, 0 being everything. */
    private fun giveUp(step: Int) {
        val extras = if (step == 0) View.VISIBLE else View.GONE
        slotOf(shapeButton).visibility = extras
        slotOf(lockButton).visibility = extras
        slotOf(speedButton).visibility = if (step < 4) View.VISIBLE else View.GONE
        pillWords(episodesButton, L.episodesTitle, words = step < 2, spoken = null, alone = L.episodesTitle)
        pillWords(
            copyButton,
            copyLabel,
            words = step < 3,
            spoken = L.playerCopies,
            alone = L.commonWithDetail(L.playerCopies, copyLabel),
        )
    }

    /**
     * A pill with its words, or its glyph alone in a round button's size. [spoken] is the name it
     * has with its words (none: TalkBack reads the words), [alone] the name it has without.
     */
    private fun pillWords(pill: TextView, label: String, words: Boolean, spoken: String?, alone: String) {
        val text = if (words) label else ""
        if (pill.text.toString() != text) pill.text = text
        val side = minOf(pillLeft, pillRight)
        pill.setPadding(if (words) pillLeft else side, pill.paddingTop, if (words) pillRight else side, pill.paddingBottom)
        pill.compoundDrawablePadding = if (words) pillGap else 0
        val name = if (words) spoken else alone
        if (name != null) {
            describe(pill, name)
        } else if (pill.contentDescription != null) {
            pill.contentDescription = null
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) pill.tooltipText = null
        }
    }

    /** The width the row's shown parts take at their natural size, with their margins. */
    private fun rowNeeds(): Int {
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var need = row.paddingLeft + row.paddingRight
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child.visibility == View.GONE) continue
            // The scroll view takes whatever it is given: its buttons say what they need.
            val sized = if (child === buttonsScroll) buttons else child
            sized.measure(free, free)
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            need += sized.measuredWidth + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0)
            if (child === buttonsScroll) need += child.paddingLeft + child.paddingRight
        }
        return need
    }

    /**
     * The television's row: no transport, since the remote's own keys play, pause, jump and step
     * episodes and a button for each was a second way to do what a thumb never needed. Picture shape
     * gets a button, the one setting a sofa changes per video. More goes at the end, holding the
     * rarer actions (the lock, picture in picture, the sleep timer and the rest), and
     * the time readout becomes something OK can land on and press, which flips the total to the time
     * left the way a tap on the phone's total does.
     *
     * The centre disc stays: the one play button, up with the row like the phone's. A pause hands
     * it the remote, so OK resumes, and its glyph turning is the only answer to either.
     */
    private fun setUpTv(root: View) {
        listOf(centerPrevious, centerNext, centerPreviousCaption, centerNextCaption)
            .forEach { it.visibility = View.GONE }
        clock.visibility = View.VISIBLE
        center.visibility = View.VISIBLE
        wire(centerPlay) { onTogglePlay(); renderPlayPause(animate = true) }
        centerPlay.nextFocusDownId = R.id.controls_timebar
        timeBar.nextFocusUpId = R.id.center_play_pause
        // Down from the bar lands on the first button the row has.
        timeBar.nextFocusDownId = R.id.control_subtitles
        val more = root.findViewById<View>(R.id.control_tv_more)
        more.visibility = View.VISIBLE
        // Between speed and More: a press steps the shape, focus stays put for the next press,
        // and the glyph and the answer over the picture say which shape it is on.
        slotOf(shapeButton).visibility = View.VISIBLE
        wire(more) { onMore(more) }
        timeText.isFocusable = true
        timeText.isClickable = true
        // TalkBack (WP-17): a button that says what OK does ([renderClock] names it) and, as its state,
        // where the video stands. The state is worked out when a reader asks, not on every tick.
        timeText.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                val exo = player() ?: return
                val clock = Translator.messages.formatter
                val state = L.playerPositionOf(clock.clock(exo.currentPosition), clock.clock(exo.duration.takeIf { it > 0 } ?: 0L))
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    info.stateDescription = state
                } else {
                    info.contentDescription = "${info.contentDescription}, $state"
                }
            }
        })
        // Focus fills the readout with a white pill and darkens its text, the buttons' own cue in
        // the readout's shape; the bar's faint pill was lost over a bright frame.
        timeText.setBackgroundResource(R.drawable.bg_tv_focus_pill)
        timeText.setTextColor(ContextCompat.getColorStateList(root.context, R.color.player_control_icon))
        wire(timeText) {
            showRemaining = !showRemaining
            onRemainingToggled(showRemaining)
        }
        // The icon buttons say their name over them while the remote is on them. The readout and
        // Episodes are words already.
        tipOnFocus(
            root.findViewById(R.id.control_subtitles),
            root.findViewById(R.id.control_audio),
            speedButton,
            shapeButton,
            previousEpisodeButton,
            nextEpisodeButton,
            more,
            centerPlay,
        )
    }

    private fun nameButtons(view: View) {
        if (view.isClickable && !view.contentDescription.isNullOrEmpty()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) view.tooltipText = view.contentDescription
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
            R.id.control_cast to L.castButton,
            R.id.center_previous to L.playerPreviousEpisode,
            R.id.center_play_pause to L.commonPause,
            R.id.center_next to L.playerNextEpisode,
            R.id.times_duration to L.playerShowTimeRemaining,
            R.id.control_subtitles to L.tracksSubtitles,
            R.id.control_audio to L.playerAudio,
            R.id.control_speed to L.playerPlaybackSpeed,
            R.id.control_rotate to L.playerScreenOrientation,
            R.id.control_lock to L.playerLockScreen,
            R.id.control_lock_top to L.playerLockScreen,
            R.id.control_tv_more to L.commonMore,
            R.id.control_tv_previous_episode to L.playerPreviousEpisode,
            R.id.control_tv_next_episode to L.playerNextEpisode,
            R.id.control_episodes to L.episodesTitle,
            R.id.control_copy to L.playerCopies,
            R.id.center_seek_back to L.playerSkipBackButton(SKIP_SECONDS),
            R.id.center_seek_forward to L.playerSkipForwardButton(SKIP_SECONDS),
        )
        for ((id, name) in names) root.findViewById<View>(id)?.contentDescription = name
        // The figure sits inside the arrow's circle, so it keeps its size whatever the system font is.
        for (id in listOf(R.id.center_seek_back_text, R.id.center_seek_forward_text)) {
            root.findViewById<TextView>(id).apply {
                text = L.messages.formatter.number(SKIP_SECONDS)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (if (isTv) SEEK_FIGURE_TV_DP else SEEK_FIGURE_DP) * resources.displayMetrics.density)
            }
        }
        val words = mapOf(
            R.id.label_subtitles to L.playerSubtitles,
            R.id.label_audio to L.playerLabelSound,
            R.id.label_speed to L.playerSpeed,
            R.id.label_rotate to L.playerLabelRotate,
            R.id.label_lock to L.playerLabelLock,
        )
        for ((id, word) in words) root.findViewById<TextView>(id).text = word
    }

    /**
     * Shows or hides the words under the six icons ([ControlLabels]). Their size follows the system
     * font up to [ControlLabels.MAX_FONT_SCALE]. On a television the focus tip that names a button
     * is left out while the words show: it would say the same thing twice.
     */
    fun setLabelsShown(show: Boolean) {
        labelsShown = show
        val metrics = container.resources.displayMetrics
        val px = ControlLabels.textPx(metrics.density, container.resources.configuration.fontScale, isTv)
        for (word in labelViews.values) {
            word.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
            word.visibility = if (show) View.VISIBLE else View.GONE
        }
        if (show) hideTip()
    }

    private fun describe(view: View?, text: String) {
        if (view == null || view.contentDescription == text) return
        view.contentDescription = text
        if (!isTv && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) view.tooltipText = text
        // Play turning into Pause under the remote: the tip says the new name.
        if (tipFor === view) focusTip?.text = text
    }

    /** Mid-play buffering: the phone swaps the play glyph for a spinner in the same disc. */
    fun setBuffering(value: Boolean) {
        buffering = value
        // A screen reader hears "Buffering" only once a stall lasts half a second (WP-17), on every form factor.
        container.removeCallbacks(speakBuffering)
        if (value) {
            container.postDelayed(speakBuffering, BUFFERING_SPOKEN_MS)
        } else if (bufferingSpoken) {
            bufferingSpoken = false
            renderPlayPause()
        }
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

    /** The line under the title as [setTitle] last wrote it. */
    private var detail = ""

    fun setTitle(name: String, detail: String) {
        val tv = isTv
        this.detail = detail
        topTitle.text = name
        topSubtitle.text = detail
        topSubtitle.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        if (!tv) return
        title.text = name
        title.visibility = if (name.isBlank()) View.GONE else View.VISIBLE
        showDetail(detail)
    }

    private fun showDetail(text: String) {
        subtitle.text = text
        subtitle.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Offers the phone's episode steps once the chat search has answered, and never before: a
     * button that does nothing is worse than no button. A television has Previous and Next buttons
     * on its row (a Google TV or Mi TV remote has no media keys for them) and the remote's keys.
     *
     * [previousLabel] and [nextLabel] are the whole name ("Next: S01E05  ·  The Lighthouse"): the
     * button's description and tooltip. [previousCaption] and [nextCaption] are the short form
     * ("S01E05") the phone writes under its buttons, where the whole name would crowd a phone
     * held upright.
     */
    fun setEpisodes(
        previousEpisode: MediaItem?,
        nextEpisode: MediaItem?,
        previousLabel: String,
        nextLabel: String,
        previousCaption: String = "",
        nextCaption: String = "",
    ) {
        if (isTv) {
            setEpisodeButton(previousEpisodeButton, previousEpisode, previousLabel) { previousEpisodeToPlay = it }
            setEpisodeButton(nextEpisodeButton, nextEpisode, nextLabel) { nextEpisodeToPlay = it }
            return
        }
        // Invisible rather than gone keeps the play disc dead centre whether or not there are
        // neighbours.
        centerPrevious.visibility = if (previousEpisode != null) View.VISIBLE else View.INVISIBLE
        describe(centerPrevious, previousLabel)
        centerPrevious.setOnClickListener { previousEpisode?.let(onPlayEpisode) }
        caption(centerPreviousCaption, previousCaption.takeIf { previousEpisode != null })
        centerNext.visibility = if (nextEpisode != null) View.VISIBLE else View.INVISIBLE
        describe(centerNext, nextLabel)
        centerNext.setOnClickListener { nextEpisode?.let(onPlayEpisode) }
        caption(centerNextCaption, nextCaption.takeIf { nextEpisode != null })
    }

    /**
     * Writes the speed in force on the speed button: the figure ("1.25x") where the icon is while
     * it is not 1x, the icon at 1x. The button's name carries it for TalkBack and the tip.
     */
    fun showSpeed(speed: Float) {
        val label = PlaybackSpeed.label(speed)
        val normal = kotlin.math.abs(speed - PlaybackSpeed.DEFAULT) < 0.001f
        val name = if (normal) L.playerPlaybackSpeed else "${L.playerPlaybackSpeed}: $label"
        describe(speedButton, name)
        if (normal) {
            speedButton.setImageDrawable(speedIcon)
            speedButton.setPadding(speedPadding, speedPadding, speedPadding, speedPadding)
        } else {
            speedButton.setImageDrawable(SpeedLabelDrawable(label))
            val tight = (6 * speedButton.resources.displayMetrics.density).toInt()
            speedButton.setPadding(tight, tight, tight, tight)
        }
    }

    /**
     * The television's Previous or Next episode button: there while the chat has that episode, gone
     * for a film, a first or last episode or a lookup that found nothing. A remote standing on it
     * when it goes is put back on the row rather than dropped.
     */
    private fun setEpisodeButton(button: View, episode: MediaItem?, label: String, keep: (MediaItem?) -> Unit) {
        keep(episode)
        describe(button, label)
        val wanted = if (episode != null) View.VISIBLE else View.GONE
        if (button.visibility == wanted) return
        val hadFocus = button.hasFocus()
        button.visibility = wanted
        if (hadFocus) focusRow()
    }

    /**
     * Shows the Episodes button for a video the chat has placed in a series, and takes it away
     * otherwise: a film has no list to open.
     */
    fun setEpisodesAvailable(available: Boolean) {
        val wanted = if (available) View.VISIBLE else View.GONE
        if (episodesButton.visibility == wanted) return
        // Leaving the button the remote stands on would drop focus on the floor: back to the row.
        val hadFocus = episodesButton.hasFocus()
        episodesButton.visibility = wanted
        if (hadFocus) focusRow()
        fitRow()
    }

    /**
     * Names the copy playing on the copy button ("1080p"), or takes the button away with null: a
     * video with no other copy has nothing to switch to.
     */
    fun setCopy(label: String?) {
        val wanted = if (label.isNullOrBlank()) View.GONE else View.VISIBLE
        copyLabel = label.orEmpty()
        copyButton.text = copyLabel
        if (copyButton.visibility != wanted) {
            val hadFocus = copyButton.hasFocus()
            copyButton.visibility = wanted
            if (hadFocus) focusRow()
        }
        // Another label is another width.
        fitRow()
    }

    /** Puts the remote back on the copy button, for when the list it opened closes. */
    fun focusCopy() {
        if (!isTv || !visible || copyButton.visibility != View.VISIBLE) return
        copyButton.requestFocus()
        poke()
    }

    /** Puts the remote back on the Episodes button, for when the list it opened closes. */
    fun focusEpisodes() {
        if (!isTv || !visible || episodesButton.visibility != View.VISIBLE) return
        episodesButton.requestFocus()
        poke()
    }

    private fun caption(view: TextView, text: String?) {
        view.text = text.orEmpty()
        view.visibility = if (text.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
    }

    /**
     * The picture shape button carries the shape in force: its glyph (the video inside the
     * screen, cropped, pulled to the edges) and its name ("Picture shape: Crop"). [animate] is a
     * press: the glyph gives a small pop as it changes, so the button answers even before the
     * shape's name shows over the picture. Reduce motion and the system's animations off keep the
     * plain swap.
     */
    fun setShape(scale: VideoScale, animate: Boolean = false) {
        shapeButton.setImageResource(
            when (scale) {
                VideoScale.Fit -> R.drawable.ic_shape_fit
                VideoScale.Crop -> R.drawable.ic_shape_crop
                VideoScale.Stretch -> R.drawable.ic_shape_stretch
                VideoScale.Ratio16x9 -> R.drawable.ic_shape_ratio_16x9
                VideoScale.Ratio4x3 -> R.drawable.ic_shape_ratio_4x3
                VideoScale.Ratio21x9 -> R.drawable.ic_shape_ratio_21x9
                VideoScale.Ratio235 -> R.drawable.ic_shape_ratio_235
                VideoScale.Square -> R.drawable.ic_shape_square
            },
        )
        describe(shapeButton, L.playerPictureShapeNow(scale.label))
        labelViews[shapeButton]?.text = scale.label
        if (!animate || !visible || com.tmplayer.ui.theme.Motion.reduced ||
            !com.tmplayer.ui.theme.systemAnimatorsEnabled(container.context)
        ) return
        shapeButton.animate().cancel()
        shapeButton.scaleX = SHAPE_POP
        shapeButton.scaleY = SHAPE_POP
        shapeButton.animate().scaleX(1f).scaleY(1f).setDuration(SHAPE_POP_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
    }

    /** The orientation button carries its current state, drawn by the activity that owns it. */
    fun setOrientationIcon(resId: Int, label: String) {
        rotate.setImageResource(resId)
        describe(rotate, label)
    }

    fun show(focusButtons: Boolean = false) {
        container.removeCallbacks(hide)
        if (!shown) {
            shown = true
            lastRowButton = null
            // Cancels a fade out still running, and the GONE at its end with it.
            container.animate().cancel()
            container.visibility = View.VISIBLE
            container.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            slideEdges(showing = true)
            onVisibility(true)
            renderPlayPause()
            renderProgress()
            container.postDelayed(tick, TICK_MS)
            // A remote has no pointer, so something must hold focus the moment the row appears,
            // and the bar is what earns it: the arrows then seek at once, which is what an arrow
            // over a video means everywhere else, and the buttons are one press Down away.
            // Down from the bare picture is the one way in that wants the buttons: the arrows
            // already seek without the row, so the bar earns nothing there.
            if (isTv) {
                val first = container.findViewById<View>(R.id.control_subtitles)
                if (!focusButtons || first.visibility != View.VISIBLE || !first.requestFocus()) timeBar.requestFocus()
            }
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
        // The button the remote was on before the sheet opened, else the first one.
        val back = lastRowButton?.takeIf { it.isShown && it.isFocusable }
        (back ?: container.findViewById<View>(R.id.control_subtitles)).requestFocus()
        poke()
    }

    /**
     * Up from the scrub bar lands on [pillId], the skip pill, while a skip is on offer with the row
     * up (WP-19); null puts back the play disc, the bar's usual way up.
     */
    fun reachSkipPill(pillId: Int?) {
        if (!isTv) return
        timeBar.nextFocusUpId = pillId ?: R.id.center_play_pause
    }

    /** Puts the remote back on the scrub bar, where a skip pill it stood on went away. */
    fun focusBar() {
        if (!isTv || !visible) return
        timeBar.requestFocus()
        poke()
    }

    fun toggle() = if (visible) hideAnimated() else show()

    fun hideAnimated() {
        container.removeCallbacks(hide)
        container.removeCallbacks(tick)
        if (!shown) return
        shown = false
        // A row put away (by Back) with the reader on one of its parts may send no focus cleared
        // from a gone view: without this every later show would stay up for good (PB-11).
        readerFocusInRow = false
        container.animate().alpha(0f).setDuration(FADE_MS)
            .withEndAction { if (!shown) container.visibility = View.GONE }
            .start()
        slideEdges(showing = false)
        onVisibility(false)
    }

    /**
     * On a phone the controls do not only fade: the top bar drops the last few dp into place from
     * above and the row at the foot rises the same into place from below, and they go back the way
     * they came, so the controls read as arriving from the edges they live on. The row's children
     * move rather than the row, since the corner offers measure themselves against the children
     * less their own translation. A television (the remote is already on the bar), Reduce motion
     * and the system's animations off keep the plain fade.
     */
    private fun slideEdges(showing: Boolean) {
        if (isTv || com.tmplayer.ui.theme.Motion.reduced || !com.tmplayer.ui.theme.systemAnimatorsEnabled(container.context)) return
        val travel = SLIDE_DP * container.resources.displayMetrics.density
        // Motion.Standard's curve, as the Compose screens ease.
        val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        val duration = if (showing) FADE_IN_MS else FADE_MS
        val edges = buildList {
            add(topBar to -travel)
            val row = cluster as? android.view.ViewGroup
            if (row != null) for (i in 0 until row.childCount) add(row.getChildAt(i) to travel)
        }
        for ((view, from) in edges) {
            view.animate().cancel()
            if (showing) view.translationY = from
            view.animate().translationY(if (showing) 0f else from).setDuration(duration).setInterpolator(ease).start()
        }
    }

    /** Puts the edges back where they rest, for a hide with no fade. */
    private fun settleEdges() {
        topBar.animate().cancel()
        topBar.translationY = 0f
        (cluster as? android.view.ViewGroup)?.let { row ->
            for (i in 0 until row.childCount) row.getChildAt(i).let { it.animate().cancel(); it.translationY = 0f }
        }
    }

    /** For picture in picture, where a fade would play out in a thumbnail nobody is touching. */
    fun hideNow() {
        // Picture in picture and the other abrupt exits: no card left over the bar.
        hidePreview()
        container.removeCallbacks(hide)
        container.removeCallbacks(tick)
        shown = false
        container.animate().cancel()
        container.alpha = 0f
        container.visibility = View.GONE
        settleEdges()
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

    /**
     * A television, paused by the viewer (WP-21): an OLED panel must not hold the row and the
     * clock still for an hour, so [PAUSE_DIM_MS] after the last press the row goes and a small
     * "Paused" mark takes its place, which moves to another corner every minute (not under Reduce
     * motion, where it stays and only dims). Any key brings the row back.
     */
    private val pauseDim = Runnable { dimPause() }
    private var pauseMark: TextView? = null
    private var markSpot = 0
    private val moveMark = object : Runnable {
        override fun run() {
            markSpot = (markSpot + 1) % MARK_GRAVITY.size
            placeMark()
            container.postDelayed(this, MARK_MOVE_MS)
        }
    }

    /** Whether the "Paused" mark stands in for the row now: the next key only brings the row back (PB2-03). */
    val pauseMarkShown: Boolean get() = pauseMark?.visibility == View.VISIBLE

    private fun pausedByViewer(): Boolean = isTv && player()?.let { it.playWhenReady.not() && it.playbackState != Player.STATE_ENDED } == true

    private fun dimPause() {
        if (!pausedByViewer() || !shown || scrubbing || held) return
        hideAnimated()
        val parent = container.parent as? android.widget.FrameLayout ?: return
        val mark = pauseMark ?: TextView(container.context).apply {
            val density = resources.displayMetrics.density
            background = ContextCompat.getDrawable(context, R.drawable.bg_player_chip)
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            // The state is already said by the play disc's description when the row is up; the mark is for the eyes.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            parent.addView(this, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            pauseMark = this
        }
        mark.text = L.playerPaused
        mark.alpha = if (com.tmplayer.ui.theme.Motion.reduced) MARK_DIM_ALPHA else 1f
        mark.visibility = View.VISIBLE
        placeMark()
        container.removeCallbacks(moveMark)
        if (!com.tmplayer.ui.theme.Motion.reduced) container.postDelayed(moveMark, MARK_MOVE_MS)
    }

    private fun placeMark() {
        val mark = pauseMark ?: return
        val density = mark.resources.displayMetrics.density
        val params = mark.layoutParams as android.widget.FrameLayout.LayoutParams
        params.gravity = MARK_GRAVITY[markSpot]
        // Inside the television's safe area (Tv.SafeH 48 dp, Tv.SafeV 27 dp).
        params.setMargins((48 * density).toInt(), (27 * density).toInt(), (48 * density).toInt(), (27 * density).toInt())
        mark.layoutParams = params
    }

    private fun hideMark() {
        container.removeCallbacks(moveMark)
        pauseMark?.visibility = View.GONE
    }

    /** Nothing of the dim runs once the screen is left or stopped: no timer outlives the activity (the low-end rule). */
    fun stopPauseDim() {
        container.removeCallbacks(pauseDim)
        hideMark()
    }

    /** Buys the row its timeout again. Called by every interaction, however it arrived. */
    fun poke() {
        container.removeCallbacks(hide)
        // Whatever the viewer just did: the mark is gone, and a pause starts counting again.
        container.removeCallbacks(pauseDim)
        hideMark()
        if (pausedByViewer()) container.postDelayed(pauseDim, PAUSE_DIM_MS)
        if (held || player()?.isPlaying != true) return
        // The resume's own time holds for the pokes the resume itself sets off (the player's
        // listeners answer it more than once); anything later is the viewer, who gets the usual.
        val quick = resumeHideMs?.takeIf { SystemClock.uptimeMillis() - resumedAt < RESUME_ECHO_MS }
        if (quick == null) resumeHideMs = null
        val after = quick ?: timeoutMs
        // A screen reader walks the row slower than a thumb: the system's own time for controls
        // (3x below Android 10), and the row stays while the reader's focus is on one of its parts.
        val reader = accessibility?.takeIf { it.isTouchExplorationEnabled || it.isEnabled }
        if (reader != null && readerFocusInRow) return
        val wait = if (after > 0 && reader != null) reader.recommendedControlsTimeout(after) else after
        if (wait > 0) container.postDelayed(hide, wait)
    }

    private val accessibility: android.view.accessibility.AccessibilityManager? =
        runCatching { container.context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }.getOrNull()

    /** True while TalkBack or Switch Access has its focus on a part of the row. */
    private var readerFocusInRow = false

    init {
        container.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(
                host: ViewGroup,
                child: View,
                event: android.view.accessibility.AccessibilityEvent,
            ): Boolean {
                when (event.eventType) {
                    android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                        readerFocusInRow = true
                        container.removeCallbacks(hide)
                    }
                    android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> {
                        readerFocusInRow = false
                        // The focus moved on or out: the row gets its time again.
                        container.post { poke() }
                    }
                }
                return super.onRequestSendAccessibilityEvent(host, child, event)
            }
        })
    }

    /** The time the row stays after a resume, in place of [timeoutMs]; see [resumed]. */
    private var resumeHideMs: Long? = null
    private var resumedAt = 0L

    /**
     * A pause, however it came: the row comes up and stays for the whole pause, and on a
     * television the play disc takes the remote, so OK resumes.
     */
    fun paused() {
        resumeHideMs = null
        show()
        // Not off a row button the viewer is on (HX22): a pause that came from elsewhere while
        // they work the row leaves them there, so their next OK is that button's again.
        if (isTv && !buttons.hasFocus()) centerPlay.requestFocus()
    }

    /**
     * On a television, a press that stays on the row (picture shape) keeps the remote on its
     * button. On the Mi TV a shape change in a 3D video redrew the picture under the row and the
     * focus fell to the play disc, so the next OK paused the video (HX22): taken back here if it
     * falls to the disc, the bar or nowhere in the moments after the press.
     */
    private fun holdFocus(button: View) {
        if (!isTv) return
        for (after in HOLD_FOCUS_CHECKS_MS) {
            container.postDelayed({
                if (!visible || !button.isShown || button.isFocused) return@postDelayed
                val now = container.rootView.findFocus()
                if (now == null || now === centerPlay || now === timeBar) button.requestFocus()
            }, after)
        }
    }

    /**
     * A resume: the row stays for the disc to turn back into pause, then goes after [hideAfterMs]
     * (see [com.tmplayer.player.PauseControls]). Focus stays where it is.
     */
    fun resumed(hideAfterMs: Long) {
        resumeHideMs = hideAfterMs
        resumedAt = SystemClock.uptimeMillis()
        if (!shown) show() else poke()
    }

    /**
     * The glyph follows the player's intent rather than whether frames are moving, so a video
     * stalled on the network still shows pause: the viewer asked for it to play, and a tap on the
     * disc should stop that, not start it.
     */
    private fun renderPlayPause(animate: Boolean = false) {
        val exo = player()
        val playing = exo?.isPlaying == true || (exo?.playWhenReady == true && buffering)
        // The morph is the only answer to a pause or a resume; with animations off in the
        // system's settings the animator runs in no time, so the glyph simply changes.
        centerIcon.setShowsPlay(!playing, animate = animate && centerDrawn && visible)
        centerDrawn = true
        describe(centerPlay, if (playing) L.commonPause else L.commonPlay)
        val state = if (bufferingSpoken) L.playerBuffering else if (playing) L.playerPlaying else L.playerPaused
        if (ViewCompat.getStateDescription(centerPlay) != state) ViewCompat.setStateDescription(centerPlay, state)
    }

    private fun renderProgress() {
        val exo = player() ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        if (chapters.isNotEmpty() && duration != marksFor) renderMarks()
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
            time.text = "${Translator.messages.formatter.clock(position)} / $end" + loopBadge() + chapterBadge(position)
            // What OK on the readout does, the phone's total says the same (below).
            describe(time, if (showRemaining) L.playerShowTotalLength else L.playerShowTimeRemaining)
            return
        }
        timesPosition.text = Translator.messages.formatter.clock(position) + loopBadge() + chapterBadge(position)
        timesDuration.text = if (showRemaining && duration > 0) {
            "-" + Translator.messages.formatter.clock((duration - position).coerceAtLeast(0))
        } else {
            Translator.messages.formatter.clock(duration)
        }
        describe(timesDuration, if (showRemaining) L.playerShowTotalLength else L.playerShowTimeRemaining)
    }

    /** The chapter at [position] after the time, cut short so a long name leaves the total in view. */
    private fun chapterBadge(position: Long): String {
        val label = Chapters.labelAt(chapters, position) ?: return ""
        val short = if (label.length > CHAPTER_CHARS) label.take(CHAPTER_CHARS - 1).trimEnd() + "\u2026" else label
        return "  \u00b7  $short"
    }

    /** The repeat's mark after the time, with the separator the player's other readouts use. */
    private fun loopBadge(): String {
        val marked = loop ?: return ""
        return "  ·  " + if (marked.complete) L.playerLoopBadge else L.playerLoopBadgeStart
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
        /** After this long paused with no press, a television's row goes and the mark takes its place (WP-21). */
        const val PAUSE_DIM_MS = 10_000L

        /** The mark steps to the next corner this often. */
        const val MARK_MOVE_MS = 60_000L

        /** A mark that does not move (Reduce motion) is drawn at this strength. */
        const val MARK_DIM_ALPHA = 0.6f

        /** The corners the mark visits, top left first, clockwise. */
        val MARK_GRAVITY = intArrayOf(
            android.view.Gravity.TOP or android.view.Gravity.START,
            android.view.Gravity.TOP or android.view.Gravity.END,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
            android.view.Gravity.BOTTOM or android.view.Gravity.START,
        )

        /** When, after a press that stays on the row, the remote is checked to still be on its button. */
        val HOLD_FOCUS_CHECKS_MS = longArrayOf(0L, 150L, 400L, 900L)

        /** Nuvio's figure, and Netflix's: long enough to read as calm, short enough to feel live. */
        const val FADE_MS = 200L

        /** The row arrives a touch slower than it leaves, so it settles in rather than pops. */
        const val FADE_IN_MS = 250L

        /** How far the top bar and the row at the foot travel as they fade in and out, in dp. */
        const val SLIDE_DP = 10f

        /** Long enough to read the row and reach for something on it, short enough to get out. */
        const val TIMEOUT_MS = 3_500L

        /** How long after a resume the player's own answers to it still count as the resume. */
        const val RESUME_ECHO_MS = 500L

        private const val HINT_FADE_MS = 160L

        /** Twice a second: faster than the eye needs on a scrub bar this size. */
        const val TICK_MS = 500L

        /**
         * How wide the phone's row must be, in dp inside its padding, to take picture shape and
         * the lock as well: six round buttons, the copy pill and Episodes. A phone held upright
         * (360 to 430 wide) stays under it, one held sideways (640 and up) is well over.
         */
        const val WIDE_ROW_DP = 540f

        /** The seconds the back and forward buttons name and jump ([Skip.BUTTON_MS]). */
        const val SKIP_SECONDS = (Skip.BUTTON_MS / 1000).toInt()

        /** How long a stall lasts before a screen reader is told "Buffering": short ones stay silent. */
        const val BUFFERING_SPOKEN_MS = 500L

        /** The figure inside the skip buttons' arrow, in dp (not sp: it must stay inside the circle). */
        const val SEEK_FIGURE_DP = 10f

        /** The same on a television, where nothing is under 14 (the circle is 48 dp, the figure two digits). */
        const val SEEK_FIGURE_TV_DP = 14f

        /** [fitRow]'s last step: both pills as their glyphs alone and speed gone. */
        const val LAST_ROW_STEP = 4

        /** Where the picture shape glyph starts its pop from, and how long the pop takes. */
        const val SHAPE_POP = 0.82f
        const val SHAPE_POP_MS = 220L

        /** About as much of a chapter's name as fits beside the times on a phone held upright. */
        const val CHAPTER_CHARS = 34

        /** A cut in the bar, near the black of the scrim it sits on. */
        val CHAPTER_CUT = Color.argb(0xE6, 0x10, 0x10, 0x10)
    }
}

/** A view's layout direction for a language that is, or is not, written right to left. */
internal fun layoutDirectionFor(rtl: Boolean): Int =
    if (rtl) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR

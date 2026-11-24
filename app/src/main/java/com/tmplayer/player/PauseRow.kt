package com.tmplayer.player

/**
 * The transport row around a pause or a resume, from anywhere (the row, a key, the picture, a
 * headset, the notification): see [PauseControls]. A pause brings the row up for its whole length
 * (on a television with the play disc focused, so OK resumes), a resume lets it go, sooner when it
 * was down before.
 *
 * Under the More panel or a track picker ([underPanel]) the row is left alone, for a pause and a
 * resume alike: raising it, or focusing its disc or bar, took the remote from the panel, so the
 * next OK resumed the video behind it and the arrows walked a hidden row (WP-19, PB-01). The pause
 * is owed instead, and [pay] brings the row up once the panel or the picker has closed.
 *
 * Kept out of the activity so a unit test can drive it with the real row and a focused panel.
 */
internal class PauseRow(
    private val controls: () -> PlayerControls?,
    private val controlsUp: () -> Boolean,
    /** The status sheet, the lock or picture in picture: nothing about the row changes. */
    private val blocked: () -> Boolean,
    /** "Still watching?" holds the screen: a pause or a resume under it leaves the row as it is. */
    private val stillWatching: () -> Boolean,
    /** The More panel or a track picker has the remote. */
    private val underPanel: () -> Boolean,
    /** The player is asked to stay paused now. */
    private val pausedNow: () -> Boolean,
    /** Says "Pause" over the picture, for a pause under a panel that keeps the row down. */
    private val sayPaused: () -> Unit,
) {
    /** Whether the row was down when the pause came, for how soon it goes after the resume. */
    private var rowDownBeforePause = false

    /** True between [raiseForPause] and the pause it was for, so the row's state is read before it rose. */
    private var pauseNoted = false

    /** True once the viewer has paused this video, so the first play is not taken for a resume. */
    private var sawPause = false

    /** A pause came under a panel or a picker, which kept the row down: it is raised once they close. */
    var owed = false
        private set

    /** Before a pause asked for here (a key, a button): the row comes up first, so the disc is there to turn. */
    fun raiseForPause() {
        if (blocked()) return
        if (underPanel()) {
            // Says so now, and the row comes up when the panel or the picker closes ([pay]).
            owed = true
            sayPaused()
            return
        }
        rowDownBeforePause = !controlsUp()
        pauseNoted = true
        controls()?.show()
    }

    /** The panel or the picker closed: a pause that came under it brings the row up now, disc focused. */
    fun pay() {
        if (!owed) return
        owed = false
        if (!pausedNow() || blocked()) return
        controls()?.paused()
    }

    /** The player's own report of a pause or a resume. */
    fun changed(playWhenReady: Boolean, endOfItem: Boolean) {
        val quiet = blocked() || stillWatching()
        val panel = underPanel()
        if (!playWhenReady) {
            if (endOfItem) return
            if (!pauseNoted) rowDownBeforePause = !controlsUp()
            pauseNoted = false
            sawPause = true
            if (panel && !quiet) {
                owed = true
                return
            }
            if (!quiet) controls()?.paused()
            return
        }
        if (!sawPause) return
        sawPause = false
        val after = PauseControls.hideAfterResumeMs(controls()?.timeoutMs ?: 0L, rowDownBeforePause)
        rowDownBeforePause = false
        // Resumed under the panel: nothing is owed any more, and the row stays as it is.
        if (panel) {
            owed = false
            return
        }
        if (!quiet) controls()?.resumed(after)
    }
}

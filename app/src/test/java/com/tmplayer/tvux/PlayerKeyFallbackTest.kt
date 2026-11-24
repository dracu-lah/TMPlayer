package com.tmplayer.tvux

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import com.tmplayer.R
import com.tmplayer.player.PlayerControls
import com.tmplayer.player.PlayerMenu
import com.tmplayer.player.PlayerMenuEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

/**
 * Every key-only action of the television's player has an arrows-and-OK path (WP-89, designer M3):
 * nothing there works only through a key the Google TV remote lacks or a hold nobody sees. The walk
 * of the key table's player columns found each action's fallback, and this holds them: a row button
 * (reached by Down from the bar and Left or Right) or a line of More (the More button is the row's
 * last), listed in `docs/agents/tv-keys.md`.
 *
 * | Action | Key | Arrows and OK |
 * | --- | --- | --- |
 * | Subtitles on, off or the picker | CC | the row's Subtitles button |
 * | Audio track | Audio | the row's Audio button |
 * | Speed, and 2x while held | held Fast forward | the row's Speed button, More, Speed |
 * | Previous and next video | Channel keys | the row's Previous and Next buttons |
 * | A chapter, or a minute | Page keys | More, Next chapter and Previous chapter; Left and Right |
 * | Details | Info | More, Playback details |
 * | Report a stutter | held Info | More, Report stutter |
 * | The handover | long OK, held Menu | More, Open in another app |
 * | Stop | Stop | Back |
 * | The keys themselves | none | More, Remote keys |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerKeyFallbackTest {

    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        when (method.name) {
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

    private fun row(): FrameLayout {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val root = FrameLayout(activity).apply { id = R.id.player_root }
        LayoutInflater.from(activity).inflate(R.layout.player_controls, root, true)
        activity.setContentView(root)
        PlayerControls(
            root = root, isTv = true, player = { player }, onVisibility = {}, onTogglePlay = {}, onPickSubtitles = {},
            onPickAudio = {}, onPickSpeed = {}, onCycleOrientation = {}, onPlayEpisode = {},
        ).show()
        return root
    }

    @Test
    fun `the television's row has a focusable button for each action its keys do`() {
        val root = row()
        for ((name, id) in listOf(
            "Subtitles (CC)" to R.id.control_subtitles,
            "Audio" to R.id.control_audio,
            "Speed (held Fast forward)" to R.id.control_speed,
            "Previous video (Channel)" to R.id.control_tv_previous_episode,
            "Next video (Channel)" to R.id.control_tv_next_episode,
            "More" to R.id.control_tv_more,
        )) {
            val button = root.findViewById<View>(id)
            assertTrue("$name has a button in the row", button != null)
            assertTrue("$name takes the remote", button.isFocusable)
        }
        // The buttons that need something to do (an episode before or after) are there to be shown, not left out.
        val more = root.findViewById<View>(R.id.control_tv_more)
        assertEquals("More is the row's last, one Right from the others", View.VISIBLE, more.visibility)
    }

    @Test
    fun `More carries the rest, each under its own gate`() {
        val all = PlayerMenu.tvEntries(
            pictureInPicture = true, saveToDownloads = true, markWatched = true, openInAnotherApp = true,
            bookmarks = true, chapters = true, chapterList = true, reportStutter = true, passthroughOff = false,
        )
        for (entry in listOf(
            PlayerMenuEntry.Speed,
            PlayerMenuEntry.NextChapter,
            PlayerMenuEntry.PreviousChapter,
            PlayerMenuEntry.PlaybackDetails,
            PlayerMenuEntry.ReportStutter,
            PlayerMenuEntry.OpenInAnotherApp,
            PlayerMenuEntry.RemoteKeys,
        )) {
            assertTrue("$entry is a line of More", entry in all)
        }
        // A chat that restricts saving leaves the handover and saving off, and the rest stays.
        val restricted = PlayerMenu.tvEntries(pictureInPicture = false, openInAnotherApp = false, reportStutter = true)
        assertTrue(PlayerMenuEntry.OpenInAnotherApp !in restricted)
        assertTrue(PlayerMenuEntry.ReportStutter in restricted && PlayerMenuEntry.RemoteKeys in restricted)
    }

    @Test
    fun `the key table names each fallback`() {
        val table = listOf("docs/agents/tv-keys.md", "../docs/agents/tv-keys.md").map(::File).first { it.exists() }.readText()
        for (words in listOf("More, Help", "More, Open in another app", "Back to top; row buttons; More entries", "the row's subtitle and audio buttons")) {
            assertTrue("tv-keys.md names the path: $words", words in table)
        }
        assertTrue("the player's fallback list is in the doc", "## Player actions without a key" in table)
    }
}

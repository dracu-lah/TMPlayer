package com.tmplayer.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.browse.BAR_GAP
import com.tmplayer.ui.browse.BAR_HEIGHT
import com.tmplayer.ui.browse.BAR_MARGIN
import com.tmplayer.ui.browse.miniFootOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The phone's mini player plumbing (U8, `docs/mini-player-phone.md`): which launches take a task
 * of their own, the dock's bookkeeping between a player and the shell, and where the bar sits.
 */
@RunWith(RobolectricTestRunner::class)
class MiniDockTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        MiniDock.dismiss()
        MiniDock.enabled = true
        FormFactor.override(false)
    }

    /** A docked player that records what was asked of it. */
    private class FakeDocked(
        override val chatId: Long = 7L,
        private val expands: Boolean = true,
    ) : DockedPlayer {
        override val title = "Episode one"
        override var playing = true
        override val positionMs = 30_000L
        override val durationMs = 60_000L
        override val casting = false
        val calls = mutableListOf<String>()

        override fun togglePlay() {
            calls += "toggle"
            playing = !playing
        }

        override fun expand(context: Context): Boolean {
            calls += "expand"
            return expands
        }

        override fun dismiss() {
            calls += "dismiss"
        }

        override fun shellHidden() {
            calls += "hidden"
        }
    }

    @Test
    fun `only a phone with the setting on opens the player in a task of its own`() {
        val table = listOf(
            // tv, enabled, own task
            Triple(false, true, true),
            Triple(false, false, false),
            Triple(true, true, false),
            Triple(true, false, false),
        )
        for ((tv, enabled, own) in table) {
            assertEquals("tv=$tv enabled=$enabled", own, PlayerLaunch.ownTask(tv, enabled))
        }
    }

    @Test
    fun `a launch from the shell on a phone takes a fresh task and brings the shell back as it closes`() {
        FormFactor.override(false)
        val intent = PlayerLaunch.fromBrowse(context, Intent(context, PlayerActivity::class.java))
        assertEquals(PlayerLaunch.OWN_TASK_FLAGS, intent.flags and PlayerLaunch.OWN_TASK_FLAGS)
        assertTrue(intent.getBooleanExtra(PlayerActivity.EXTRA_OUTSIDE_APP_TASK, false))
    }

    @Test
    fun `with the setting off, or on a TV, a launch is left as it always was`() {
        FormFactor.override(false)
        MiniDock.enabled = false
        val off = PlayerLaunch.fromBrowse(context, Intent(context, PlayerActivity::class.java))
        assertEquals(0, off.flags)
        assertFalse(off.hasExtra(PlayerActivity.EXTRA_OUTSIDE_APP_TASK))

        MiniDock.enabled = true
        FormFactor.override(true)
        val tv = PlayerLaunch.fromBrowse(context, Intent(context, PlayerActivity::class.java))
        assertEquals(0, tv.flags)
        assertFalse(tv.hasExtra(PlayerActivity.EXTRA_OUTSIDE_APP_TASK))
    }

    @Test
    fun `a new video from the shell dismisses the docked one first, so there is one player`() {
        val docked = FakeDocked()
        MiniDock.dock(docked)
        PlayerLaunch.fromBrowse(context, Intent(context, PlayerActivity::class.java))
        assertEquals(listOf("dismiss"), docked.calls)
        assertNull(MiniDock.docked.value)
    }

    @Test
    fun `undocking an older player leaves the one docked since`() {
        val first = FakeDocked()
        val second = FakeDocked()
        MiniDock.dock(first)
        MiniDock.dock(second)
        MiniDock.undock(first)
        assertSame(second, MiniDock.docked.value)
        MiniDock.undock(second)
        assertNull(MiniDock.docked.value)
    }

    @Test
    fun `expanding takes the bar away and brings the player's task forward`() {
        val docked = FakeDocked()
        MiniDock.dock(docked)
        assertTrue(MiniDock.expand(context))
        assertEquals(listOf("expand"), docked.calls)
        assertNull(MiniDock.docked.value)
    }

    @Test
    fun `a player whose task is gone is dismissed rather than left holding a decoder`() {
        val docked = FakeDocked(expands = false)
        MiniDock.dock(docked)
        assertFalse(MiniDock.expand(context))
        assertEquals(listOf("expand", "dismiss"), docked.calls)
        assertNull(MiniDock.docked.value)
    }

    @Test
    fun `play, pause and the shell leaving go to the docked player, and nothing happens with none`() {
        MiniDock.togglePlay()
        MiniDock.shellHidden()
        assertFalse(MiniDock.expand(context))
        val docked = FakeDocked()
        MiniDock.dock(docked)
        MiniDock.togglePlay()
        MiniDock.shellHidden()
        assertEquals(listOf("toggle", "hidden"), docked.calls)
        assertFalse(docked.playing)
        MiniDock.dismiss()
        assertEquals(listOf("toggle", "hidden", "dismiss"), docked.calls)
        assertNull(MiniDock.docked.value)
    }

    @Test
    fun `a task that is not this app's is never brought forward`() {
        assertFalse(MiniDock.moveTaskToFront(context, taskId = 987_654))
    }

    @Test
    fun `the player has a task affinity of its own and leaves Recents with its last player`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, PlayerActivity::class.java), 0)
        assertEquals("${context.packageName}.player", info.taskAffinity)
        assertTrue(info.flags and ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS != 0)
        // Default launch mode: under singleTask the next episode reached the live instance.
        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, info.launchMode)
    }

    @Test
    fun `the bar sits over the floating bar while it is out, and at the foot otherwise`() {
        val nav = 24.dp
        val room = nav + BAR_MARGIN + BAR_HEIGHT + BAR_GAP
        assertEquals(room, miniFootOf(navigationBar = nav, rail = false, barShown = true, withActions = false, room = room))
        assertEquals(nav + BAR_MARGIN, miniFootOf(navigationBar = nav, rail = false, barShown = false, withActions = false, room = room))
        // Beside a rail there is no bar at the foot, only the page's pill when it has one.
        assertEquals(nav + BAR_MARGIN, miniFootOf(navigationBar = nav, rail = true, barShown = true, withActions = false, room = room))
        assertTrue(miniFootOf(navigationBar = nav, rail = true, barShown = true, withActions = true, room = room) > nav + BAR_MARGIN)
    }
}

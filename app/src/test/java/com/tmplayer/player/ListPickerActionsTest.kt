package com.tmplayer.player

import com.tmplayer.data.ListsStore
import com.tmplayer.data.NamedListRules
import com.tmplayer.data.VisibleList
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.browse.ListPickerActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The writes behind "Add to a list" in the player's More options (HX43), which are the card menu's
 * own ([ListPickerActions]): a press adds, a press on a ticked list takes it out, a name another
 * list has makes a second list, and the hundredth list is the last. Robolectric only for the main
 * thread the answer is said on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListPickerActionsTest {

    private val dir = Files.createTempDirectory("tm-picker-actions").toFile()
    private val lists: ListsStore = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME))).lists
    private val said = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun video(message: Long = 4) = WatchLaterRecord(
        chatId = -100, messageId = message, fileId = 7, title = "Clip $message", fileName = "Clip $message.mp4", chatTitle = "Chat",
        sizeBytes = 1_000, durationSec = 60, mimeType = "video/mp4", addedAt = 0,
    )

    private fun actions(record: WatchLaterRecord = video()) = ListPickerActions(lists, L.messages, record) { said += it }

    private fun visible(): List<VisibleList> = runBlocking { lists.visible.first() }

    /** The writes run off the caller's thread: wait for [done] rather than for a fixed time. */
    private fun until(what: String, done: () -> Boolean) {
        repeat(400) {
            // The line is said on the main thread, which Robolectric runs when its looper is let to.
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("never happened: $what, said $said")
    }

    @Test
    fun `a press adds the video and says so, and a press on the ticked list takes it out`() {
        val made = runBlocking { lists.create("Weekend") }!!
        val record = video()
        actions(record).pick(visible().first(), inIt = false)
        until("added") { visible().first().ids.contains(record.id) }
        until("said") { said.any { it.contains("Weekend") } }

        val before = said.size
        actions(record).pick(visible().first(), inIt = true)
        until("removed") { !visible().first().ids.contains(record.id) }
        until("said again") { said.size > before }
        assertEquals(made.id, visible().first().info.id)
    }

    @Test
    fun `a name another list has makes a second list holding the video`() {
        runBlocking { lists.create("Weekend") }
        actions().create("Weekend", known = 1)
        until("two lists") { visible().size == 2 }
        assertEquals(listOf("Weekend", "Weekend"), visible().map { it.info.name })
        assertEquals(1, visible().count { it.ids.isNotEmpty() })
    }

    @Test
    fun `the hundredth list is made and the next press says there is no room`() {
        runBlocking { repeat(NamedListRules.MAX_LISTS) { lists.create("List $it") } }
        assertEquals(NamedListRules.MAX_LISTS, visible().size)
        actions().create("One too many", known = NamedListRules.MAX_LISTS)
        until("told") { said.any { it == L.messages.listsTooMany(NamedListRules.MAX_LISTS) } }
        assertEquals(NamedListRules.MAX_LISTS, visible().size)
        assertTrue(visible().none { it.ids.isNotEmpty() })
    }

    @Test
    fun `an empty name makes nothing and says to give the list a name`() {
        actions().create("   ", known = 0)
        until("told") { said.contains(L.messages.listsNameEmpty) }
        assertFalse(visible().isNotEmpty())
    }

    @Test
    fun `two quick toggles before the screen catches up add and then remove, never add twice`() {
        runBlocking { lists.create("Weekend") }
        val record = video()
        val stale = visible().first()
        val picker = actions(record)
        // Both presses were made against the same drawn list, which did not have the video.
        picker.toggle(stale)
        picker.toggle(stale)
        until("two lines said") { said.size == 2 }
        assertEquals(L.messages.listsAdded(record.title, "Weekend"), said[0])
        assertEquals(L.messages.listsRemoved(record.title, "Weekend"), said[1])
        assertFalse(visible().first().ids.contains(record.id))
    }
}

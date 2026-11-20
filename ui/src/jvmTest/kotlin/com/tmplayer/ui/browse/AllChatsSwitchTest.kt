package com.tmplayer.ui.browse

import com.tmplayer.data.AddedChat
import com.tmplayer.data.AddedChoice
import com.tmplayer.data.AddedMode
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ChatVideoCount
import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The "All chats" switch in the picker's model (HX86): a fresh start ticks Saved Messages, the
 * switch disables the ticks without losing them, off brings them back (never empty), a quick on and
 * off writes once with the last state, and a stored answer opens with the switch where it was.
 * The store is the real one on a temporary file; its read is real time, so the waits poll.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AllChatsSwitchTest {

    private val dir: File = Files.createTempDirectory("all-chats-switch").toFile()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ChatVideoCount.forget()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        ChatVideoCount.forget()
        dir.deleteRecursively()
    }

    private val saved = ChatSummary(1, "Saved Messages", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Saved)
    private fun chat(id: Long) = ChatSummary(id, "Chat $id", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel)
    private val chats = listOf(saved) + (10L..40L).map(::chat)

    private fun store() = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    @Test
    fun `a fresh install starts with Saved Messages ticked and Back has nothing to ask about`() {
        val model = AddChatsViewModel(store(), null).also { it.open() }
        try {
            model.start(chats)
            waitFor("Saved Messages ticked") { model.state.value.ticked == setOf(1L) }
            assertFalse(model.state.value.allChats)
            assertFalse("a tick nobody made is not a change", model.changed)
        } finally {
            model.stop()
        }
    }

    @Test
    fun `a viewer's own tick made before the stored answer is read is not joined by Saved Messages`() {
        val model = AddChatsViewModel(store(), null)
        try {
            model.toggle(12)
            model.open()
            model.start(chats)
            waitFor("the read") { model.changed }
            assertEquals(setOf(12L), model.state.value.ticked)
        } finally {
            model.stop()
        }
    }

    @Test
    fun `an existing viewer keeps their picks and sees the switch off`() {
        val settings = store()
        runBlocking { settings.setAddedChats(listOf(AddedChat(11, "Chat 11"), AddedChat(12, "Chat 12"))) }
        val model = AddChatsViewModel(settings, null).also { it.open() }
        try {
            model.start(chats)
            waitFor("the stored choice") { model.state.value.added.size == 2 }
            assertEquals(setOf(11L, 12L), model.state.value.ticked)
            assertFalse(model.state.value.allChats)
        } finally {
            model.stop()
        }
    }

    @Test
    fun `on disables the ticks and the folders, keeps them, and off brings them back`() {
        val model = AddChatsViewModel(null, null)
        model.toggle(11)
        model.toggle(12)
        model.setAllChats(true, chats, listComplete = true)
        assertTrue(model.state.value.allChats)
        model.toggle(13)
        model.selectAll(setOf(14L, 15L))
        model.clear(setOf(11L))
        model.folder(7)
        assertEquals("the kept ticks are not edited behind the switch", setOf(11L, 12L), model.state.value.ticked)
        assertEquals(null, model.state.value.folder)
        model.setAllChats(false, chats, listComplete = true)
        assertFalse(model.state.value.allChats)
        assertEquals(setOf(11L, 12L), model.state.value.ticked)
        model.toggle(13)
        assertEquals(setOf(11L, 12L, 13L), model.state.value.ticked)
    }

    @Test
    fun `on with nothing ticked and off again returns to Saved Messages, never an empty set`() {
        val model = AddChatsViewModel(null, null)
        model.clear(setOf(1L))
        assertTrue(model.state.value.ticked.isEmpty())
        model.setAllChats(true, chats, listComplete = true)
        model.setAllChats(false, chats, listComplete = true)
        assertEquals(setOf(1L), model.state.value.ticked)
    }

    @Test
    fun `a kept tick for a chat since left is dropped quietly when the switch goes off`() {
        val model = AddChatsViewModel(null, null)
        model.toggle(11)
        model.toggle(999)
        model.setAllChats(true, chats, listComplete = true)
        model.setAllChats(false, chats, listComplete = true)
        assertEquals(setOf(11L), model.state.value.ticked)
    }

    @Test
    fun `turned on and off quickly, Done writes once and the last state wins`() {
        val choices = ArrayList<AddedChoice>()
        val model = AddChatsViewModel(null, null, write = { error("not a plain pick") }, writeChoice = { choices += it })
        model.toggle(11)
        repeat(5) {
            model.setAllChats(true, chats, listComplete = true)
            model.setAllChats(false, chats, listComplete = true)
        }
        model.setAllChats(true, chats, listComplete = true)
        model.done(chats)
        waitFor("the write") { model.state.value.saved }
        assertEquals(1, choices.size)
        assertEquals(AddedMode.All, choices.single().mode)
        assertEquals(listOf(11L), choices.single().chats.map { it.id })
    }

    @Test
    fun `Done with the switch on is allowed with nothing ticked, and the answer opens again with the switch on`() {
        val settings = store()
        val model = AddChatsViewModel(settings, null).also { it.open() }
        model.setAllChats(true, chats, listComplete = true)
        model.done(chats)
        waitFor("the write") { model.state.value.saved }
        val stored = runBlocking { settings.addedChats.first() }
        assertEquals(AddedMode.All, stored.mode)
        assertEquals(null, stored.ids)
        val again = AddChatsViewModel(settings, null).also { it.open() }
        try {
            waitFor("the switch on") { again.state.value.allChats }
            assertFalse("opened as it was stored, so Back asks nothing", again.changed)
            again.setAllChats(false, chats, listComplete = true)
            assertTrue("a flipped switch is a change", again.changed)
            assertEquals("off with nothing kept is Saved Messages", setOf(1L), again.state.value.ticked)
            again.done(chats)
            waitFor("the write") { again.state.value.saved }
            val back = runBlocking { settings.addedChats.first() }
            assertEquals(AddedMode.Picked, back.mode)
            assertEquals(setOf(1L), back.ids)
        } finally {
            again.stop()
        }
    }

    @Test
    fun `a switch touched before the stored answer lands is not overruled by it`() {
        val settings = store()
        runBlocking { settings.setAddedChoice(AddedChoice(AddedMode.All, listOf(AddedChat(11, "Chat 11")))) }
        val model = AddChatsViewModel(settings, null)
        try {
            model.setAllChats(false, chats, listComplete = true)
            model.open()
            waitFor("the stored choice") { model.state.value.added.isNotEmpty() }
            assertFalse(model.state.value.allChats)
        } finally {
            model.stop()
        }
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }
}

package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatPicking
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ChatVideoCount
import com.tmplayer.data.CountAnswer
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The three pane picker with a remote alone on a large account (HX76): 3,000 chats, the mandatory
 * choice as the setup step shows it. Every line of the focus map: start on Done over a choice
 * already made, else on the first row (a fresh install has Saved Messages ticked, HX86), and on the
 * All chats switch while it is on; the rail, the list and the panel each
 * answer Left, Right, Up, Down and Back; the chats with no videos fold into one row that opens in
 * place; a held Down speeds up; the search keyboard's Search lands on a row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class AddChatsTvKeysTest {

    /** Without it a state write left on the looper at the end of one test silences Compose for the rest of the class (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    @Test
    fun `the remote starts on the first row when the Recommended band is shown, only Saved Messages is ticked for a fresh start`() {
        val model = AddChatsViewModel(counts = Counts, write = {})
        val activity = open(model, chats = CHATS)
        assertEquals("Saved Messages is the one tick of a fresh install (HX86)", setOf(100L), model.state.value.ticked)
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        save("add-chats-tv-three-panes.png", draw(activity))
        model.stop()
        idle()
        ChatVideoCount.forget()
    }

    @Test
    fun `the remote starts on the first row when nothing is ticked`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val activity = open(model, chats = CHATS)
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        model.stop()
    }

    @Test
    fun `no chats at all starts on the rail and the empty scope stays on it`() {
        val model = AddChatsViewModel(counts = Counts, write = {})
        val activity = open(model, chats = emptyList())
        assertTrue(focusedWords(activity).contains("All"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("an empty list keeps the remote on the rail", focusedWords(activity).contains("All"))
        model.stop()
    }

    @Test
    fun `the rail answers Up, Down, Right and OK, and Left from a row comes back to it`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val activity = open(model, chats = CHATS)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left goes to the rail on the current entry", focusedWords(activity).contains("All"))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focusedWords(activity).contains("Channels"))
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(focusedWords(activity).contains("All"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Right goes into the list", listOf("Saved Messages"), focusedTitle(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("OK goes into the list too", listOf("Saved Messages"), focusedTitle(activity))
        model.stop()
    }

    @Test
    fun `a row goes right to Done and the panel goes left to the row it came from`() {
        val model = AddChatsViewModel(counts = null, write = {})
        // Not required, so Done is on the panel with nothing ticked.
        val activity = open(model, chats = CHATS, required = false)
        // The focus fill, ring and ink are painted in the draw phase (HX76): the pictures show
        // the row the remote left drawn plain again and the one it reached lit.
        save("add-chats-tv-row-focused.png", draw(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        save("add-chats-tv-row-focus-moved.png", draw(activity))
        val row = focusedTitle(activity)
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf("Done"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("No Select all on TV: Down stays on Done", listOf("Done"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Left goes back to the row it came from", row, focusedTitle(activity))
        model.stop()
    }

    @Test
    fun `Back goes from a row to the rail and a second Back asks, then Leave leaves the required choice`() {
        val model = AddChatsViewModel(counts = null, write = {})
        var left = 0
        val activity = open(model, chats = CHATS, onLeave = { left++ }, confirmLeave = true)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("Back goes to the rail", focusedWords(activity).contains("All"))
        assertEquals(0, left)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("a second Back asks first, even with nothing ticked", 0, left)
        assertTrue(nodes(activity).any { words(it).contains("Leave without choosing?") })
        click(activity, "Leave")
        idle()
        assertEquals(1, left)
        model.stop()
    }

    /**
     * The setup's one question on a television (R3): the remote starts on the first chat, OK ticks
     * it, Right goes to Done, and Back climbs to the rail and then asks "Leave without choosing?".
     * Saved Messages is ticked for a fresh start (HX86), so Done is on; OK on it unticks it, which
     * turns Done off again until a chat is ticked.
     */
    @Test
    fun `the setup question on a television, first chat, OK ticks, Right goes to Done, Back asks`() {
        val model = AddChatsViewModel(counts = null, write = {})
        var left = 0
        val activity = open(model, chats = CHATS, onLeave = { left++ }, confirmLeave = true)
        assertEquals("Saved Messages is ticked for a fresh start", setOf(100L), model.state.value.ticked)
        assertTrue("the question is the heading", nodes(activity).any { words(it).contains("Which chats have your videos?") })
        assertEquals("focus starts on the first chat", listOf("Saved Messages"), focusedTitle(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("OK unticks it", model.state.value.ticked.isEmpty())
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("OK ticks the chat", 1, model.state.value.ticked.size)
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Right moves to Done", listOf("Done"), focusedWords(activity))
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("the first Back climbs, it does not leave", 0, left)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("the next Back asks", nodes(activity).any { words(it).contains("Leave without choosing?") })
        assertEquals(0, left)
        model.stop()
        idle()
        ChatVideoCount.forget()
    }

    @Test
    fun `the chats with no videos fold into one row that opens in place`() {
        val model = AddChatsViewModel(counts = SmallCounts, write = {})
        val activity = open(model, chats = SMALL, required = false)
        // On the first row (the remote starts there when nothing was ticked), then down to the fold.
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        var presses = 0
        while (focusedWords(activity).none { it.endsWith("chats with no videos") } && presses++ < 20) {
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        }
        val fold = focusedWords(activity)
        assertTrue("a fold row was reached: $fold", fold.any { it == "25 chats with no videos" })
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("the remote stands on the first chat of the fold", listOf("Quiet 1"), focusedTitle(activity))
        assertTrue("the fold is gone once open", nodes(activity).none { words(it).any { w -> w.endsWith("chats with no videos") } })
        model.stop()
    }

    @Test
    fun `a held Down speeds up`() {
        val model = AddChatsViewModel(counts = Counts, write = {}, countsRest = false)
        val activity = open(model, chats = CHATS)
        // Twenty repeats of a held Down: the first few walk, the rest move half a screen each.
        hold(activity, KeyEvent.KEYCODE_DPAD_DOWN, repeats = 20)
        val deep = focusedTitle(activity).single()
        val place = deep.removePrefix("Chat number ").toIntOrNull() ?: 0
        // Walking row by row would reach about 20; half-screen jumps reach about 80.
        assertTrue("a held Down went far, to $deep", place > 60)
        model.stop()
    }

    @Test
    fun `a held Down does not count the rows it runs past`() {
        // The answers are kept app wide, and the other tests here have counted these chats.
        ChatVideoCount.forget()
        val asked = java.util.Collections.synchronizedSet(HashSet<Long>())
        val recording = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer {
                asked += chatId
                return Counts.videoCount(chatId, localOnly)
            }
        }
        val model = AddChatsViewModel(counts = recording, write = {}, countsRest = false)
        val activity = open(model, chats = CHATS)
        hold(activity, KeyEvent.KEYCODE_DPAD_DOWN, repeats = 30)
        // The settle after the last jump is a coroutine delay, which runs on the real clock.
        Thread.sleep(700)
        idle()
        val place = focusedTitle(activity).single().removePrefix("Chat number ").toInt()
        val head = ChatPicking.headStart(CHATS).toSet()
        // The switch above the rows takes a row's height of the list, so a jump is a row shorter (HX86).
        assertTrue("the held Down went past the head start, to $place", 1_000L + place > head.max() + 25)
        // Counted: the head start, and the rows on screen where the remote stopped. Not the
        // screenfuls in between, each a Telegram call whose answer rebuilt the list mid-jump.
        val passed = asked.filter { it !in head && it < 1_000L + place - 20 }
        assertTrue("rows run past were counted: ${passed.size} of them", passed.isEmpty())
        // The screenful under the stop is composed ahead for the next jump, but not on screen.
        val ahead = asked.filter { it > 1_000L + place + 12 }
        assertTrue("rows composed ahead were counted: $ahead", ahead.isEmpty())
        assertTrue("the rows where it stopped are counted: at $place", (1_000L + place) in asked)
        model.stop()
    }

    @Test
    fun `the Search entry puts the remote on the keys and the keyboard's Search puts it on a row`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val activity = open(model, chats = CHATS, search = true)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(5) { press(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue(focusedWords(activity).contains("Search"))
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("OK on Search puts the remote on the first key", listOf("A"), focusedWords(activity))
        save("add-chats-tv-search-keys.png", draw(activity))
        click(activity, "search probe")
        idle()
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        model.stop()
    }

    /**
     * The Recommended band's button (ON-3) is one stop the remote reaches with Down from the first
     * row, OK adds the whole band and OK again takes it out, Down goes on to the band's first
     * row, and Back from it climbs to the rail like Back from a row (ON-5).
     */
    @Test
    fun `the remote reaches the Recommended band's button with Down and OK takes the band`() {
        val model = AddChatsViewModel(counts = Counts, write = {}, countsRest = false)
        val activity = open(model, chats = CHATS)
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        assertEquals(12, model.state.value.recommended.size)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf("Add these 12 chats"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(model.state.value.recommended.toSet() + 100L, model.state.value.ticked)
        assertEquals("the remote stays on the button", listOf("Remove these 12 chats"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("the band is out again, Saved Messages stays", setOf(100L), model.state.value.ticked)
        assertEquals(listOf("Add these 12 chats"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Down goes on to the band's first chat", listOf("Chat number 1"), focusedTitle(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(listOf("Add these 12 chats"), focusedWords(activity))
        // Saved Messages is ticked, so Done is on and Right goes to it.
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Right goes to Done", listOf("Done"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf("Done"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Left goes back to the last row the remote stood on", listOf("Chat number 1"), focusedTitle(activity))
        model.stop()
        idle()
        ChatVideoCount.forget()
    }

    @Test
    fun `Back from the band's button climbs to the rail, then with ticks asks before leaving`() {
        val model = AddChatsViewModel(counts = Counts, write = {}, countsRest = false)
        var left = 0
        val activity = open(model, chats = CHATS, onLeave = { left++ }, confirmLeave = true)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(13, model.state.value.ticked.size)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("Back from the button goes to the rail: ${focusedWords(activity)}", focusedWords(activity).contains("All"))
        assertEquals(0, left)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("with ticks the second Back asks", 0, left)
        assertTrue(nodes(activity).any { words(it).contains("Leave without choosing?") })
        // No is the way back: the question goes and the ticks stay.
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(nodes(activity).none { words(it).contains("Leave without choosing?") })
        assertEquals(13, model.state.value.ticked.size)
        // The remote went back to Done, so Back climbs to the rail first and the next one asks again.
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        click(activity, "Leave")
        idle()
        assertEquals(1, left)
        model.stop()
        idle()
        ChatVideoCount.forget()
    }

    @Test
    fun `an account with nothing recommended gets Continue without choosing, reached by Right and OK`() {
        val model = AddChatsViewModel(counts = NoVideos, write = {})
        var went = 0
        val activity = open(model, chats = CHATS.take(12), onContinue = { went++ })
        assertTrue("nothing to recommend", model.state.value.recommended.isEmpty())
        // Saved Messages is ticked for a fresh start (HX86), so Done works and nothing is offered
        // until the viewer unticks it: then the way out comes back.
        assertTrue(nodes(activity).none { words(it).contains("Continue without choosing") })
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(model.state.value.ticked.isEmpty())
        assertTrue(nodes(activity).any { words(it).contains("Continue without choosing") })
        // Done is off with nothing ticked, so Right lands on the way out instead of staying put.
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf("Continue without choosing"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Left goes back to the row", listOf("Saved Messages"), focusedTitle(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, went)
        model.stop()
        idle()
        ChatVideoCount.forget()
    }

    @Test
    fun `with a Recommended band the way out is not offered, the gate stays required`() {
        val model = AddChatsViewModel(counts = Counts, write = {}, countsRest = false)
        val activity = open(model, chats = CHATS, onContinue = {})
        assertEquals(12, model.state.value.recommended.size)
        assertTrue(nodes(activity).none { words(it).contains("Continue without choosing") })
        model.stop()
        idle()
        ChatVideoCount.forget()
    }

    // ---- Telegram folders in the rail (HX29) ------------------------------------------------

    @Test
    fun `the rail lists the folders under the scopes, the remote walks to one and into its chats`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val activity = open(model, chats = FOLDERED, folders = FOLDERS, required = false)
        assertTrue("the Folders heading is on screen", nodes(activity).any { words(it).contains("Folders") })
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue(focusedWords(activity).contains("All"))
        // All, Channels, Groups, People, Archived, then the folders.
        repeat(5) { press(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("the first folder: ${focusedWords(activity)}", focusedWords(activity).contains("Films"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(7, model.state.value.folder)
        assertEquals("Right goes into the folder's first chat", listOf("Loud 1"), focusedTitle(activity))
        save("add-chats-tv-folder.png", draw(activity))
        // Back climbs to the rail, on the folder.
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("Back from a row goes to the rail: ${focusedWords(activity)}", focusedWords(activity).contains("Films"))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focusedWords(activity).contains("Family"))
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up from the first folder is Archived", focusedWords(activity).contains("Archived"))
        model.stop()
    }

    @Test
    fun `a folder is taken whole with one press, its chats only, and a second press takes them out`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val activity = open(model, chats = FOLDERED, folders = FOLDERS, required = false)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(6) { press(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue(focusedWords(activity).contains("Family"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(8, model.state.value.folder)
        // Up from the first row is the folder's one button.
        settle(activity)
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(listOf("Add these 5 chats"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(setOf(100L, 2_003L, 2_004L, 2_005L, 3_001L, 3_002L), model.state.value.ticked)
        assertEquals(listOf("Remove these 5 chats"), focusedWords(activity))
        // The button hands Back to the rail like a row does.
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(focusedWords(activity).contains("Family"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("the second press takes them out again, Saved Messages stays", setOf(100L), model.state.value.ticked)
        model.stop()
    }

    @Test
    fun `a folder still being read says so, and one that could not be read offers Try again to the remote`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val reading = open(model, chats = SMALL, folders = FOLDERS, folderStatus = FolderStatus(pending = true), required = false)
        press(reading, KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(5) { press(reading, KeyEvent.KEYCODE_DPAD_DOWN) }
        press(reading, KeyEvent.KEYCODE_DPAD_RIGHT)
        settle(reading)
        assertTrue(nodes(reading).any { words(it).contains("Reading this folder from Telegram\u2026") })
        assertTrue("nothing to stand on yet, so the remote stays on the rail", focusedWords(reading).contains("Films"))
        model.stop()
    }

    @Test
    fun `a folder that could not be read is not called empty, and Try again is one press from its entry`() {
        val model = AddChatsViewModel(counts = null, write = {})
        var retried = 0
        val activity = open(
            model, chats = SMALL, folders = FOLDERS, folderStatus = FolderStatus(failed = setOf(7)),
            onRetryFolders = { retried++ }, required = false,
        )
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(5) { press(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        // A Robolectric window lays the list out only when it draws, so the button exists after the
        // draw and the press is made once more; on a television the first press already lands on it.
        settle(activity)
        assertTrue(nodes(activity).any { words(it).contains("This folder could not be read from Telegram. It is not empty.") })
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf("Try again"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, retried)
        // Back from the button goes to the rail and does not close the picker.
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(focusedWords(activity).contains("Films"))
        model.stop()
    }

    @Test
    fun `a folder that vanished while chosen falls back to every chat`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val activity = open(model, chats = FOLDERED, folders = FOLDERS, required = false)
        model.folder(99)
        idle()
        // Loud 1 is in Films only: it is listed because the vanished folder is no filter any more.
        assertTrue(nodes(activity).any { words(it).contains("Loud 1") })
        model.stop()
    }

    // ---- The All chats switch (HX86) ---------------------------------------------------------

    /**
     * The switch is the first stop above the rows: Up from the first row reaches it, OK flips it,
     * and with it on the rows are disabled, so Down goes straight to Done.
     */
    @Test
    fun `the All chats switch is above the rows, OK flips it, and with it on Down skips the disabled ticks to Done`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val activity = open(model, chats = CHATS, required = false)
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue("Up from the first row reaches the switch: ${focusedWords(activity)}", focusedWords(activity).contains("All chats"))
        assertFalse(model.state.value.allChats)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("OK turns it on", model.state.value.allChats)
        assertEquals("the ticks are kept", setOf(100L), model.state.value.ticked)
        assertTrue("it says On for a screen reader", nodes(activity).any { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) == "On"
        })
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse("OK turns it off again", model.state.value.allChats)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Down skips the disabled ticks to Done", listOf("Done"), focusedWords(activity))
        assertTrue("the foot says why", nodes(activity).any { words(it).contains("All chats is on") })
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        model.stop()
    }

    @Test
    fun `an account opened with All chats on starts the remote on the switch, and Done works with nothing ticked`() {
        val model = AddChatsViewModel(counts = null, write = {})
        model.clear(setOf(100L))
        model.setAllChats(true, CHATS, listComplete = true)
        var went = 0
        val activity = open(model, chats = CHATS, onContinue = { went++ })
        assertTrue("the remote starts on the switch: ${focusedWords(activity)}", focusedWords(activity).contains("All chats"))
        assertTrue(model.state.value.ticked.isEmpty())
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Right goes to Done, which is on in the required choice", listOf("Done"), focusedWords(activity))
        assertTrue(nodes(activity).none { words(it).contains("Continue without choosing") })
        model.stop()
    }

    @Test
    fun `the folders in the rail and the Recommended band are greyed while All chats is on`() {
        val model = AddChatsViewModel(counts = null, write = {})
        model.setAllChats(true, FOLDERED, listComplete = true)
        val activity = open(model, chats = FOLDERED, folders = FOLDERS, required = false)
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(6) { press(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("a folder is not chosen while every chat counts", null, model.state.value.folder)
        model.stop()
    }

    private fun open(
        model: AddChatsViewModel,
        chats: List<ChatSummary>,
        search: Boolean = false,
        folders: List<com.tmplayer.data.ChatFolderSummary> = emptyList(),
        folderStatus: FolderStatus = FolderStatus(),
        onRetryFolders: () -> Unit = {},
        required: Boolean = true,
        onLeave: () -> Unit = {},
        confirmLeave: Boolean = false,
        onContinue: (() -> Unit)? = null,
    ): ComponentActivity {
        FormFactor.override(true)
        // A television is never in touch mode, and in touch mode Compose lets nothing pressable
        // take the focus; the test window starts in it, so it is put the way a television is.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(
                    chats = chats,
                    listComplete = true,
                    starred = emptySet(),
                    model = model,
                    onClose = {},
                    folders = folders,
                    folderStatus = folderStatus,
                    onRetryFolders = onRetryFolders,
                    required = required,
                    onLeave = onLeave,
                    confirmLeave = confirmLeave,
                    onContinue = onContinue,
                    // A stand-in for the television's search box: a press is its keyboard's Search.
                    searchField = if (!search) null else { _, _, _, modifier, onSubmit ->
                        Text("search probe", modifier.clickable { onSubmit() })
                    },
                )
            }
        }
        idle()
        return activity
    }

    /** A frame drawn and the clock run on: a Robolectric window lays a changed list out when it draws, not before. */
    private fun settle(activity: ComponentActivity) {
        draw(activity)
        idle()
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun press(activity: ComponentActivity, code: Int) {
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    private fun hold(activity: ComponentActivity, code: Int, repeats: Int) {
        val down = SystemClock.uptimeMillis()
        for (repeat in 0..repeats) {
            activity.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, repeat))
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)) }
        }
        activity.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    private fun words(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** Every word on the focused control. */
    private fun focusedWords(activity: ComponentActivity): List<String> =
        nodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let(::words).orEmpty()

    /** The focused row's chat name: its first words past the avatar's letter. */
    private fun focusedTitle(activity: ComponentActivity): List<String> = focusedWords(activity).filter { it.length > 1 }.take(1)

    private fun click(activity: ComponentActivity, text: String) {
        val target = nodes(activity).first { words(it).contains(text) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
    }

    private fun draw(activity: ComponentActivity): Bitmap {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(name: String, bitmap: Bitmap) {
        val dirs = listOfNotNull(
            File("../build/device-test/review").absoluteFile,
            System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File),
        )
        for (dir in dirs) {
            val file = dir.apply { mkdirs() }.resolve(name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 500)
        }
    }

    private companion object {
        fun chat(id: Long, title: String, kind: ChatKind = ChatKind.Channel) =
            ChatSummary(id, title, miniThumbnail = null, photoFileId = 0, kind = kind)

        /** Saved Messages and 2,999 more: the size of the account the report came from. */
        val CHATS: List<ChatSummary> = listOf(chat(100, "Saved Messages", ChatKind.Saved)) +
            (1..2_999).map { chat(1_000L + it, "Chat number $it") }

        /** Every chat is empty: nothing reaches the Recommended bar. */
        val NoVideos = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = CountAnswer.Counted(0)
        }

        /** The first twelve hold 50 videos, every seventh after them 5, the rest none. */
        val Counts = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer =
                CountAnswer.Counted(
                    when {
                        chatId in 1_001L..1_012L -> 50
                        chatId % 7 == 0L -> 5
                        else -> 0
                    },
                )
        }

        /** Saved Messages, five chats with videos and twenty five without. */
        val SMALL: List<ChatSummary> = listOf(chat(100, "Saved Messages", ChatKind.Saved)) +
            (1..5).map { chat(2_000L + it, "Loud $it") } + (1..25).map { chat(3_000L + it, "Quiet $it") }

        val SmallCounts = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer =
                CountAnswer.Counted(if (chatId in 2_001L..2_005L) 3 else 0)
        }

        val FOLDERS = listOf(
            com.tmplayer.data.ChatFolderSummary(7, "Films", icon = "Custom"),
            com.tmplayer.data.ChatFolderSummary(8, "Family", icon = "Custom"),
        )

        /** SMALL with the loud chats in Films, and three of them and two quiet ones in Family. */
        val FOLDERED: List<ChatSummary> = SMALL.map { chat ->
            val folders = buildList {
                if (chat.id in 2_001L..2_005L) add(7)
                if (chat.id in 2_003L..2_005L || chat.id in 3_001L..3_002L) add(8)
            }
            chat.copy(folderIds = folders)
        }
    }
}

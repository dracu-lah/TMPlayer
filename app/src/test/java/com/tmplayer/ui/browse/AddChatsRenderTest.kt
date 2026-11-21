package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.tmplayer.data.ChatCount
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.CountAnswer
import com.tmplayer.data.FormFactor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.SetupOffline
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.awaitCancellation
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
 * The Add chats picker (HX27) on a phone held upright and on a television, over a long account:
 * once with every count answered (the Recommended band, a chat that could not be counted, one past
 * the exact figures) and once with the counter silent (every row on its placeholder). Frames go
 * to `build/device-test/review/` and to TMPLAYER_SHOTS=<dir> when that is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddChatsRenderTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        // A test's cancelled counts hand their permits back on the looper; left unrun they would
        // hold the app wide counter's three for the next test, whose counts would never land.
        idle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phone() {
        val model = AddChatsViewModel(counts = Counts)
        save("add-chats-phone.png", render(tv = false, model, CHATS))
        // The head of the list is counted first, which the recommendation is made from, and the
        // rest of the account behind it, in the background: no row shows a figure (T5).
        val counted = model.state.value.counted
        assertTrue("the first row was counted", counted.containsKey(CHATS.first().id))
        model.stop()
    }

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun tv() {
        val model = AddChatsViewModel(counts = Counts)
        save("add-chats-tv.png", render(tv = true, model, CHATS))
        model.stop()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `rows hold their place while the counts are still coming`() {
        val model = AddChatsViewModel(counts = Silent)
        save("add-chats-counting-phone.png", render(tv = false, model, SILENT_CHATS))
        // Nothing answered, so nothing is recommended, only Saved Messages is ticked (HX86), and every row is a placeholder.
        assertTrue(model.state.value.recommended.isEmpty())
        assertEquals(setOf(50_100L), model.state.value.ticked)
        model.stop()
        // The cancelled counts hand their permits back on the looper; left unrun they would hold
        // all three for the next test, whose counts would then never land.
        idle()
    }

    /**
     * The mandatory chat choice (ChatsGate) on a phone: its own heading, no Back button, Done not
     * pressable until a chat is ticked, and the system Back leaves rather than closing or asking
     * to discard.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `required mode has no Back, needs a tick and leaves on Back`() {
        FormFactor.override(false)
        val model = AddChatsViewModel(write = {})
        var closed = 0
        var left = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(
                    chats = CHATS,
                    listComplete = true,
                    starred = emptySet(),
                    model = model,
                    onClose = { closed++ },
                    required = true,
                    onLeave = { left++ },
                )
            }
        }
        idle()
        assertTrue(node(activity, "Which chats have your videos?") != null)
        assertTrue("no Back button", node(activity, "Back", clickable = true) == null)
        // Saved Messages is ticked for a fresh start (HX86), so Done is pressable at once.
        assertTrue("Done is pressable with Saved Messages ticked", node(activity, "Done", clickable = true) != null)
        assertTrue(node(activity, "Choose at least one chat") == null)

        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(1, left)
        assertEquals(0, closed)
        assertTrue("never asks to discard", node(activity, "Discard changes?") == null)

        click(activity, "Saved Messages")
        idle()
        assertTrue(node(activity, "Choose at least one chat") != null)
        assertTrue("Done waits for a tick", node(activity, "Choose at least one chat", clickable = true) == null)
        click(activity, "Harbour Notes")
        idle()
        assertTrue("a tick makes Done pressable", node(activity, "Done", clickable = true) != null)
        // No frame is drawn here: drawing the window while the tick box still animates left the
        // native canvas in a state that stopped the next test's counts from landing.
        model.stop()
    }

    /**
     * The Recommended band's one button (ON-3) ticks the whole band on a press and takes it out on
     * the next, and Back in the required choice with chats ticked asks before it leaves (ON-5):
     * No leaves the ticks alone, Leave goes. Back with nothing ticked is the test above.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the band's button adds the band and Back with ticks asks before leaving`() {
        com.tmplayer.data.ChatVideoCount.forget()
        FormFactor.override(false)
        val model = AddChatsViewModel(counts = Counts, write = {}, countsRest = false)
        var left = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(
                    chats = CHATS, listComplete = true, starred = emptySet(), model = model, onClose = {},
                    required = true, onLeave = { left++ }, confirmLeave = true,
                )
            }
        }
        idle()
        assertTrue("three chats reach the bar: ${model.state.value}", model.state.value.recommended.size == 3)
        assertEquals("only Saved Messages is ticked for a fresh start", setOf(100L), model.state.value.ticked)
        click(activity, "Add these 3 chats")
        idle()
        assertEquals(model.state.value.recommended.toSet() + 100L, model.state.value.ticked)
        assertTrue("the button now takes them out", node(activity, "Remove these 3 chats", clickable = true) != null)
        click(activity, "Remove these 3 chats")
        idle()
        assertEquals(setOf(100L), model.state.value.ticked)
        click(activity, "Add these 3 chats")
        idle()

        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("Back with ticks asks first", 0, left)
        assertTrue(node(activity, "Leave without choosing?") != null)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("Back from the question keeps the choice", node(activity, "Leave without choosing?") == null)
        assertEquals(4, model.state.value.ticked.size)
        assertEquals(0, left)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        click(activity, "Leave")
        idle()
        assertEquals(1, left)
        model.stop()
    }

    /**
     * The picker reopened over a choice already made (ON-12): the added chats lead in their own
     * band, are not listed again under Recommended, and the band is not held behind skeletons.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `reopened over a choice the added chats lead and appear once`() {
        com.tmplayer.data.ChatVideoCount.forget()
        FormFactor.override(false)
        val file = File.createTempFile("added", ".preferences_pb").apply { delete() }
        val store = com.tmplayer.data.SettingsStore(com.tmplayer.data.SettingsStore.openDataStore(file))
        kotlinx.coroutines.runBlocking { store.setAddedChats(listOf(com.tmplayer.data.AddedChat(101, "Harbour Notes"))) }
        val model = AddChatsViewModel(store, Counts, write = {}, countsRest = false).also { it.open() }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(chats = CHATS, listComplete = true, starred = emptySet(), model = model, onClose = {})
            }
        }
        val until = System.nanoTime() + 5_000_000_000L
        while (model.state.value.added.isEmpty() && System.nanoTime() < until) {
            Thread.sleep(20)
            idle()
        }
        idle()
        assertEquals(setOf(101L), model.state.value.added)
        assertTrue("an Added band heads the added chats: ${nodes(activity).map { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("|") { it.text } }.filter { it.isNotBlank() }}", node(activity, "1 CHAT ADDED") != null)
        assertEquals("the chat is listed once", 1, nodes(activity).count { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Harbour Notes" } })
        assertTrue(model.state.value.recommended.isNotEmpty() && 101L !in model.state.value.recommended)
        assertFalse(model.state.value.recommending)
        model.stop()
        file.delete()
    }

    /** A save that fails says so with Try again, rather than sitting on the press for ever. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a failed save shows the error and Try again`() {
        FormFactor.override(false)
        var tries = 0
        val model = AddChatsViewModel(write = {
            tries++
            throw java.io.IOException("No space left on device")
        })
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(chats = CHATS, listComplete = true, starred = emptySet(), model = model, onClose = {}, required = true)
            }
        }
        idle()
        click(activity, "Harbour Notes")
        idle()
        click(activity, "Done")
        idle()
        assertTrue("the error is kept", model.state.value.error != null)
        assertFalse("not stuck saving", model.state.value.saving)
        assertFalse(model.state.value.saved)
        assertTrue(node(activity, "Try again", clickable = true) != null)
        // Nothing tries again by itself; Try again does.
        assertEquals(1, tries)
        save("add-chats-save-failed-phone.png", draw(activity))
        click(activity, "Try again")
        idle()
        assertEquals(2, tries)
        model.stop()
    }

    /**
     * No chat holds videos (R3): the question still shows what there is, Saved Messages first, and
     * says to forward a video there. The way on for this visit stays under it, and Done stays off.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `an account with only Saved Messages is told to forward a video there`() {
        FormFactor.override(false)
        val model = AddChatsViewModel(counts = EmptyCounts, write = {})
        var went = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(
                    chats = listOf(CHATS.first { it.kind == ChatKind.Saved }),
                    listComplete = true,
                    starred = emptySet(),
                    model = model,
                    onClose = {},
                    required = true,
                    onContinue = { went++ },
                )
            }
        }
        idle()
        assertTrue(node(activity, "Which chats have your videos?") != null)
        assertTrue("Saved Messages is the row", node(activity, "Saved Messages", clickable = true) != null)
        // Saved Messages is ticked for a fresh start (HX86): Done works and nothing is asked. Untick it
        // and the hint and the way on come back.
        assertTrue("Done works with Saved Messages ticked", node(activity, "Done", clickable = true) != null)
        assertTrue(node(activity, "Forward a video to Saved Messages in Telegram, then tick Saved Messages here.") == null)
        click(activity, "Saved Messages")
        idle()
        assertTrue(
            "the hint says what to do",
            node(activity, "Forward a video to Saved Messages in Telegram, then tick Saved Messages here.") != null,
        )
        assertTrue("Done waits for a tick", node(activity, "Done", clickable = true) == null)
        save("setup-question-saved-only-phone.png", draw(activity))
        click(activity, "Continue without choosing")
        assertEquals(1, went)
        model.stop()
        idle()
    }

    /**
     * The All chats switch on a phone (HX86): above the search, off at first, a screen reader hears
     * Off then On, the ticks are disabled while it is on and come back with it off, and Done works in
     * the required choice with nothing ticked.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the All chats switch on a phone disables the ticks while on and gives them back off`() {
        com.tmplayer.data.ChatVideoCount.forget()
        FormFactor.override(false)
        val written = ArrayList<com.tmplayer.data.AddedChoice>()
        val model = AddChatsViewModel(counts = Counts, write = {}, writeChoice = { written += it }, countsRest = false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(chats = CHATS, listComplete = true, starred = emptySet(), model = model, onClose = {}, required = true)
            }
        }
        idle()
        val switchNode = { nodes(activity).first { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "All chats" } && n.config.getOrNull(SemanticsActions.OnClick) != null } }
        assertEquals("Off to a screen reader", "Off", switchNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("above the search", node(activity, "Search") != null)
        click(activity, "Harbour Notes")
        idle()
        assertEquals(setOf(100L, 101L), model.state.value.ticked)
        click(activity, "All chats")
        idle()
        assertTrue(model.state.value.allChats)
        assertEquals("On to a screen reader", "On", switchNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("the line says why things are greyed", node(activity, "All chats is on") != null)
        assertTrue("Add these N is greyed", node(activity, "Add these 3 chats")?.config?.contains(SemanticsProperties.Disabled) == true)
        assertTrue("a row takes no press while it is on", node(activity, "Mira", clickable = true) == null)
        assertTrue("the row is still listed", node(activity, "Mira") != null)
        model.toggle(104)
        assertEquals("a tick is ignored while it is on", setOf(100L, 101L), model.state.value.ticked)
        click(activity, "All chats")
        idle()
        assertEquals("the kept ticks are back", setOf(100L, 101L), model.state.value.ticked)
        click(activity, "All chats")
        idle()
        click(activity, "Done")
        idle()
        assertEquals(1, written.size)
        assertEquals(com.tmplayer.data.AddedMode.All, written.single().mode)
        assertEquals(listOf(100L, 101L), written.single().chats.map { it.id })
        model.stop()
        idle()
        com.tmplayer.data.ChatVideoCount.forget()
    }

    /** The offline card (R3): the same words and two buttons on a phone and on a television. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the setup's offline card on a phone`() {
        offlineCard(tv = false, shot = "setup-offline-phone.png")
    }

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the setup's offline card on a television`() {
        offlineCard(tv = true, shot = "setup-offline-tv.png")
    }

    private fun offlineCard(tv: Boolean, shot: String) {
        FormFactor.override(tv)
        if (tv) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        com.tmplayer.data.ChatsGate.clearWaiver()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { TmMaterialTheme(dark = true) { SetupOffline() } }
        idle()
        assertTrue(node(activity, "You are offline") != null)
        assertTrue(node(activity, "TMPlayer needs the internet once to list your chats. It carries on by itself when you are back online.") != null)
        assertTrue(node(activity, "Try again", clickable = true) != null)
        assertTrue(node(activity, "Continue without choosing", clickable = true) != null)
        save(shot, draw(activity))
        click(activity, "Continue without choosing")
        assertTrue("the way on waives the question for this visit", com.tmplayer.data.ChatsGate.waived.value)
        com.tmplayer.data.ChatsGate.clearWaiver()
    }

    /** The Tips card (R3) in place of the modal tour offer: Show me opens the tour, Not now turns it off. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the Tips card on a phone`() {
        tipsCard(tv = false, shot = "tips-card-phone.png")
    }

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the Tips card on a television`() {
        tipsCard(tv = true, shot = "tips-card-tv.png")
    }

    private fun tipsCard(tv: Boolean, shot: String) {
        FormFactor.override(tv)
        if (tv) androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        var shown = 0
        var dismissed = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.setContent {
            TmMaterialTheme(dark = true) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                ) {
                    com.tmplayer.ui.onboarding.TipsCard(onShow = { shown++ }, onDismiss = { dismissed++ })
                }
            }
        }
        idle()
        assertTrue(node(activity, "Tips") != null)
        assertTrue(node(activity, "A short tour of where your videos come from, the player, downloads and Settings.") != null)
        assertTrue("no dialog: the card is plain content, not a window", node(activity, "Take a quick tour?") == null)
        save(shot, draw(activity))
        click(activity, "Show me")
        assertEquals(1, shown)
        click(activity, "Not now")
        assertEquals(1, dismissed)
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    /** Every merged semantics node of the window's Compose views. */
    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    /** The node whose words include [text] (and that can be pressed, when [clickable]), or null. */
    private fun node(activity: ComponentActivity, text: String, clickable: Boolean = false): SemanticsNode? =
        nodes(activity).firstOrNull { node ->
            val words = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            words.any { it == text } && (!clickable || node.config.getOrNull(SemanticsActions.OnClick) != null)
        }

    private fun click(activity: ComponentActivity, text: String) {
        val target = node(activity, text, clickable = true) ?: error(
            "nothing pressable reads $text among " +
                nodes(activity).map { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("|") { it.text } }.filter { it.isNotBlank() },
        )
        target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
    }

    private fun draw(activity: ComponentActivity): Bitmap {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun render(tv: Boolean, model: AddChatsViewModel, chats: List<ChatSummary>): Bitmap {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(chats = chats, listComplete = true, starred = setOf(STARRED), model = model, onClose = {})
            }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
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
        /** A chat Telegram would not count, and one holding more than the exact figures go to. */
        const val UNCOUNTABLE = 105L
        const val HUGE = 103L
        const val STARRED = 102L

        fun chat(id: Long, title: String, kind: ChatKind = ChatKind.Channel, archived: Boolean = false) =
            ChatSummary(id, title, miniThumbnail = null, photoFileId = 0, kind = kind, isArchived = archived)

        /** Saved Messages, a few named chats and then two hundred more, so most of it is off screen. */
        val CHATS: List<ChatSummary> = listOf(chat(100, "Saved Messages", ChatKind.Saved)) +
            listOf(
                chat(101, "Harbour Notes", ChatKind.Channel),
                chat(STARRED, "Lantern Club", ChatKind.Group),
                chat(HUGE, "Night Lantern Archive"),
                chat(104, "Mira", ChatKind.Direct),
                chat(UNCOUNTABLE, "Quiet Reels"),
            ) + (1..200).map { chat(1_000L + it, "Chat number $it", archived = it % 5 == 0) }

        /** Its own ids: the counter keeps what it counted, and these must start from nothing. */
        val SILENT_CHATS: List<ChatSummary> = CHATS.map { it.copy(id = it.id + 50_000L) }

        val Counts = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = when (chatId) {
                UNCOUNTABLE -> CountAnswer.Failed("Bad Request")
                HUGE -> CountAnswer.Counted(4_200)
                101L -> CountAnswer.Counted(340)
                STARRED -> CountAnswer.Counted(57)
                else -> CountAnswer.Counted((chatId % 9).toInt())
            }
        }

        /** Every chat counts as holding no video: nothing is recommended. */
        val EmptyCounts = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = CountAnswer.Counted(0)
        }

        val Silent = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = awaitCancellation()
        }
    }
}

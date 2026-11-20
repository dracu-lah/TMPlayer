package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ChatsGate
import com.tmplayer.data.CountAnswer
import com.tmplayer.data.DeviceFit
import com.tmplayer.data.DeviceFitOverride
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SetupFlow
import com.tmplayer.ui.browse.AddChatsScreen
import com.tmplayer.ui.browse.AddChatsViewModel
import com.tmplayer.ui.browse.SetupOffline
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.onboarding.SetupFlowEffects
import com.tmplayer.ui.onboarding.TipsCard
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.TmSchemes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The setup after sign in as one question (R3) on the desktop: the picker in its required form at
 * a narrow, a medium and a wide window in both themes (the phone's and the television's own frames
 * are in the Android render tests), the offline card, the Tips card, what runs beside the question
 * ([SetupFlowEffects]) and a fresh install's defaults. With TMPLAYER_SHOTS=<dir> the frames are
 * written there.
 */
@OptIn(ExperimentalTestApi::class)
class OneQuestionSetupTest {

    @After
    fun reset() {
        DeviceFitOverride.forced = null
        ChatsGate.clearWaiver()
    }

    @Test
    fun `the question draws at every width in both themes, Saved Messages ticked and Done on`() {
        for ((w, h) in listOf(420 to 860, 820 to 1000, 1280 to 800)) {
            for (dark in listOf(true, false)) {
                val model = AddChatsViewModel(counts = Counts, write = {})
                frame("setup-question-$w-${if (dark) "dark" else "light"}", w, h, dark) {
                    CompositionLocalProvider(LocalBackStack provides BackStack()) {
                        AddChatsScreen(
                            chats = CHATS, listComplete = true, starred = emptySet(), model = model,
                            onClose = {}, required = true,
                        )
                    }
                }
                assertEquals("Saved Messages is ticked for a fresh start (HX86)", setOf(200L), model.state.value.ticked)
                model.stop()
            }
        }
    }

    @Test
    fun `the question is the heading, with no count under any row and no Back button`() = runComposeUiTest {
        val model = AddChatsViewModel(counts = Counts, write = {})
        setContent {
            CompositionLocalProvider(LocalBackStack provides BackStack()) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(1280.dp, 900.dp)) {
                        AddChatsScreen(chats = CHATS, listComplete = true, starred = emptySet(), model = model, onClose = {}, required = true)
                    }
                }
            }
        }
        waitForIdle()
        onNode(hasText("Which chats have your videos?")).assertExists()
        onNode(hasText("Back") and hasClickAction()).assertDoesNotExist()
        onNode(hasText("Choose at least one chat")).assertDoesNotExist()
        // No video counts anywhere (user, 2026-11-16).
        val countLine = SemanticsMatcher("a video count") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { Regex("\\d+\\+? videos").containsMatchIn(it.text) }
        }
        onAllNodes(countLine).assertCountEquals(0)
        model.stop()
    }

    /** A tick, then Done: the choice is written and the picker closes, which ends the setup. */
    @Test
    fun `ticking a chat makes Done work and writes the choice`() = runComposeUiTest {
        val written = mutableListOf<List<Long>>()
        val model = AddChatsViewModel(counts = Counts, write = { written += it.map { chat -> chat.id } })
        var closed = 0
        setContent {
            CompositionLocalProvider(LocalBackStack provides BackStack()) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(1280.dp, 900.dp)) {
                        AddChatsScreen(chats = CHATS, listComplete = true, starred = emptySet(), model = model, onClose = { closed++ }, required = true)
                    }
                }
            }
        }
        waitForIdle()
        onNode(hasText("Done") and hasClickAction()).assertExists()
        onNode(hasText("Saved Messages") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Done") and hasClickAction()).assertDoesNotExist()
        onNode(hasText("Harbour Notes") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Done") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 3_000) { closed == 1 }
        assertEquals(listOf(listOf(201L)), written)
        model.stop()
    }

    @Test
    fun `an account with nothing but Saved Messages is told to forward a video there`() = runComposeUiTest {
        val model = AddChatsViewModel(counts = NoVideos, write = {})
        var went = 0
        setContent {
            CompositionLocalProvider(LocalBackStack provides BackStack()) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(1280.dp, 900.dp)) {
                        AddChatsScreen(
                            chats = listOf(CHATS.first()), listComplete = true, starred = emptySet(), model = model,
                            onClose = {}, required = true, onContinue = { went++ },
                        )
                    }
                }
            }
        }
        waitForIdle()
        // Ticked for a fresh start (HX86): the hint comes once the viewer unticks it.
        onNode(hasText("Forward a video to Saved Messages in Telegram, then tick Saved Messages here.")).assertDoesNotExist()
        onNode(hasText("Saved Messages") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Forward a video to Saved Messages in Telegram, then tick Saved Messages here.")).assertExists()
        onNode(hasText("Saved Messages") and hasClickAction()).assertExists()
        onNode(hasText("Continue without choosing") and hasClickAction()).performClick()
        assertEquals(1, went)
        model.stop()
    }

    @Test
    fun `the offline card says so, keeps the remote on Try again and offers the way on`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { Box(Modifier.size(1280.dp, 800.dp)) { SetupOffline() } } }
        waitForIdle()
        onNode(hasText("You are offline")).assertExists()
        onNode(hasText("Try again") and hasClickAction() and isFocused()).assertExists()
        onNode(hasText("Continue without choosing") and hasClickAction()).performClick()
        assertTrue(ChatsGate.waived.value)
        for ((w, h) in listOf(420 to 860, 1280 to 800)) {
            frame("setup-offline-$w", w, h, dark = true) { SetupOffline() }
        }
    }

    @Test
    fun `the Tips card is a card, Show me opens the tour and Not now turns it off`() = runComposeUiTest {
        var shown = 0
        var dismissed = 0
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(900.dp, 300.dp)) { TipsCard(onShow = { shown++ }, onDismiss = { dismissed++ }) }
            }
        }
        waitForIdle()
        onNode(hasText("Tips")).assertExists()
        onNode(hasText("Take a quick tour?")).assertDoesNotExist()
        onNode(hasText("Show me") and hasClickAction()).performClick()
        onNode(hasText("Not now") and hasClickAction()).performClick()
        assertEquals(1, shown)
        assertEquals(1, dismissed)
        for (dark in listOf(true, false)) {
            frame("tips-card-${if (dark) "dark" else "light"}", 900, 300, dark) { TipsCard(onShow = {}, onDismiss = {}) }
        }
    }

    /** Esc on the desktop goes back a page; only a remote's Back is "Not now", or one stray key turns the card off for good. */
    @Test
    fun `Esc on the desktop does not dismiss the Tips card`() = runComposeUiTest {
        var dismissed = 0
        val stack = BackStack()
        setContent {
            CompositionLocalProvider(LocalBackStack provides stack) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(900.dp, 300.dp)) { TipsCard(onShow = {}, onDismiss = { dismissed++ }) }
                }
            }
        }
        waitForIdle()
        assertFalse("nobody on the back stack wants Esc", stack.canGoBack)
        assertFalse(stack.handleBack())
        assertEquals(0, dismissed)
    }

    // What runs beside the question.

    @Test
    fun `a fresh install ends the setup with streaming mode on and crash reports off`() = runComposeUiTest {
        val settings = store()
        runBlocking { settings.armSetup() }
        var next by androidx.compose.runtime.mutableStateOf(SetupFlow.Next.Choose)
        setContent { SetupFlowEffects(settings, next, replay = false) }
        waitForIdle()
        assertTrue(runBlocking { settings.setupPending.first() })
        next = SetupFlow.Next.Finish
        waitUntil(timeoutMillis = 3_000) { !runBlocking { settings.setupPending.first() } }
        assertTrue("the streaming mode is on for a fresh install", runBlocking { settings.lowStorageModeNow() })
        assertFalse("crash reports are off", runBlocking { settings.crashReportsNow() })
        assertFalse("crash reports were not asked for", runBlocking { settings.crashReportsAsked.first() })
    }

    @Test
    fun `the device picks silently while the question is asked, a replay and an answered account write nothing`() = runComposeUiTest {
        // A device short of storage, with the streaming mode off: the recommendation turns it on.
        DeviceFitOverride.forced = DeviceFit(shortOfMemory = false, shortOfStorage = true)
        val asked = store()
        runBlocking { asked.setLowStorageMode(false) }
        setContent { SetupFlowEffects(asked, SetupFlow.Next.Wait, replay = false) }
        waitUntil(timeoutMillis = 3_000) { runBlocking { asked.lowStorageModeNow() } }

        val replayed = store()
        runBlocking { replayed.setLowStorageMode(false) }
        setContent { SetupFlowEffects(replayed, SetupFlow.Next.Choose, replay = true) }
        waitForIdle()
        Thread.sleep(300)
        assertFalse("a replay never overwrites a later choice", runBlocking { replayed.lowStorageModeNow() })

        val answered = store()
        runBlocking { answered.setLowStorageMode(false) }
        setContent { SetupFlowEffects(answered, SetupFlow.Next.Finish, replay = false) }
        waitForIdle()
        Thread.sleep(300)
        assertFalse("an account that had a setup writes nothing", runBlocking { answered.lowStorageModeNow() })
    }

    @Test
    fun `finishing marks the setup done once the question is answered or waived`() = runComposeUiTest {
        val settings = store()
        runBlocking { settings.armSetup() }
        setContent { SetupFlowEffects(settings, SetupFlow.Next.Finish, replay = false) }
        waitUntil(timeoutMillis = 3_000) { !runBlocking { settings.setupPending.first() } }
        assertFalse(runBlocking { settings.setupReplay.first() })
    }

    private fun store(): SettingsStore =
        SettingsStore(SettingsStore.openDataStore(Files.createTempDirectory("tm-one-question").resolve(SettingsStore.FILE_NAME).toFile()))

    private fun frame(name: String, w: Int, h: Int, dark: Boolean, content: @Composable () -> Unit) {
        val png = ImageComposeScene(w, h, Density(1f)) {
            TmMaterialTheme(dark = dark) { Surface(Modifier.size(w.dp, h.dp), color = com.tmplayer.ui.theme.Tone.background) { content() } }
        }.use { scene ->
            var now = 0L
            scene.render(now)
            repeat(12) {
                Thread.sleep(50)
                now += 100_000_000L
                scene.render(now)
            }
            now += 600_000_000L
            scene.render(now).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
        val ground = TmSchemes.of(dark).background.toArgb()
        var drawn = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) if (bitmap.getColor(x, y) != ground) drawn++
        assertTrue("$name has something on it", drawn > 50)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve("$name.png").writeBytes(png)
    }

    private companion object {
        fun chat(id: Long, title: String, kind: ChatKind = ChatKind.Channel) =
            ChatSummary(id, title, miniThumbnail = null, photoFileId = 0, kind = kind)

        val CHATS: List<ChatSummary> = listOf(chat(200, "Saved Messages", ChatKind.Saved)) +
            listOf(
                chat(201, "Harbour Notes"),
                chat(102, "Lantern Club", ChatKind.Group),
                chat(203, "Night Lantern Archive"),
                chat(204, "Mira", ChatKind.Direct),
            ) + (1..60).map { chat(2_000L + it, "Chat number $it") }

        val Counts = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = when (chatId) {
                203L -> CountAnswer.Counted(4_200)
                201L -> CountAnswer.Counted(340)
                102L -> CountAnswer.Counted(57)
                else -> CountAnswer.Counted((chatId % 9).toInt())
            }
        }

        val NoVideos = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = CountAnswer.Counted(0)
        }
    }
}

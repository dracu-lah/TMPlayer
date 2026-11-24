package com.tmplayer.ui.browse

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.BackupSend
import com.tmplayer.data.BackupSender
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SignOutBackup
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.settings.SettingsPage
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Sync across my devices and the backup before sign out (FQ12), on a phone and by remote
 * on a television: the one question's card (its words, its two buttons, the remote reaching them,
 * what Yes and Not now do and that it is asked once), and the sign out dialog's tick (on by
 * default, reachable by remote, a failed backup shown with the way out and Back returning to the
 * first dialog). Pictures go to `build/device-test/review/`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeepPlaceTest {

    private companion object {
        const val WRITE_MS = 10_000L
        const val TITLE = "Sync your progress across devices?"
        const val TICK = "Save my settings backup to my Saved Messages first"
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    // One store for the whole test, as Home keeps one: rememberKeepPlaceCardState remembers its
    // state by the store, so a new store on every read made a new state (keep = false) whenever
    // the stored flag recomposed the caller, and the card "vanished" depending on timing.
    private val settings by lazy { SettingsStore(RuntimeEnvironment.getApplication()) }

    @Before
    fun noWaitingForALine() {
        // Yes starts a real sync; with no Telegram it gives up at once, not after half a minute.
        com.tmplayer.data.ResumeSync.connectWaitMs = 50L
    }

    @After
    fun tearDown() {
        com.tmplayer.data.ResumeSync.connectWaitMs = 30_000L
        FormFactor.override(false)
        SignOutBackup.sender = com.tmplayer.data.SavedMessagesBackup
        runBlocking { settings.setResumeSync(false) }
    }

    private fun shot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    /** Scrolls whichever list holds [matcher] to it: a lazy list has no node for a row it has not drawn. */
    private fun reveal(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        val lists = rule.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction()).fetchSemanticsNodes().size
        for (i in 0 until lists) {
            if (rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) return
            runCatching { rule.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())[i].performScrollToNode(matcher) }
        }
    }

    private fun tvKeys() {
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    /** Home's end: a row of something the remote can stand on, then the card, as HomePane lays them out. */
    private fun home(tv: Boolean, fontScale: Float = 1f, yes: () -> Unit = {}, no: () -> Unit = {}, above: FocusRequester? = null) {
        FormFactor.override(tv)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TMPlayerTheme {
                    Box(Modifier.fillMaxSize().background(Tone.background)) {
                        LazyColumn {
                            item {
                                Box(
                                    Modifier.size(120.dp).background(Tone.surface)
                                        .then(if (above != null) Modifier.focusRequester(above) else Modifier)
                                        .focusable(),
                                )
                            }
                            item { KeepPlaceCard(onYes = yes, onNotNow = no, start = 16.dp, end = 16.dp) }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    // ---- the card -----------------------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the phone card says what is kept and where, with two buttons`() {
        var yes = 0
        var no = 0
        home(tv = false, yes = { yes++ }, no = { no++ })
        rule.onNodeWithText(TITLE).assertExists()
        rule.onNodeWithText("one small file in your own Saved Messages", substring = true).assertExists()
        rule.onNodeWithText("Nothing else is saved", substring = true).assertExists()
        rule.onNodeWithText("You can change this any time in Settings, Privacy and lock.").assertExists()
        shot("keep-place-card-phone-411.png")
        rule.onNode(hasText("Not now") and hasClickAction()).performScrollTo().performClick()
        rule.onNode(hasText("Yes, sync it") and hasClickAction()).performScrollTo().performClick()
        assertEquals(1, no)
        assertEquals(1, yes)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
    fun `the card at the smallest width and a large font keeps both buttons on the screen`() {
        home(tv = false, fontScale = 1.5f)
        rule.onNode(hasText("Yes, sync it") and hasClickAction()).performScrollTo().assertExists()
        rule.onNode(hasText("Not now") and hasClickAction()).performScrollTo().assertExists()
        shot("keep-place-card-phone-320-font15.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the remote reaches both buttons and presses them`() {
        var yes = 0
        var no = 0
        val above = FocusRequester()
        home(tv = true, yes = { yes++ }, no = { no++ }, above = above)
        tvKeys()
        rule.waitForIdle()
        shot("keep-place-card-tv-960.png")
        rule.runOnIdle { above.requestFocus() }
        rule.waitForIdle()
        // Down from the last row lands on Yes, sync it; Right goes on to Not now; Left comes back.
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNode(hasText("Yes, sync it") and hasClickAction()).assertIsFocused()
        rule.onNode(hasText("Yes, sync it") and hasClickAction()).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNode(hasText("Not now") and hasClickAction()).assertIsFocused()
        rule.onNode(hasText("Not now") and hasClickAction()).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        rule.onNode(hasText("Yes, sync it") and hasClickAction()).assertIsFocused()
        rule.onNode(hasText("Yes, sync it") and hasClickAction()).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(1, yes)
        assertEquals(0, no)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `after Yes the card goes and the remote stays on the row above, not lost`() {
        runBlocking { settings.setResumeSync(false) }
        val above = FocusRequester()
        FormFactor.override(true)
        rule.setContent {
            TMPlayerTheme {
                val state = rememberKeepPlaceCardState(settings, true) {}
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    LazyColumn {
                        item {
                            Box(Modifier.size(120.dp).background(Tone.surface).focusRequester(above).testTag("last-row").focusable())
                        }
                        if (state.visible) item { KeepPlaceCard(onYes = state::yes, onNotNow = state::notNow, start = 16.dp, end = 16.dp) }
                    }
                }
            }
        }
        tvKeys()
        rule.waitUntil(WRITE_MS) { rule.onAllNodesWithText("Yes, sync it").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { above.requestFocus() }
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNode(hasText("Yes, sync it") and hasClickAction()).assertIsFocused()
        rule.onNode(hasText("Yes, sync it") and hasClickAction()).performKeyInput { pressKey(Key.Enter) }
        rule.waitUntil(WRITE_MS) { rule.onAllNodesWithText("Yes, sync it").fetchSemanticsNodes().isEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag("last-row").assertIsFocused()
    }

    // ---- its state: asked once, off until a yes -----------------------------------------------

    /** One Home after another: [open] is a new visit, with the state it makes. */
    private inner class Visits(val said: MutableList<String> = mutableListOf()) {
        var visit by androidx.compose.runtime.mutableIntStateOf(0)
        var eligible by androidx.compose.runtime.mutableStateOf(true)
        lateinit var state: KeepPlaceCardState

        init {
            rule.setContent {
                androidx.compose.runtime.key(visit) { state = rememberKeepPlaceCardState(settings, eligible, say = { said += it }) }
            }
            rule.waitForIdle()
        }

        fun open() {
            visit++
            rule.waitForIdle()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the question is shown once and sync is off until a yes`() {
        runBlocking { settings.setResumeSync(false) }
        val visits = Visits()
        rule.waitUntil(WRITE_MS) { visits.state.visible }
        // Nothing is on before the answer.
        assertFalse(runBlocking { settings.resumeSyncNow() })
        visits.state.shown()
        rule.waitUntil(WRITE_MS) { !runBlocking { settings.keepPlaceCardPending.first() } }
        // Still drawn for this visit, gone for the next: a new Home asks nothing.
        assertTrue(visits.state.visible)
        assertFalse(runBlocking { settings.resumeSyncNow() })
        visits.open()
        assertFalse("not asked again", visits.state.visible)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `yes turns sync on and says so`() {
        runBlocking { settings.setResumeSync(false) }
        val visits = Visits()
        rule.waitUntil(WRITE_MS) { visits.state.visible }
        visits.state.yes()
        rule.waitUntil(WRITE_MS) { runBlocking { settings.resumeSyncNow() } }
        assertFalse(visits.state.visible)
        assertEquals(listOf("Your progress now syncs across your devices"), visits.said)
        assertFalse(runBlocking { settings.keepPlaceCardPending.first() })
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `not now leaves sync off for good`() {
        runBlocking { settings.setResumeSync(false) }
        val visits = Visits()
        rule.waitUntil(WRITE_MS) { visits.state.visible }
        visits.state.notNow()
        rule.waitUntil(WRITE_MS) { !runBlocking { settings.keepPlaceCardPending.first() } }
        assertFalse(visits.state.visible)
        assertFalse(runBlocking { settings.resumeSyncNow() })
        visits.open()
        assertFalse(visits.state.visible)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a Home with nothing to show or sync already on never asks`() {
        runBlocking { settings.setResumeSync(false) }
        val visits = Visits()
        visits.eligible = false
        visits.open()
        rule.waitForIdle()
        assertFalse(visits.state.visible)
        runBlocking { settings.setResumeSync(true) }
        visits.eligible = true
        visits.open()
        assertFalse(visits.state.visible)
    }

    // ---- the sign out dialog ------------------------------------------------------------------

    private fun account(tv: Boolean) {
        FormFactor.override(tv)
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = SettingsPage.Accounts, accountFolders = emptyList())
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `sign out offers the backup first, ticked, and a failed one shows the way out`() {
        SignOutBackup.sender = BackupSender { _, _ -> BackupSend.Failed("Telegram said no") }
        account(tv = false)
        reveal(hasText("Sign out of Telegram") and hasClickAction())
        rule.onNode(hasText("Sign out of Telegram") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sign out of Telegram?").assertExists()
        rule.onNodeWithText(TICK).assertExists()
        shot("keep-place-signout-phone-411.png")
        rule.onNode(hasText("Sign out") and hasClickAction()).performClick()
        rule.waitUntil(WRITE_MS) { rule.onAllNodesWithText("The backup was not saved").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("The backup could not be saved: Telegram said no").assertExists()
        rule.onNodeWithText("You are not signed out yet", substring = true).assertExists()
        rule.onNode(hasText("Sign out anyway") and hasClickAction()).assertExists()
        shot("keep-place-signout-failed-phone-411.png")
        // Back is the first dialog again, where the tick can be turned off or Sign out tried again.
        rule.onNode(hasText("Back") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sign out of Telegram?").assertExists()
        rule.onAllNodesWithText("The backup was not saved").assertCountEquals(0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `on a television the tick is a row of the dialog, on, and a press turns it off`() {
        account(tv = true)
        tvKeys()
        rule.waitForIdle()
        reveal(hasText("Sign out of Telegram") and hasClickAction())
        rule.onNode(hasText("Sign out of Telegram") and hasClickAction()).requestFocus()
        rule.onNode(hasText("Sign out of Telegram") and hasClickAction()).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        rule.onNodeWithText("Sign out of Telegram?").assertExists()
        val tick = hasText(TICK) and hasClickAction()
        rule.onNode(tick).assertExists()
        shot("keep-place-signout-tv-960.png")
        // The dialog is a window of its own, where the test's keys do not reach; what the remote
        // needs is on the node: a focusable, toggleable row that starts on, and a press turns it off.
        rule.onNode(tick).assertIsOn()
        rule.onNode(tick).assertHasClickAction()
        rule.onNode(tick).performClick()
        rule.waitForIdle()
        rule.onNode(tick).assertIsOff()
        rule.onNode(tick).performClick()
        rule.waitForIdle()
        rule.onNode(tick).assertIsOn()
        rule.onNode(hasText("Cancel") and hasClickAction()).assertIsFocused()
    }
}

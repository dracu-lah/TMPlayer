package com.tmplayer.ui.browse

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.FormFactor
import com.tmplayer.data.ListsStore
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.nio.file.Files
import java.time.Duration

/**
 * The named lists (HX43) on a television, by remote alone: "Add to a list" (the menu a video's or a
 * show's hold opens it from) starts on the first list, Down walks the lists to New list, and OK
 * there puts the remote in the name field; the lists' page starts on the shown list's chip, Down
 * reaches its buttons and then its first card.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ListsTvFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val dir = Files.createTempDirectory("tm-lists-tv").toFile()
    private val store: ListsStore = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME))).lists

    @After
    fun tearDown() {
        FormFactor.override(false)
        dir.deleteRecursively()
    }

    private fun video(message: Long) = WatchLaterRecord(
        chatId = -100, messageId = message, fileId = 7, title = "Clip $message", fileName = "Clip $message.mp4", chatTitle = "Chat",
        sizeBytes = 1_000, durationSec = 60, mimeType = "video/mp4", addedAt = 0,
    )

    private fun idle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(2)
        }
        rule.waitForIdle()
    }

    private fun leaveTouchMode(root: Any?) {
        ReflectionHelpers.callInstanceMethod<Any>(root, "ensureTouchMode", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.callInstanceMethod<Any>(root, "windowFocusChanged", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }

    /** The newest window (the picker's or the page's) takes the remote, as on a television. */
    private fun windowTakesKeys() {
        leaveTouchMode(rule.activity.window.decorView.parent)
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
        idle()
    }

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        idle()
    }

    private fun waitFor(text: String) {
        repeat(100) {
            idle()
            if (rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()) return
        }
        error("never showed: " + text)
    }

    private fun focusedText(): String = runCatching {
        rule.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { it.config.toString() }
    }.getOrDefault("nothing")

    private fun waitFocused(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        repeat(100) {
            idle()
            if (runCatching { rule.onNode(matcher).assertIsFocused() }.isSuccess) return
        }
        rule.onNode(matcher).assertIsFocused()
    }

    @Test
    fun `Add to a list starts on the first list and Down reaches New list`() {
        val lists = runBlocking {
            store.create("Tutorials")
            store.create("Weekend")
            store.visible.first()
        }
        FormFactor.override(true)
        rule.setContent { TmMaterialTheme(dark = true) { ListPickerMenu(video(1), lists, onPick = { _, _ -> }, onNew = {}, onDismiss = {}) } }
        waitFor("Weekend")
        windowTakesKeys()
        waitFocused(hasText("Tutorials"))
        press(Key.DirectionDown)
        rule.onNode(hasText("Weekend")).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNode(hasText("New list")).assertIsFocused()
        // OK there turns the card into the name dialog, whose field takes the remote (its blinking
        // cursor keeps Robolectric's clock from ever going idle, so that step is the desktop test's).
    }

    @Test
    fun `the lists' page starts on its list, Down reaches the buttons and the cards`() {
        val (id, lists) = runBlocking {
            val info = store.create("Weekend")!!
            store.add(info.id, video(1))
            store.add(info.id, video(2))
            info.id to store.visible.first()
        }
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                ListPage(
                    initial = id,
                    lists = lists,
                    store = store,
                    art = emptyMap(),
                    onArtWanted = {},
                    onOpen = {},
                    onDetails = {},
                    onMessage = {},
                    onDismiss = {},
                )
            }
        }
        waitFor("Clip 1")
        windowTakesKeys()
        waitFocused(hasText("Weekend"))
        press(Key.DirectionDown)
        org.junit.Assert.assertTrue("on " + focusedText(), runCatching { rule.onNode(hasText("Rename list")).assertIsFocused() }.isSuccess)
        press(Key.DirectionDown)
        // The first card of the grid, the one holding Clip 1.
        rule.onNode(isFocused()).assert(androidx.compose.ui.test.hasAnyAncestor(hasTestTag(LIST_GRID_TAG)) and androidx.compose.ui.test.hasClickAction())
    }
}

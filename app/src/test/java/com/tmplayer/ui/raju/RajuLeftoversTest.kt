package com.tmplayer.ui.raju

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.OpenDownloadsFolder
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.EmptySearchWays
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.BrandLoader
import com.tmplayer.ui.connect.FirstOpenPromiseView
import com.tmplayer.ui.connect.SignInHelpLink
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Raju pass 2 leftovers on a phone: 1.1 the loader's one line of promise on a first open, 2.4 the
 * sign in footer's help as words, 4.7 an empty search with one main button, 7.5 Open folder on an empty
 * Downloads page. Each is read at 100% and 200% font, every pressable thing at least 48 dp.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class RajuLeftoversPhoneTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun page(scale: Float, content: @Composable () -> Unit) {
        Motion.reduced = true
        FormFactor.override(false)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                TMPlayerTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun has(text: String) = assertTrue("missing: $text", RajuPass.count(rule, text) > 0)

    private fun thumbs() {
        val small = RajuPass.smallTargets(rule)
        assertTrue("targets under 48 dp: $small", small.isEmpty())
    }

    private fun press(text: String) {
        rule.onNode(hasText(text) and hasClickAction()).performClick()
        rule.waitForIdle()
    }

    private fun loader(scale: Float) {
        Motion.reduced = true
        FormFactor.override(false)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                TMPlayerTheme { BrandLoader(label = L.stateConnecting, sweepAt = 0.5f, below = { FirstOpenPromiseView() }) }
            }
        }
        rule.waitForIdle()
        has(L.stateFirstOpenPromise)
        // Fully on screen: the line is under the spinner, never cut off at the foot.
        val node = rule.onNode(hasText(L.stateFirstOpenPromise)).fetchSemanticsNode()
        val root = rule.onAllNodes(isRoot())[0].fetchSemanticsNode().boundsInRoot
        assertTrue("the promise is inside the window at $scale: ${node.boundsInRoot} in $root", node.boundsInRoot.bottom <= root.bottom && node.boundsInRoot.left >= 0f)
        RajuPass.shot(rule, "phone-loader-promise-${(scale * 100).toInt()}")
    }

    @Test
    fun `1_1 the loader says what is coming, under the spinner, at 100 percent`() = loader(1f)

    @Test
    fun `1_1 the loader says what is coming, under the spinner, at 200 percent font`() = loader(2f)

    private fun helpLink(scale: Float) {
        var proxy = 0
        var logs = 0
        page(scale) { SignInHelpLink(onProxy = { proxy++ }, onSendLogs = { logs++ }, sendLogsLabel = L.logsSend) }
        has(L.connectGetHelp)
        thumbs()
        press(L.connectGetHelp)
        has(L.loginProxySettings)
        has(L.logsSend)
        RajuPass.shot(rule, "phone-signin-help-${(scale * 100).toInt()}")
        press(L.loginProxySettings)
        assertEquals(1, proxy)
        press(L.connectGetHelp)
        press(L.logsSend)
        assertEquals(1, logs)
    }

    @Test
    fun `2_4 the sign in help is words that open the proxy and the logs, at 100 percent`() = helpLink(1f)

    @Test
    fun `2_4 the sign in help is words that open the proxy and the logs, at 200 percent font`() = helpLink(2f)

    private fun emptyWays(scale: Float) {
        val asked = mutableListOf<String>()
        page(scale) {
            EmptySearchWays(
                canSearchOthers = true,
                word = "night",
                onSearchOthers = { asked += "others" },
                onAddChats = { asked += "add" },
                onSearchWord = { asked += "word:$it" },
            )
        }
        has(L.searchOtherChats)
        has(L.pickerAddMore)
        has(L.searchSearchFor("night"))
        thumbs()
        // Top to bottom: the main way first.
        val tops = listOf(L.searchOtherChats, L.pickerAddMore, L.searchSearchFor("night"))
            .map { rule.onNode(hasText(it)).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("order on the page", tops.sorted(), tops)
        RajuPass.shot(rule, "phone-search-empty-${(scale * 100).toInt()}")
        press(L.searchOtherChats)
        press(L.pickerAddMore)
        press(L.searchSearchFor("night"))
        assertEquals(listOf("others", "add", "word:night"), asked)
    }

    @Test
    fun `4_7 an empty search has one main button and the rest as links, at 100 percent`() = emptyWays(1f)

    @Test
    fun `4_7 an empty search has one main button and the rest as links, at 200 percent font`() = emptyWays(2f)

    @Test
    fun `4_7 with nothing else to search the first way left is the main one`() {
        var added = 0
        page(1f) { EmptySearchWays(canSearchOthers = false, word = null, onSearchOthers = {}, onAddChats = { added++ }, onSearchWord = {}) }
        has(L.pickerAddMore)
        assertEquals(0, RajuPass.count(rule, L.searchOtherChats))
        thumbs()
        press(L.pickerAddMore)
        assertEquals(1, added)
    }

    private fun emptyDownloads(scale: Float) {
        var went = 0
        var opened = 0
        page(scale) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                BigEmpty(
                    L.downloadsEmptyDownloaded(folder = "TMPlayer"),
                    icon = TmIcons.Download,
                    actionLabel = L.browseGoToChats,
                    onAction = { went++ },
                    takeFocus = false,
                    linkLabel = L.downloadsOpenFolder,
                    onLink = { opened++ },
                )
            }
        }
        has(L.browseGoToChats)
        has(L.downloadsOpenFolder)
        thumbs()
        RajuPass.shot(rule, "phone-downloads-empty-folder-${(scale * 100).toInt()}")
        press(L.downloadsOpenFolder)
        assertEquals(1, opened)
        assertEquals(0, went)
    }

    @Test
    fun `7_5 an empty Downloads page offers Open folder as a link, at 100 percent`() = emptyDownloads(1f)

    @Test
    fun `7_5 an empty Downloads page offers Open folder as a link, at 200 percent font`() = emptyDownloads(2f)

    @Test
    fun `7_5 without a picked folder there is no link to a folder that cannot be opened`() {
        page(1f) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                BigEmpty(L.downloadsEmptyDownloaded(folder = "TMPlayer"), icon = TmIcons.Download, actionLabel = L.browseGoToChats, onAction = {}, takeFocus = false)
            }
        }
        assertEquals(0, RajuPass.count(rule, L.downloadsOpenFolder))
    }

    @Test
    fun `7_5 the folder intent shows the picked folder and a bad tree opens nothing`() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTMPlayer")
        val intent = OpenDownloadsFolder.intent(tree)
        assertNotNull(intent)
        assertEquals(android.content.Intent.ACTION_VIEW, intent!!.action)
        assertEquals("vnd.android.document/directory", intent.type)
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTMPlayer/document/primary%3ADownload%2FTMPlayer", intent.data.toString())
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // Not a tree: nothing to open, and nothing thrown.
        assertNull(OpenDownloadsFolder.intent(Uri.parse("content://x/y")))
        assertNull(OpenDownloadsFolder.intent(Uri.EMPTY))
        // Pressing it starts that intent, and a tree that is no folder starts nothing and does not crash.
        assertEquals(true, OpenDownloadsFolder.open(rule.activity, tree))
        assertEquals(intent.data, org.robolectric.Shadows.shadowOf(rule.activity).nextStartedActivity?.data)
        assertEquals(false, OpenDownloadsFolder.open(rule.activity, Uri.EMPTY))
    }
}

/** The loader's line on a television (the search's empty page is held by TvSearchScopeTest, which walks its pills with the remote). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class RajuLeftoversTvTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    @Test
    fun `1_1 the television's loader carries the same line`() {
        Motion.reduced = true
        FormFactor.override(true)
        rule.setContent { TMPlayerTheme { BrandLoader(label = L.stateConnecting, sweepAt = 0.5f, below = { FirstOpenPromiseView() }) } }
        rule.waitForIdle()
        assertTrue(RajuPass.count(rule, L.stateFirstOpenPromise) > 0)
        RajuPass.shot(rule, "tv-loader-promise")
    }
}

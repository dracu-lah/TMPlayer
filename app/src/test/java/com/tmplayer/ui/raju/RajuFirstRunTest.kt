package com.tmplayer.ui.raju

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
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.HomeEmptyCard
import com.tmplayer.ui.browse.HomeNoneChosenCard
import com.tmplayer.ui.browse.HomeOffCard
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Raju steps 3, 4 and 7 on a phone (the second pass): the first Home he lands on with nothing in
 * it, the card when no chat is chosen, the card when the library is off, and an empty Downloads
 * page. Each says what to do in words, has one button that works, and keeps it a thumb's size at
 * 100% and 200% font. No page is a dead end.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class RajuFirstRunPhoneTest {

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

    private fun has(text: String, substring: Boolean = false) =
        assertTrue("missing: $text", RajuPass.count(rule, text, substring) > 0)

    private fun thumbs() {
        val small = RajuPass.smallTargets(rule)
        assertTrue("targets under 48 dp: $small", small.isEmpty())
    }

    @Test
    fun `step 4, an empty Home says where a first video comes from and has one way on`() {
        var saved = 0
        page(1f) { HomeEmptyCard(onOpenSaved = { saved++ }) }
        has(L.homeFirstTitle)
        has(L.homeFirstOpenSaved)
        RajuPass.shot(rule, "phone-home-empty")
        thumbs()
        rule.onNode(hasText(L.homeFirstOpenSaved) and hasClickAction()).performClickAndIdle()
        assertEquals(1, saved)
    }

    @Test
    fun `step 4, an empty Home at 200 percent font still shows its words and its button`() {
        page(2f) { HomeEmptyCard(onOpenSaved = {}) }
        has(L.homeFirstTitle)
        has(L.homeFirstOpenSaved)
        RajuPass.shot(rule, "phone-home-empty-200")
        thumbs()
    }

    @Test
    fun `step 3, no chat chosen says so and the button opens the picker`() {
        var asked = 0
        page(1f) { HomeNoneChosenCard(start = 0.dp, end = 0.dp, onAddChats = { asked++ }) }
        has(L.pickerChooseChats)
        RajuPass.shot(rule, "phone-home-none-chosen")
        thumbs()
        rule.onNode(hasText(L.pickerChooseChats) and hasClickAction()).performClickAndIdle()
        assertEquals(1, asked)
    }

    @Test
    fun `step 3, no chat chosen at 200 percent font keeps the button`() {
        page(2f) { HomeNoneChosenCard(start = 0.dp, end = 0.dp, onAddChats = {}) }
        has(L.pickerChooseChats)
        RajuPass.shot(rule, "phone-home-none-chosen-200")
        thumbs()
    }

    @Test
    fun `step 4, a Home with the library off says so and opens Settings`() {
        var opened = 0
        page(1f) { HomeOffCard(onSettings = { opened++ }) }
        has(L.homeOffTitle)
        has(L.homeOpenSettings)
        RajuPass.shot(rule, "phone-home-off")
        thumbs()
        rule.onNode(hasText(L.homeOpenSettings) and hasClickAction()).performClickAndIdle()
        assertEquals(1, opened)
    }

    @Test
    fun `step 7, an empty Downloads page names the folder and offers the way on`() {
        var went = 0
        page(1f) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                BigEmpty(
                    L.downloadsEmptyDownloaded(folder = "TMPlayer"),
                    icon = TmIcons.Download,
                    actionLabel = L.browseGoToChats,
                    onAction = { went++ },
                    takeFocus = false,
                )
            }
        }
        has("Nothing downloaded yet", substring = true)
        has(L.browseGoToChats)
        RajuPass.shot(rule, "phone-downloads-empty")
        thumbs()
        // No "Free up space" on a page with nothing to free (7.3).
        assertEquals(0, RajuPass.count(rule, "Free up space", substring = true))
        rule.onNode(hasText(L.browseGoToChats) and hasClickAction()).performClickAndIdle()
        assertEquals(1, went)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickAndIdle() {
        performClick()
        rule.waitForIdle()
    }
}

/** The same cards on a television: the remote reaches the one button and OK presses it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class RajuFirstRunTvTest {

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

    private fun page(content: @Composable () -> Unit) {
        Motion.reduced = true
        FormFactor.override(true)
        rule.setContent { TMPlayerTheme { Column(Modifier.fillMaxSize().padding(48.dp)) { content() } } }
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitForIdle()
    }

    private fun pressOk() {
        rule.onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
    }

    @Test
    fun `step 4, the empty Home's button takes the remote and OK presses it`() {
        var saved = 0
        page { HomeEmptyCard(onOpenSaved = { saved++ }) }
        val button = rule.onNode(hasText(L.homeFirstOpenSaved) and hasClickAction())
        button.requestFocus()
        button.assertIsFocused()
        RajuPass.shot(rule, "tv-home-empty")
        pressOk()
        assertEquals(1, saved)
    }

    @Test
    fun `step 3, the no chat chosen card takes the remote and OK opens the picker`() {
        var asked = 0
        page { HomeNoneChosenCard(start = 0.dp, end = 0.dp, onAddChats = { asked++ }) }
        val button = rule.onNode(hasText(L.pickerChooseChats) and hasClickAction())
        button.requestFocus()
        button.assertIsFocused()
        RajuPass.shot(rule, "tv-home-none-chosen")
        pressOk()
        assertEquals(1, asked)
    }

    @Test
    fun `step 7, the empty Downloads page puts the remote on its button`() {
        var went = 0
        page {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                BigEmpty(
                    L.downloadsEmptyDownloaded(folder = "TMPlayer"),
                    icon = TmIcons.Download,
                    actionLabel = L.browseGoToChats,
                    onAction = { went++ },
                )
            }
        }
        // takeFocus is on by default: the remote is already on the button, never on nothing.
        rule.onNode(hasText(L.browseGoToChats) and hasClickAction()).assertIsFocused()
        RajuPass.shot(rule, "tv-downloads-empty")
        pressOk()
        assertEquals(1, went)
    }
}

package com.tmplayer.ui.downloads

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
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
 * WP-14: the Downloads page's wordless pills (Share, Save to folder, Delete) are named. A screen
 * reader gets the name from the pill's own description, once, and the remote sees it in a tip under
 * the pill while it is on it, as the browse headers' icons do. Delete keeps its confirm: the pill
 * only calls what it is given.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class DownloadsPillNamesTest {

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

    private val share = FocusRequester()
    private val delete = FocusRequester()
    private val calls = mutableListOf<String>()

    private fun open() {
        FormFactor.override(true)
        Motion.reduced = true
        // A window the remote works in: out of touch mode and holding focus, as on a television.
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    Row {
                        TvPill(L.commonShare, TmIcons.Share, showLabel = false, modifier = Modifier.focusRequester(share), onClick = { calls += "share" })
                        TvPill(L.commonDelete, TmIcons.Delete, danger = true, showLabel = false, modifier = Modifier.focusRequester(delete), onClick = { calls += "delete" })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a wordless pill carries its name for a screen reader, once`() {
        open()
        for (label in listOf(L.commonShare, L.commonDelete)) {
            val node = rule.onNodeWithContentDescription(label).fetchSemanticsNode()
            assertEquals(listOf(label), node.config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue("pressable", node.config.getOrNull(SemanticsActions.OnClick) != null)
        }
    }

    @Test
    fun `the remote on a wordless pill shows its word in a tip, and off it the tip goes`() {
        open()
        // At rest no word is drawn: only the description names it.
        assertEquals(0, rule.onAllNodes(hasText(L.commonDelete)).fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription(L.commonDelete).requestFocus()
        rule.waitForIdle()
        assertEquals("the tip names Delete", 1, rule.onAllNodes(hasText(L.commonDelete)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText(L.commonShare)).fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription(L.commonShare).requestFocus()
        rule.waitForIdle()
        assertEquals("the tip names Share", 1, rule.onAllNodes(hasText(L.commonShare)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText(L.commonDelete)).fetchSemanticsNodes().size)
    }

    @Test
    fun `OK on a wordless pill calls what it was given and nothing more`() {
        open()
        rule.onNodeWithContentDescription(L.commonDelete).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("delete"), calls)
    }
}

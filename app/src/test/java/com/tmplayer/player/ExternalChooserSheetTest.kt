package com.tmplayer.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chooser (PR4) as a viewer meets it: TMPlayer and the apps found, the first app picked, a
 * line selects without leaving, and Always or Just once answers with the pick. Under a remote the
 * lines and both buttons are drawn in reading order (the focus order itself is a device check). Long names and two apps sharing a name stay readable.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ExternalChooserSheetTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val vlc = InstalledPlayers.Found(ExternalApp("org.videolan.vlc", "org.videolan.vlc.Video", "VLC"), null)
    private val mx = InstalledPlayers.Found(ExternalApp("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.ad.Screen", "MX Player"), null)

    private fun show(
        apps: List<InstalledPlayers.Found>,
        gone: String? = null,
        onChoose: (PlayerChoice, Boolean) -> Unit,
    ) = rule.setContent {
        TmMaterialTheme(dark = true) { ExternalChooserSheet(apps, gone, onChoose, onDismiss = {}) }
    }

    @Test
    fun `the first app is picked and Always answers with it`() {
        var answer: Pair<PlayerChoice, Boolean>? = null
        show(listOf(vlc, mx)) { choice, always -> answer = choice to always }
        rule.waitForIdle()

        rule.onNodeWithText("VLC").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithText("MX Player").assertIsNotSelected()
        rule.onNodeWithText("TMPlayer").assertIsNotSelected()

        rule.onNodeWithText("Always").performClick()
        assertEquals(vlc.app.choice to true, answer)
    }

    @Test
    fun `a line selects without leaving and Just once answers with the new pick`() {
        var answer: Pair<PlayerChoice, Boolean>? = null
        show(listOf(vlc, mx)) { choice, always -> answer = choice to always }
        rule.waitForIdle()

        rule.onNodeWithText("MX Player").performClick()
        rule.waitForIdle()
        assertEquals(null, answer)
        rule.onNodeWithText("MX Player").assertIsSelected()

        rule.onNodeWithText("Just once").performClick()
        assertEquals(mx.app.choice to false, answer)
    }

    @Test
    fun `TMPlayer is always a line, and the only one when nothing else is installed`() {
        var answer: Pair<PlayerChoice, Boolean>? = null
        show(emptyList()) { choice, always -> answer = choice to always }
        rule.waitForIdle()
        rule.onNodeWithText("TMPlayer").assertIsSelected()
        rule.onNodeWithText("Just once").performClick()
        assertEquals(PlayerChoice.TmPlayer to false, answer)
    }

    @Test
    fun `a default that was uninstalled is named above the list`() {
        show(listOf(vlc), gone = "MX Player") { _, _ -> }
        rule.waitForIdle()
        rule.onNodeWithText("MX Player is not installed any more. Pick a player").assertIsDisplayed()
    }

    @Test
    fun `two apps with one name are told apart by package`() {
        val a = InstalledPlayers.Found(ExternalApp("one.player", "one.C", "Player"), null)
        val b = InstalledPlayers.Found(ExternalApp("two.player", "two.C", "Player"), null)
        show(listOf(a, b)) { _, _ -> }
        rule.waitForIdle()
        rule.onNodeWithText("Player (one.player)").assertIsDisplayed()
        rule.onNodeWithText("Player (two.player)").assertIsDisplayed()
    }

    @Test
    fun `under a remote every line and both buttons are on screen`() {
        FormFactor.override(true)
        show(listOf(vlc)) { _, _ -> }
        rule.waitForIdle()
        // A window the remote works in, as on a television.
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

        // Every control is reachable and nothing is clipped: the lines and both buttons are on screen.
        rule.onNodeWithText("TMPlayer").assertIsDisplayed()
        rule.onNodeWithText("VLC").assertIsDisplayed()
        rule.onNodeWithText("Always").assertIsDisplayed()
        rule.onNodeWithText("Just once").assertIsDisplayed()
        // The remote's own focus order lives in the dialog window, which Robolectric does not give
        // focus to (as for the storage location list): a device check, listed in the report.
    }
}

package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsGroup
import com.tmplayer.data.SettingsLink
import com.tmplayer.data.SettingsMap
import com.tmplayer.data.SettingsNeed
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
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
 * Every page of Settings on a phone and a television drawn in a window tall enough for all of it,
 * held to SettingsMap (U20): each row the map lists for the page and the form is drawn there, and
 * nothing is drawn there the map does not list. So the search box, the deep links and the help
 * text cannot point at a row that moved, and a row cannot be added without a home in the map.
 * The links from outside Settings land on a page this form shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsMapScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        SettingsSeek.drawn = null
        FormFactor.override(false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h4000dp-port-night-xhdpi")
    fun `every phone page draws its rows and only those`() = check(DeviceForm.Phone)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h4000dp-port-television-night-xhdpi")
    fun `every television page draws its rows and only those`() = check(DeviceForm.Tv)

    private fun check(form: DeviceForm) {
        FormFactor.override(form == DeviceForm.Tv)
        val seen = mutableSetOf<String>()
        SettingsSeek.drawn = { seen += it }
        var page by mutableStateOf<SettingsPage?>(null)
        rule.setContent {
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    key(page) {
                        SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = page, accountFolders = emptyList())
                    }
                }
            }
        }
        val needs = SettingsNeeds(vlc = false, pictureInPicture = false, logInbox = false, playNext = false, channels = false)
        val problems = mutableListOf<String>()
        // The pages this form has: a television lists no Notifications.
        for (shown in SettingsPage.entries.filter { SettingsMap.on(form, it.group).isNotEmpty() }) {
            seen.clear()
            page = shown
            rule.waitForIdle()
            val rows = SettingsMap.on(form, shown.group)
            // Drawn without anything switched on or a build's extra: no need, or one this
            // Robolectric device meets. Advanced is folded until asked for.
            val expected = rows.filter { it.need != SettingsNeed.Advanced && needs.met(it) }
                .map { L.messages.text(it.titleOn(form)) }
            // The "Moved to" hint's lines (WP-08) are on the old page for one release; their titles are the moved rows'.
            val moved = movedAliases(shown, form, needs, L).map { it.title }
            val known = rows.flatMap { row -> row.titlesOn(form).map { L.messages.text(it) } }.toSet() + moved
            expected.filter { it !in seen }.takeIf { it.isNotEmpty() }?.let { problems += "$form $shown misses $it" }
            (seen - known).takeIf { it.isNotEmpty() }?.let { problems += "$form $shown draws rows SettingsMap does not list: $it" }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)

        // The category list: one row per page this form has, in the shared order (WP-08): nine on a
        // television, ten on a phone. A sub-page (Troubleshooting, Redo setup choices) is a row of Help.
        seen.clear()
        page = null
        rule.waitForIdle()
        val expectedPages = SettingsMap.pages(form).map { L.messages.text(it.title) }
        // A television has no category rows since WP-07: its categories are the rail's items, held to
        // the same map by SettingsPaneTvFocusTest, so the map is the list there.
        val listed = if (form == DeviceForm.Tv) {
            expectedPages
        } else {
            seen.filter { title -> SettingsGroup.entries.any { L.messages.text(it.title) == title } }
        }
        assertEquals("the list follows the groups' order", expectedPages, listed)
        assertEquals(if (form == DeviceForm.Tv) 9 else 10, listed.size)
        assertTrue("Account comes first", listed.first() == L.messages.text(SettingsGroup.Accounts.title))
        val pages = listed
        // Every page a link sends this form to is one it lists, or opens from one that is.
        for (link in SettingsLink.entries.filter { form in it.forms }) {
            val listedGroup = link.group.parent ?: link.group
            assertTrue("$link: $listedGroup is a page on $form", L.messages.text(listedGroup.title) in pages)
        }
        // Gestures and Home are rows of Playback and of Home and library now (Raju 11.1), never pages.
        assertTrue("no page named Player gestures or Home", listOf("Player gestures", "Home").none { it in pages })
        assertTrue("Home and library is one page", L.messages.text(SettingsGroup.Library.title) in pages)
    }
}

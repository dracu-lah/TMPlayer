package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.UiScale
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.os.SystemAccessibility
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Desktop accessibility (WP-50): a page's name is a heading, a card at rest speaks as one line, the
 * OS readers parse what the OS prints, "follow the system" never overrides a choice, and the
 * desktop-only Huge size (1.5) steps and stays out of the shared sizes.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibilityDesktopTest {

    @Test
    fun `a page header is a heading for a screen reader`() = runComposeUiTest {
        setContent { TmMaterialTheme(dark = true) { Column { PageHeader(title = "Settings", subtitle = "Everything") } } }
        waitForIdle()
        val headings = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
        assertEquals(1, headings.size)
        assertEquals("Settings", headings.single().config[SemanticsProperties.Text].single().text)
    }

    @Test
    fun `a card's state is read after its title, percent first, and nothing is nothing`() {
        assertEquals("40 percent watched, Downloaded", tileSpokenState("40 percent watched", "Downloaded"))
        assertEquals("Selected", tileSpokenState(null, "Selected"))
        assertEquals("", tileSpokenState(null, null))
        assertEquals("", tileSpokenState("", " "))
    }

    @Test
    fun `percent watched is never none or all`() {
        assertNull(watchedPercentOf(null))
        assertNull(watchedPercentOf(0f))
        assertNull(watchedPercentOf(1f))
        assertEquals(40, watchedPercentOf(0.4f))
        assertEquals(99, watchedPercentOf(0.995f))
    }

    @Test
    fun `a choice beats the system, a stored on counts as a choice, and nothing says off`() {
        assertTrue(SystemDefaults.effective(chosen = true, stored = false, system = false))
        assertFalse(SystemDefaults.effective(chosen = false, stored = true, system = true))
        assertTrue(SystemDefaults.effective(chosen = null, stored = true, system = false))
        assertTrue(SystemDefaults.effective(chosen = null, stored = false, system = true))
        assertFalse(SystemDefaults.effective(chosen = null, stored = false, system = null))
        assertNull(SystemDefaults.shown(null, stored = false))
        assertEquals(true, SystemDefaults.shown(null, stored = true))
        assertEquals(false, SystemDefaults.shown(false, stored = true))
    }

    @Test
    fun `the OS readers parse what the OS prints and shrug at the rest`() {
        assertEquals(true, SystemAccessibility.windowsHighContrast("79"))
        assertEquals(false, SystemAccessibility.windowsHighContrast("78"))
        assertNull(SystemAccessibility.windowsHighContrast("junk"))
        assertNull(SystemAccessibility.windowsHighContrast(null))
        assertEquals(1.5f, SystemAccessibility.windowsTextScale(150), 0.001f)
        assertEquals(2.25f, SystemAccessibility.windowsTextScale(900), 0.001f)
        assertEquals(1f, SystemAccessibility.windowsTextScale(null), 0.001f)
        assertEquals(1f, SystemAccessibility.windowsTextScale(-5), 0.001f)
        assertEquals(true, SystemAccessibility.gsettingsBool("true\n"))
        assertEquals(false, SystemAccessibility.gsettingsBool("false"))
        assertNull(SystemAccessibility.gsettingsBool("No such schema"))
        assertEquals(1.25f, SystemAccessibility.gsettingsTextScale("1.25\n"), 0.001f)
        assertEquals(1f, SystemAccessibility.gsettingsTextScale("nan"), 0.001f)
        assertEquals(2.25f, SystemAccessibility.gsettingsTextScale("9.0"), 0.001f)
        assertEquals(true, SystemAccessibility.defaultsBool("1\n"))
        assertEquals(false, SystemAccessibility.defaultsBool("0"))
        assertNull(SystemAccessibility.defaultsBool("The domain/default pair does not exist"))
    }

    @Test
    fun `Huge is the desktop's own step at 1_5 and the shared sizes are unchanged`() {
        assertEquals(1.5f, DesktopSize.Huge.factor, 0.0001f)
        assertEquals(UiScale.entries.map { it.factor }, DesktopSize.entries.filter { it != DesktopSize.Huge }.map { it.factor })
        assertEquals(DesktopSize.Huge, DesktopSize.of(UiScale.Largest, huge = true))
        assertEquals(DesktopSize.Largest, DesktopSize.of(UiScale.Largest, huge = false))
        assertEquals(DesktopSize.Huge, DesktopSize.Largest.step(1))
        assertEquals(DesktopSize.Huge, DesktopSize.Huge.step(1))
        assertEquals(DesktopSize.Smaller, DesktopSize.Smaller.step(-1))
        assertEquals(DesktopSize.Largest, DesktopSize.Huge.step(-1))
        // The shared enum has no Huge: a phone or a TV never meets it.
        assertTrue(UiScale.entries.none { it.name == "Huge" })
    }

    @Test
    fun `the choices survive a restart and unchosen reads as follow the system`() {
        val file = Files.createTempDirectory("tm-a11y").resolve("desktop.properties").toFile()
        assertNull(DesktopPrefs(file).now.highContrast)
        assertNull(DesktopPrefs(file).now.reduceMotion)
        DesktopPrefs(file).update { it.copy(highContrast = false, reduceMotion = true, hugeSize = true) }
        val again = DesktopPrefs(file).now
        assertEquals(false, again.highContrast)
        assertEquals(true, again.reduceMotion)
        assertTrue(again.hugeSize)
        DesktopPrefs(file).update { it.copy(highContrast = null) }
        assertNull(DesktopPrefs(file).now.highContrast)
    }
}

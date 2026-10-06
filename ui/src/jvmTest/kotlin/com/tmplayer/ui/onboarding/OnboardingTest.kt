package com.tmplayer.ui.onboarding

import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OnboardingTest {

    @Test
    fun `the pages, in order`() {
        assertEquals(
            listOf(OnboardingPage.Language, OnboardingPage.About, OnboardingPage.SignIn, OnboardingPage.Chats, OnboardingPage.Videos),
            Onboarding.pages,
        )
        assertEquals(listOf(OnboardingPage.SignIn, OnboardingPage.Chats, OnboardingPage.Videos), Onboarding.pages.filter { it.illustrated })
    }

    @Test
    fun `the tour comes first whenever it is unseen, signed in or not`() {
        assertEquals(Entry.Tour, Onboarding.entry(signedIn = false, overviewSeen = false))
        assertEquals(Entry.Tour, Onboarding.entry(signedIn = true, overviewSeen = false))
        assertEquals(Entry.SignIn, Onboarding.entry(signedIn = false, overviewSeen = true))
        assertEquals(Entry.App, Onboarding.entry(signedIn = true, overviewSeen = true))
    }

    @Test
    fun `show the walkthrough again puts the tour over the app, and finishing it goes back`() = runBlocking {
        val file = Files.createTempDirectory("tm-tour").resolve(SettingsStore.FILE_NAME).toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(file))
        assertEquals(Entry.Tour, Onboarding.entry(false, settings.overviewSeen.first()))
        settings.markOverviewSeen()
        assertEquals(Entry.SignIn, Onboarding.entry(false, settings.overviewSeen.first()))
        assertEquals(Entry.App, Onboarding.entry(true, settings.overviewSeen.first()))
        settings.replayOverview()
        assertEquals(Entry.Tour, Onboarding.entry(true, settings.overviewSeen.first()))
        settings.markOverviewSeen()
        assertEquals(Entry.App, Onboarding.entry(true, settings.overviewSeen.first()))
    }

    @Test
    fun `on the first run the About page cannot be skipped past and Back does not leave`() {
        val tour = TourState(firstRun = true)
        assertFalse(tour.canLeave)
        assertFalse(tour.back())
        assertFalse("no Skip on Language", tour.canSkip)
        assertTrue(tour.next())
        assertEquals(OnboardingPage.About, tour.page)
        assertFalse("no Skip on About", tour.canSkip)
        assertTrue(tour.next())
        assertTrue("Skip once About is read", tour.canSkip)
        assertTrue(tour.next())
        assertTrue(tour.next())
        assertTrue(tour.isLast)
        assertFalse("Start, not Skip, on the last page", tour.canSkip)
        assertFalse(tour.next())
        assertTrue(tour.back())
        assertEquals(OnboardingPage.Chats, tour.page)
    }

    @Test
    fun `asked for again, the tour can be skipped and left from the first page`() {
        val tour = TourState(firstRun = false)
        assertTrue(tour.canSkip)
        assertTrue(tour.canLeave)
        assertFalse(tour.back())
        assertEquals(OnboardingPage.Videos, TourState(firstRun = false, start = 99).page)
    }

    @Test
    fun `each device reads its own wording`() {
        val s = Translator.english()
        for (page in Onboarding.pages) {
            val copies = DeviceForm.entries.map { s.onboarding(page, it) }
            for (copy in copies) {
                assertTrue("$page title", copy.title.isNotBlank())
                assertTrue("$page body", copy.body.isNotBlank())
                assertFalse("$page has a dash", Regex("[\u2013\u2014]").containsMatchIn(copy.title + copy.body))
            }
        }
        val chats = DeviceForm.entries.map { s.onboarding(OnboardingPage.Chats, it).body }
        assertEquals("phone, TV and desktop each say how they are operated", 3, chats.toSet().size)
        assertTrue(s.onboarding(OnboardingPage.Chats, DeviceForm.Tv).body.contains("press"))
        assertTrue(s.onboarding(OnboardingPage.Chats, DeviceForm.Desktop).body.contains("click"))
        assertTrue(s.onboarding(OnboardingPage.Chats, DeviceForm.Phone).body.contains("tap"))
        assertNotEquals(
            s.onboarding(OnboardingPage.SignIn, DeviceForm.Tv).title,
            s.onboarding(OnboardingPage.SignIn, DeviceForm.Phone).title,
        )
    }

    @Test
    fun `the About page carries the unofficial line Telegram asks for`() {
        val s = Translator.english()
        val about = s.onboarding(OnboardingPage.About, DeviceForm.Phone)
        assertEquals(OnboardingPoint.Kind.entries, about.points.map { it.kind })
        assertTrue(about.points.first().body.contains("unofficial app that uses the Telegram API"))
    }

    @Test
    fun `the language page lists the system first, then every language by its own name`() {
        val choices = Onboarding.languages()
        assertEquals("", choices.first().tag)
        assertNull(choices.first().name)
        assertEquals(Languages.all.map { it.tag }, choices.drop(1).map { it.tag })
        assertEquals("Español (Latinoamérica)", choices.first { it.tag == "es-419" }.name)
    }

    @Test
    fun `the pseudo locale reaches every page`() {
        val xa = Translator.load(Languages.PSEUDO)
        val en = Translator.english()
        for (page in Onboarding.pages) {
            assertNotEquals(page.name, en.onboarding(page, DeviceForm.Tv).title, xa.onboarding(page, DeviceForm.Tv).title)
        }
    }
}

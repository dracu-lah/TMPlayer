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
    fun `two pages, in order`() {
        assertEquals(listOf(OnboardingPage.Welcome, OnboardingPage.HowItWorks), Onboarding.pages)
        assertEquals(listOf(OnboardingPage.HowItWorks), Onboarding.pages.filter { it.illustrated })
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
        val settings = store()
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
    fun `an install that saw the six page tour is not shown the new one`() = runBlocking {
        // The flag the old tour wrote is the one the new tour reads.
        val settings = store()
        settings.markOverviewSeen()
        assertEquals(Entry.App, Onboarding.entry(signedIn = true, overviewSeen = settings.overviewSeen.first()))
        assertEquals(Entry.SignIn, Onboarding.entry(signedIn = false, overviewSeen = settings.overviewSeen.first()))
    }

    @Test
    fun `on the first run the Welcome page cannot be skipped past and Back does not leave`() {
        val tour = TourState(firstRun = true)
        assertEquals(OnboardingPage.Welcome, tour.page)
        assertFalse(tour.canLeave)
        assertFalse("Back has nothing to do on the first page", tour.backEnabled)
        assertFalse(tour.back())
        assertFalse("no Skip on Welcome, which carries the Telegram terms line", tour.canSkip)
        assertTrue(tour.next())
        assertEquals(OnboardingPage.HowItWorks, tour.page)
        assertTrue(tour.isLast)
        assertFalse("Start, not Skip, on the last page", tour.canSkip)
        assertTrue(tour.backEnabled)
        // Start on the last page finishes the tour: a fresh install that taps Start has passed
        // Welcome, so the terms line has been on screen.
        assertFalse(tour.next())
        assertTrue(tour.back())
        assertEquals(OnboardingPage.Welcome, tour.page)
    }

    @Test
    fun `asked for again, the tour can be skipped and left from the first page`() {
        val tour = TourState(firstRun = false)
        assertTrue(tour.canSkip)
        assertTrue(tour.canLeave)
        assertTrue(tour.backEnabled)
        assertFalse(tour.back())
        assertEquals(OnboardingPage.HowItWorks, TourState(firstRun = false, start = 99).page)
    }

    @Test
    fun `Back closes the language picker before it moves a page`() {
        val tour = TourState(firstRun = true)
        tour.choosingLanguage = true
        assertTrue("Back closes the picker even on the first page of a first run", tour.backEnabled)
        assertTrue(tour.back())
        assertFalse(tour.choosingLanguage)
        assertEquals(OnboardingPage.Welcome, tour.page)
        assertFalse(tour.backEnabled)

        tour.choosingLanguage = true
        assertTrue("Next closes the picker as it moves on", tour.next())
        assertFalse(tour.choosingLanguage)
        assertEquals(OnboardingPage.HowItWorks, tour.page)
    }

    @Test
    fun `each device reads its own wording`() {
        val s = Translator.english()
        for (page in Onboarding.pages) {
            for (copy in DeviceForm.entries.map { s.onboarding(page, it) }) {
                assertTrue("$page title", copy.title.isNotBlank())
                assertTrue("$page body", copy.body.isNotBlank())
                assertEquals("$page has three points", 3, copy.points.size)
                val words = copy.title + copy.body + copy.points.joinToString { it.title + it.body }
                assertFalse("$page has a dash", Regex("[\u2013\u2014]").containsMatchIn(words))
            }
        }
        val signIn = DeviceForm.entries.map { form ->
            s.onboarding(OnboardingPage.HowItWorks, form).points.first { it.kind == OnboardingPoint.Kind.SignIn }.body
        }
        assertEquals("phone, TV and desktop each say how they sign in", 3, signIn.toSet().size)
        assertTrue(signIn[DeviceForm.entries.indexOf(DeviceForm.Tv)].contains("QR"))
        assertTrue(signIn[DeviceForm.entries.indexOf(DeviceForm.Phone)].contains("number"))
    }

    @Test
    fun `the Welcome page carries the unofficial line Telegram asks for`() {
        val s = Translator.english()
        val welcome = s.onboarding(OnboardingPage.Welcome, DeviceForm.Phone)
        assertEquals(
            listOf(OnboardingPoint.Kind.Unofficial, OnboardingPoint.Kind.NoServer, OnboardingPoint.Kind.NoData),
            welcome.points.map { it.kind },
        )
        assertTrue(welcome.points.first().body.contains("unofficial app that uses the Telegram API"))
    }

    @Test
    fun `How it works says how to get a first video in`() {
        val s = Translator.english()
        val how = s.onboarding(OnboardingPage.HowItWorks, DeviceForm.Phone)
        assertTrue(how.points.first { it.kind == OnboardingPoint.Kind.FirstVideo }.body.contains("Saved Messages"))
    }

    @Test
    fun `the language picker lists the system first, then every language by its own name`() {
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

    @Test
    fun `the first sign in card is asked once, and only with folders to choose between`() {
        assertTrue(FirstSignIn.shouldAsk(folderCount = 2, alreadyAsked = false))
        assertFalse("no folders, nothing to choose", FirstSignIn.shouldAsk(folderCount = 0, alreadyAsked = false))
        assertFalse("asked already", FirstSignIn.shouldAsk(folderCount = 2, alreadyAsked = true))
    }

    private fun store(): SettingsStore {
        val file = Files.createTempDirectory("tm-tour").resolve(SettingsStore.FILE_NAME).toFile()
        return SettingsStore(SettingsStore.openDataStore(file))
    }
}

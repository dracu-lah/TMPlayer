package com.tmplayer.ui.onboarding

import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Translator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The quick tour (CP69): which pages, how it is walked, and when it is shown or offered. */
class TourTest {

    @Test
    fun `the TV home screen page is the television's alone`() {
        assertTrue(TourPage.TvHome in Tour.pages(DeviceForm.Tv))
        assertFalse(TourPage.TvHome in Tour.pages(DeviceForm.Phone))
        assertFalse(TourPage.TvHome in Tour.pages(DeviceForm.Desktop))
        assertEquals(TourPage.Videos, Tour.pages(DeviceForm.Phone).first())
        assertEquals(TourPage.Settings, Tour.pages(DeviceForm.Tv).last())
    }

    @Test
    fun `Next walks on and finishes after the last page, Back stops at the first`() {
        val tour = TourProgress(listOf(TourPage.Videos, TourPage.Home))
        assertTrue(tour.isFirst)
        assertFalse(tour.back())
        assertTrue(tour.next())
        assertTrue(tour.isLast)
        assertFalse("done", tour.next())
        assertTrue(tour.back())
        assertEquals(TourPage.Videos, tour.page)
    }

    @Test
    fun `every page on every form has a heading and its lines`() {
        val s = Translator.messages
        for (form in DeviceForm.entries) {
            for (page in Tour.pages(form)) {
                val copy = s.tourCopy(page, form)
                assertTrue(copy.title.isNotBlank())
                assertTrue("$page on $form", copy.lines.size in 2..5)
                assertTrue(copy.lines.all { it.isNotBlank() })
            }
        }
    }

    @Test
    fun `a picture's badges never outnumber its page's lines`() {
        for (form in DeviceForm.entries) {
            for (page in Tour.pages(form)) {
                val badges = TourBadges.of(page, form)
                assertTrue("$page on $form", badges.size <= Translator.messages.tourCopy(page, form).lines.size)
                assertTrue(badges.all { it.x in 0f..1f && it.y in 0f..1f })
            }
        }
    }

    @Test
    fun `an updated install and a fresh sign in are both offered the tour once`() = runBlocking {
        val updated = store()
        assertEquals(SettingsStore.TourState.Never, updated.tourState.first())
        updated.openTour()
        assertEquals(SettingsStore.TourState.Pending, updated.tourState.first())
        updated.markTourDone()
        assertEquals(SettingsStore.TourState.Done, updated.tourState.first())

        val fresh = store()
        fresh.armSetup()
        fresh.markSetupDone()
        // The tour is no longer a counted part of the setup: it is offered on its own after it.
        assertEquals("offered once after the setup", SettingsStore.TourState.Never, fresh.tourState.first())
    }

    private fun store(): SettingsStore {
        val file = Files.createTempDirectory("tm-tour").resolve(SettingsStore.FILE_NAME).toFile()
        return SettingsStore(SettingsStore.openDataStore(file))
    }
}

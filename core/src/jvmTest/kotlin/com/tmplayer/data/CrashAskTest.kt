package com.tmplayer.data

import com.tmplayer.data.CrashAsk.Decision
import com.tmplayer.data.CrashAsk.Inputs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** S1: the one-time crash report question, its decision, its focus order and what it stores. */
class CrashAskTest {

    private val fresh = Inputs(available = true, asked = false, inShell = true)

    private fun store() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-crash-ask").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    @Test
    fun `an install never asked is asked once, and marked as it shows`() {
        assertEquals(Decision.ShowAndMarkAsked, CrashAsk.decide(fresh))
        // Marked: the same run keeps it up until it is answered.
        assertEquals(Decision.Show, CrashAsk.decide(fresh.copy(asked = true, shownThisRun = true)))
        assertEquals(Decision.Hide, CrashAsk.decide(fresh.copy(asked = true, shownThisRun = true, answeredThisRun = true)))
    }

    @Test
    fun `never asked twice, whatever the answer or none`() {
        // The next run: asked is stored, whether the viewer said Yes, No or left the card up.
        val nextRun = fresh.copy(asked = true)
        assertEquals(Decision.Hide, CrashAsk.decide(nextRun))
        assertEquals(Decision.Hide, CrashAsk.decide(nextRun.copy(otherUp = false, safeMode = false, kidsLock = false)))
    }

    @Test
    fun `a whole lifetime of runs shows it exactly once`() {
        var asked = false
        var shows = 0
        repeat(5) {
            var shown = false
            // A run with three compositions: hidden by another card, then free, then free again.
            for (otherUp in listOf(true, false, false)) {
                when (CrashAsk.decide(fresh.copy(asked = asked, otherUp = otherUp, shownThisRun = shown))) {
                    Decision.ShowAndMarkAsked -> {
                        shows++
                        shown = true
                        asked = true
                    }
                    Decision.Show, Decision.Hide -> Unit
                }
            }
        }
        assertEquals(1, shows)
    }

    @Test
    fun `a build with no DSN never shows it`() {
        assertEquals(Decision.Hide, CrashAsk.decide(fresh.copy(available = false)))
        assertEquals(Decision.Hide, CrashAsk.decide(fresh.copy(available = false, shownThisRun = true, asked = true)))
    }

    @Test
    fun `a kids lock, a safe start, another card or no shell keep it back without using it up`() {
        for (held in listOf(
            fresh.copy(kidsLock = true),
            fresh.copy(safeMode = true),
            fresh.copy(otherUp = true),
            fresh.copy(inShell = false),
        )) {
            assertEquals(held.toString(), Decision.Hide, CrashAsk.decide(held))
        }
        // Once the card is away the question comes, still unasked.
        assertEquals(Decision.ShowAndMarkAsked, CrashAsk.decide(fresh.copy(otherUp = false)))
        // Shown, then another card comes up: it steps back for it and returns after.
        assertEquals(Decision.Hide, CrashAsk.decide(fresh.copy(asked = true, shownThisRun = true, otherUp = true)))
        assertEquals(Decision.Show, CrashAsk.decide(fresh.copy(asked = true, shownThisRun = true)))
        // A kids lock turned on while it was up takes it away.
        assertEquals(Decision.Hide, CrashAsk.decide(fresh.copy(asked = true, shownThisRun = true, kidsLock = true)))
    }

    @Test
    fun `a remote lands on Yes, then No, then the privacy page, and Back is No`() {
        assertEquals(listOf(CrashAsk.Control.Yes, CrashAsk.Control.No, CrashAsk.Control.Privacy), CrashAsk.ORDER)
        assertEquals(CrashAsk.Control.Yes, CrashAsk.firstFocus(DeviceForm.Tv))
        // Not blocking: a phone and a desktop keep the focus where it was.
        assertNull(CrashAsk.firstFocus(DeviceForm.Phone))
        assertNull(CrashAsk.firstFocus(DeviceForm.Desktop))
        assertEquals(CrashAsk.Control.No, CrashAsk.BACK)
    }

    @Test
    fun `consent stays the basis`() {
        assertFalse("opt-out default-on is documented, not enabled", CrashAsk.OPT_OUT_DEFAULT_ON)
        runBlocking { assertFalse(store().crashReportsNow()) }
    }

    @Test
    fun `any stored answer counts as asked, and marking writes no answer`() = runBlocking {
        val settings = store()
        assertFalse(settings.crashReportsAsked.first())

        settings.markCrashReportsAsked()
        assertTrue(settings.crashReportsAsked.first())
        assertFalse("asked is not consent", settings.crashReportsNow())

        for (answer in listOf(false, true)) {
            val other = store()
            other.setCrashReports(answer)
            assertTrue("answered $answer", other.crashReportsAsked.first())
            assertEquals(answer, other.crashReportsNow())
        }
    }
}

package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** S1: crash reports are consent, off until turned on; the stored answer and the asked mark stay readable. */
class CrashAskTest {

    private fun store() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-crash-ask").resolve(SettingsStore.FILE_NAME).toFile()),
    )

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

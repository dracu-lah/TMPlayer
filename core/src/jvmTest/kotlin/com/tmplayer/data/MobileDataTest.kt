package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** "On mobile data: Ask first, Never, Allow" (R2): the migration from the two old switches, and what each choice does. */
class MobileDataTest {

    private fun store() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-mobile").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    /** The migration table of design/redesign/downloads-tab.md, one row per stored pair. */
    @Test
    fun `the two old switches map to one answer, the safe default for the rest`() {
        val table = listOf(
            // wifi only, save mobile data -> shown
            Triple(true, true, MobileData.Never),
            Triple(true, false, MobileData.Never),
            Triple(false, true, MobileData.Ask),
            Triple(false, false, MobileData.Ask),
        )
        for ((wifiOnly, save, shown) in table) {
            assertEquals("wifi=$wifiOnly save=$save", shown, MobileData.fromLegacy(wifiOnly, save))
            // With no stored word the booleans decide.
            assertEquals(shown, MobileData.resolve(null, wifiOnly, save))
        }
    }

    @Test
    fun `a stored word wins over the old switches, and garbage falls back to them`() {
        assertEquals(MobileData.Allow, MobileData.resolve("allow", wifiOnly = true, saveMobileData = true))
        assertEquals(MobileData.Never, MobileData.resolve("never", wifiOnly = false, saveMobileData = false))
        assertEquals(MobileData.Ask, MobileData.resolve("ask", wifiOnly = true, saveMobileData = false))
        for (junk in listOf("", "ALLOW", "yes", "Never ", "\u0000", "a".repeat(10_000))) {
            assertNull(junk, MobileData.parse(junk))
            assertEquals(MobileData.Never, MobileData.resolve(junk, wifiOnly = true, saveMobileData = false))
            assertEquals(MobileData.Ask, MobileData.resolve(junk, wifiOnly = false, saveMobileData = false))
        }
    }

    @Test
    fun `a fresh store asks first, and the old switch is read as before`() = runBlocking {
        val store = store()
        assertEquals(MobileData.Ask, store.mobileData.first())
        assertEquals(false, store.wifiOnlyDownloads.first())
        // A phone that had only Wi-Fi only on (the old key) shows Never without being rewritten.
        store.setWifiOnlyDownloads(true)
        assertEquals(MobileData.Never, store.mobileData.first())
        assertEquals(MobileData.Never, store.mobileDataNow())
        assertTrue(store.wifiOnlyDownloadsNow())
        // Save mobile data on, Wi-Fi only off: Ask first, and the streaming bound is kept.
        store.setWifiOnlyDownloads(false)
        store.setSaveMobileData(true)
        assertEquals(MobileData.Ask, store.mobileData.first())
        assertTrue(store.saveMobileDataNow())
    }

    @Test
    fun `picking keeps the old Wi-Fi only switch in step, so an older build holds back the same`() = runBlocking {
        val store = store()
        store.setMobileData(MobileData.Never)
        assertEquals(true, store.wifiOnlyDownloads.first())
        store.setMobileData(MobileData.Allow)
        assertEquals(MobileData.Allow, store.mobileData.first())
        assertEquals(false, store.wifiOnlyDownloads.first())
        store.setMobileData(MobileData.Ask)
        assertEquals(MobileData.Ask, store.mobileDataNow())
        assertEquals(false, store.wifiOnlyDownloadsNow())
    }

    @Test
    fun `any pick ends a stored Save mobile data, so Allow no longer bounds a stream`() = runBlocking {
        for (pick in MobileData.entries) {
            val store = store()
            store.setSaveMobileData(true)
            assertTrue(store.saveMobileDataNow())
            store.setMobileData(pick)
            assertEquals(pick.name, false, store.saveMobileDataNow())
            assertEquals(false, store.saveMobileData.first())
            assertEquals(pick, store.mobileDataNow())
            assertEquals(pick == MobileData.Never, store.wifiOnlyDownloadsNow())
        }
    }

    @Test
    fun `the setup's No limits turns Never off but leaves Allow alone`() = runBlocking {
        val store = store()
        store.setMobileData(MobileData.Never)
        store.setWifiOnlyDownloads(false)
        assertEquals(MobileData.Ask, store.mobileData.first())
        store.setMobileData(MobileData.Allow)
        store.setWifiOnlyDownloads(false)
        assertEquals(MobileData.Allow, store.mobileData.first())
    }

    @Test
    fun `the always on switches stay on, whatever an old backup says`() = runBlocking {
        val store = store()
        assertTrue(store.pauseDownloadsWhilePlaying.first())
        assertTrue(store.noticeOnNow(Notice.LowStorage))
        store.setNoticeOn(Notice.LowStorage, false)
        assertTrue(store.noticeOnNow(Notice.LowStorage))
        assertTrue(store.noticeOn(Notice.LowStorage).first())
    }

    @Test
    fun `Allow never asks, Ask asks once for a large video, Never blocks`() {
        val large = MeteredPolicy.LARGE_DOWNLOAD_BYTES * 2
        fun decide(choice: MobileData, warned: Boolean = false) = MeteredPolicy.decide(
            metered = true,
            wifiOnly = choice.blocks,
            alreadyDownloaded = false,
            warnedThisSession = warned,
            sizeBytes = large,
            asks = choice.asks,
        )
        assertEquals(MeteredDecision.Warn, decide(MobileData.Ask))
        assertEquals(MeteredDecision.Allow, decide(MobileData.Ask, warned = true))
        assertEquals(MeteredDecision.Block, decide(MobileData.Never))
        assertEquals(MeteredDecision.Allow, decide(MobileData.Allow))
        // Wi-Fi, or a file already on the phone, asks nothing of any choice.
        for (choice in MobileData.entries) {
            assertEquals(
                MeteredDecision.Allow,
                MeteredPolicy.decide(false, choice.blocks, false, false, large, choice.asks),
            )
            assertEquals(
                MeteredDecision.Allow,
                MeteredPolicy.decide(true, choice.blocks, true, false, large, choice.asks),
            )
        }
    }
}

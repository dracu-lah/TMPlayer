package com.tmplayer.desktop.ui

import androidx.compose.ui.unit.dp
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings and Downloads as a panel: the size it takes and the page it keeps under it. */
class SettingsModalTest {

    @Test
    fun `a small window gets the full window`() {
        assertTrue(modalSize(1000.dp, 900.dp).full)
        assertTrue(modalSize(1600.dp, 650.dp).full)
        assertEquals(1000.dp, modalSize(1000.dp, 900.dp).width)
    }

    @Test
    fun `a wide window gets a capped centred panel`() {
        val wide = modalSize(2560.dp, 1440.dp)
        assertFalse(wide.full)
        assertEquals(1040.dp, wide.width)
        assertEquals(820.dp, wide.height)
        val edge = modalSize(1100.dp, 700.dp)
        assertFalse(edge.full)
        assertEquals(1020.dp, edge.width)
        assertEquals(620.dp, edge.height)
    }

    private fun shell(): ShellState {
        val settings = SettingsStore(
            SettingsStore.openDataStore(Files.createTempDirectory("tm-modal").resolve(SettingsStore.FILE_NAME).toFile()),
        )
        val dir = Files.createTempDirectory("tm-modal-extras").toFile()
        return ShellState(
            settings,
            object : DownloadRunner {
                override fun download(request: DownloadRequest) = Unit
                override fun cancel(fileId: Int) = Unit
                override fun pause(fileId: Int) = Unit
            },
            WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-modal-w").resolve(WatchedStore.FILE_NAME).toFile())),
            services = {
                DesktopExtras(
                    prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                    updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                    watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
                )
            },
        )
    }

    @Test
    fun `the panel opens over the page it came from and closing returns there`() {
        val shell = shell()
        shell.go(Destination.Chats)
        shell.go(Destination.Settings)
        assertTrue(shell.modalOpen)
        assertEquals(Destination.Chats, shell.pageDestination)
        // Settings to Downloads (Free up space) keeps the page under both.
        shell.go(Destination.Downloads)
        assertEquals(Destination.Chats, shell.pageDestination)
        shell.backFromPage()
        assertEquals(Destination.Chats, shell.destination)
        assertFalse(shell.modalOpen)
        assertEquals(Destination.Chats, shell.pageDestination)
    }

    @Test
    fun `a panel opened first thing sits over Home`() {
        val shell = shell()
        shell.go(Destination.Downloads)
        assertEquals(Destination.Home, shell.pageDestination)
        shell.closeModal()
        assertEquals(Destination.Home, shell.destination)
    }
}

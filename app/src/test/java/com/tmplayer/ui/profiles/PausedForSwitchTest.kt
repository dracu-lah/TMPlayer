package com.tmplayer.ui.profiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.AccountStamp
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Profile
import com.tmplayer.data.SwitchPause
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The downloads a switch paused (HX88), in memory only: a Cancel of that switch puts them back
 * while the same profile and rows are open; nothing resumes by itself after a switch, a sign out
 * or a restart, and what earlier builds kept on the device to resume is cleared, never read. The
 * rules themselves are tested in core (SwitchPause, DownloadQueueModelTest).
 */
@RunWith(RobolectricTestRunner::class)
class PausedForSwitchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val four = SwitchPause.Paused(4, -100L, 40L)

    @Test
    fun `the copy earlier builds kept is cleared and never resumed`() {
        val old = context.getSharedPreferences("profile_switch_paused", Context.MODE_PRIVATE)
        old.edit().putString("paused_", "4,-100,40").commit()
        PausedForSwitch.clearOldCopy(context)
        assertTrue(old.all.isEmpty())
    }

    @Test
    fun `wiping another profile keeps the open one's paused downloads`() = runBlocking {
        val platform = AndroidProfilePlatform(context)
        platform.held = SwitchPause.Held(AccountStamp(1L, null), OfflineDownloads.rowsGeneration, listOf(four))
        platform.wipe(Profile("pbbbbb", "pbbbbb"), last = false, deleteDownloads = false)
        assertNotNull("not the profile being wiped", platform.held)
        platform.wipe(Profile("main", null), last = true, deleteDownloads = false)
        assertNull("a sign out discards what its wait paused", platform.held)
    }

    @Test
    fun `the cache folder of each profile comes from its own folder name`() {
        val base = object : com.tmplayer.platform.Paths {
            override val databaseDir = java.io.File("/data/app/tdlib")
            override val filesDir = java.io.File("/data/app/tdlib-files")
            override val downloadsDir = java.io.File("/data/app/downloads")
            override fun disk() = com.tmplayer.data.DiskInfo.EMPTY
        }
        assertEquals(java.io.File("/data/app/tdlib-files"), com.tmplayer.data.WatchCache.rootOf(base, AccountStamp(1L, null)))
        assertEquals(java.io.File("/data/app/tdlib-files-pbbbbb"), com.tmplayer.data.WatchCache.rootOf(base, AccountStamp(2L, "pbbbbb")))
    }
}

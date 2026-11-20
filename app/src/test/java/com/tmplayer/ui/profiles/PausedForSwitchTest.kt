package com.tmplayer.ui.profiles

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.Profile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The downloads a switch paused, kept on the device per profile (HX88): they are there for that
 * profile when it is open next (a process that died mid wait included), never for another one,
 * taken once, never resumed as another video under the same id, and gone with a sign out.
 */
@RunWith(RobolectricTestRunner::class)
class PausedForSwitchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val four = PausedForSwitch.Paused(4, -100L, 40L)
    private val five = PausedForSwitch.Paused(5, -100L, 50L)

    @Test
    fun `the paused downloads come back for their own profile, once, never for another`() {
        PausedForSwitch.record(context, null, listOf(four, five))
        assertTrue("not another profile's", PausedForSwitch.take(context, "pbbbbb").isEmpty())
        assertEquals("the adopted profile's, after a restart too", listOf(four, five), PausedForSwitch.take(context, null))
        assertTrue("taken once", PausedForSwitch.take(context, null).isEmpty())
    }

    @Test
    fun `an id that is another video now (a new database) is never resumed`() {
        val rows = mapOf(4 to (-100L to 40L), 5 to (-200L to 9L))
        assertEquals(listOf(4), PausedForSwitch.stillTheSame(listOf(four, five), rows))
    }

    @Test
    fun `signing the last profile out and in again in the same process resumes nothing`() = runBlocking {
        PausedForSwitch.record(context, null, listOf(four, five))
        // The sign out of the one profile, adopted in place: same folder before and after.
        AndroidProfilePlatform(context).wipe(Profile("main", null), last = true, deleteDownloads = false)
        assertTrue("a sign out discards what its wait paused", PausedForSwitch.take(context, null).isEmpty())
    }

    @Test
    fun `a retry's wait that finds nothing moving never loses what the first wait paused`() {
        PausedForSwitch.record(context, "pa", listOf(four))
        PausedForSwitch.record(context, "pa", emptyList())
        PausedForSwitch.record(context, "pa", listOf(five))
        assertEquals(listOf(four, five), PausedForSwitch.take(context, "pa"))
    }

    @Test
    fun `wiping another profile keeps the open one's paused downloads`() = runBlocking {
        val platform = AndroidProfilePlatform(context)
        platform.pausedForSwitch = listOf(four)
        platform.pausedFor = com.tmplayer.data.AccountStamp(1L, null)
        platform.wipe(Profile("pbbbbb", "pbbbbb"), last = false, deleteDownloads = false)
        assertEquals("not the profile being wiped", listOf(four), platform.pausedForSwitch)
        platform.wipe(Profile("main", null), last = true, deleteDownloads = false)
        assertTrue(platform.pausedForSwitch.isEmpty())
    }

    @Test
    fun `the cache folder of each profile comes from its own folder name`() {
        val base = object : com.tmplayer.platform.Paths {
            override val databaseDir = java.io.File("/data/app/tdlib")
            override val filesDir = java.io.File("/data/app/tdlib-files")
            override val downloadsDir = java.io.File("/data/app/downloads")
            override fun disk() = com.tmplayer.data.DiskInfo.EMPTY
        }
        assertEquals(java.io.File("/data/app/tdlib-files"), com.tmplayer.data.WatchCache.rootOf(base, com.tmplayer.data.AccountStamp(1L, null)))
        assertEquals(java.io.File("/data/app/tdlib-files-pbbbbb"), com.tmplayer.data.WatchCache.rootOf(base, com.tmplayer.data.AccountStamp(2L, "pbbbbb")))
    }

    @Test
    fun `a set paused in another TDLib database is never resumed (Sign in again, an ended session)`() {
        PausedForSwitch.record(context, "pa", listOf(four), database = "olddatabase1")
        assertTrue(PausedForSwitch.take(context, "pa", database = "newdatabase2").isEmpty())
        PausedForSwitch.record(context, "pa", listOf(four), database = "olddatabase1")
        PausedForSwitch.record(context, "pa", listOf(five), database = "newdatabase2")
        assertEquals("only the new database's", listOf(five), PausedForSwitch.take(context, "pa", database = "newdatabase2"))
    }
}

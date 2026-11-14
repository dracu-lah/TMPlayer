package com.tmplayer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Safe mode for a start-up loop: two runs in a row that crashed soon after opening make the next
 * run a safe one, a run that stays up or a viewer who asks ends it, and a counted crash is passed
 * on to the handler that was there before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeModeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val early get() = File(context.filesDir, "crash/early.txt")
    private var original: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        early.delete()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        early.delete()
    }

    private fun crashSoonAfterOpening() {
        SafeMode.watch(context)
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
    }

    @Test
    fun anOrdinaryStartIsNotSafe() {
        SafeMode.watch(context)
        assertFalse(SafeMode.active)
    }

    @Test
    fun oneEarlyCrashIsNotALoop() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        crashSoonAfterOpening()
        SafeMode.watch(context)
        assertFalse(SafeMode.active)
    }

    @Test
    fun twoEarlyCrashesInARowMakeTheNextRunSafe() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        crashSoonAfterOpening()
        crashSoonAfterOpening()
        SafeMode.watch(context)
        assertTrue(SafeMode.active)
    }

    @Test
    fun aRunThatStaysUpEndsTheLoop() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        crashSoonAfterOpening()
        crashSoonAfterOpening()
        SafeMode.watch(context)
        SafeMode.stayedUp()
        SafeMode.watch(context)
        assertFalse(SafeMode.active)
    }

    @Test
    fun openingNormallyNextTimeEndsItAtOnceButNotThisRun() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        crashSoonAfterOpening()
        crashSoonAfterOpening()
        SafeMode.watch(context)
        assertTrue(SafeMode.active)
        SafeMode.openNormallyNextTime()
        assertTrue("this run stays safe", SafeMode.active)
        SafeMode.watch(context)
        assertFalse("the next one is ordinary", SafeMode.active)
    }

    @Test
    fun aCountedCrashStillReachesTheHandlerThatWasThere() {
        val seen = mutableListOf<String?>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> seen += error.message }
        crashSoonAfterOpening()
        assertEquals(listOf<String?>("boom"), seen)
    }

    @Test
    fun aBrokenCountFileIsNoLoop() {
        early.parentFile?.mkdirs()
        early.writeText("not\nnumbers\n\u0000\u0000")
        SafeMode.watch(context)
        assertFalse(SafeMode.active)
    }
}

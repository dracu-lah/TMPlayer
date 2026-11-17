@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.tmplayer.ios

import com.tmplayer.data.appleDevice
import com.tmplayer.platform.KeptLog
import com.tmplayer.platform.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * The app's own log on iOS (KeptLog): what the app logs is there for "Save logs", in time order
 * with TDLib's file, and the device line says what iOS calls the device, not Android.
 */
class KeptLogTest {
    @Test
    fun aLoggedLineIsKeptWithItsMomentAndTag() {
        val mark = "kept-${kotlin.random.Random.nextLong()}"
        Logger.i("KeptLogTest", "line $mark")
        Logger.w("KeptLogTest", "warning $mark", IllegalStateException("boom $mark"))
        val lines = KeptLog.lines(500)
        val line = lines.lastOrNull { "line $mark" in it }
        assertNotNull(line, "the info line is kept")
        assertTrue(Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} \d+ t\d+ I/KeptLogTest: line """).containsMatchIn(line), line)
        assertTrue(lines.any { "W/KeptLogTest: warning $mark" in it }, "the warning is kept")
        assertTrue(lines.any { "boom $mark" in it }, "its error's message is kept with it")
    }

    @Test
    fun aRunStartsWithWhatItIs() {
        Logger.i("KeptLogTest", "anything")
        assertTrue(KeptLog.lines(2000).any { "I/KeptLog: TMPlayer " in it && "started on " in it })
    }

    @Test
    fun tdlibsLinesMergeInByTime() {
        val path = assertNotNull(KeptLog.tdlibLogPath)
        val mark = "tdlib-${kotlin.random.Random.nextLong()}"
        // A TDLib line from 2001, so it sorts first, and one from now, after the app's line below.
        // In TDLib's own form: whole seconds, a point, the fraction (not Kotlin's 1.7E9).
        val now = "${platform.Foundation.NSDate().timeIntervalSince1970.toLong() + 5}.500000000"
        val text = "[ 2][t 9][1000000000.000][Old.cpp:1] old $mark\n[ 1][t 9][$now][New.cpp:2] new $mark\n"
        assertTrue(NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null))
        Logger.i("KeptLogTest", "between $mark")
        val lines = KeptLog.lines(2000)
        val old = lines.indexOfFirst { "W/TDLib: [Old.cpp:1] old $mark" in it }
        val between = lines.indexOfFirst { "between $mark" in it }
        val new = lines.indexOfFirst { "E/TDLib: [New.cpp:2] new $mark" in it }
        assertTrue(old in 0 until between, "the old TDLib line before the app's: $old, $between")
        assertTrue(between < new, "the new TDLib line after it: $between, $new")
    }

    @Test
    fun theLinesAskedForAreTheLast() {
        repeat(30) { Logger.i("KeptLogTest", "many $it") }
        val lines = KeptLog.lines(10)
        assertEquals(10, lines.size)
        assertTrue(lines.last().endsWith("many 29") || lines.any { it.endsWith("many 29") })
    }

    @Test
    fun theDeviceIsNamedAsIosNamesIt() {
        val device = appleDevice()
        assertFalse("Android" in device, device)
        val system = platform.UIKit.UIDevice.currentDevice.systemName
        assertTrue(device.contains(", $system "), device)
    }
}

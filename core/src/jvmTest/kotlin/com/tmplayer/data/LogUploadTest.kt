package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Send to developer" (L5): which builds offer it, and how the inbox's answer is read. */
class LogUploadTest {

    @Test
    fun `a build with no address, or an insecure or odd one, offers nothing`() {
        assertFalse(LogUpload.available(null))
        assertFalse(LogUpload.available(""))
        assertFalse(LogUpload.available("   "))
        assertFalse(LogUpload.available("http://inbox.example/v1/report"))
        assertFalse(LogUpload.available("https://"))
        assertFalse(LogUpload.available("https://inbox example/v1"))
        assertTrue(LogUpload.available("https://tmplayer-log-inbox.example.workers.dev/v1/report"))
        assertTrue(LogUpload.available("  https://inbox.example/v1/report \n"))
    }

    @Test
    fun `a stored report gives its code`() {
        assertEquals(LogUpload.Outcome.Sent("TMP-7K3Q"), LogUpload.outcome(201, """{"code":"TMP-7K3Q","keepDays":30}"""))
        assertEquals(LogUpload.Outcome.Sent("TMP-7K3Q"), LogUpload.outcome(201, """{ "keepDays": 30, "code" : "TMP-7K3Q" }"""))
    }

    @Test
    fun `a success without a well formed code is a failure, never a made up code`() {
        for (body in listOf(null, "", "{}", """{"code":"TMP-0O1I"}""", """{"code":"tmp-7k3q"}""", """{"code":"TMP-7K3QX"}""", "TMP-7K3Q", "<html>captive portal</html>")) {
            assertEquals("$body", LogUpload.Outcome.Failed(201), LogUpload.outcome(201, body))
        }
        assertNull(LogUpload.parseCode("""{"code":"TMP-7K3"}"""))
    }

    @Test
    fun `an error status is never read as sent, whatever the body says`() {
        assertEquals(LogUpload.Outcome.Failed(500), LogUpload.outcome(500, """{"code":"TMP-7K3Q"}"""))
        assertEquals(LogUpload.Outcome.Failed(413), LogUpload.outcome(413, null))
        assertEquals(LogUpload.Outcome.Failed(0), LogUpload.outcome(0, ""))
    }

    @Test
    fun `a limit hit says how long to wait, with a sane number`() {
        assertEquals(LogUpload.Outcome.SlowDown(60), LogUpload.outcome(429, null))
        assertEquals(LogUpload.Outcome.SlowDown(60), LogUpload.outcome(429, "", "soon"))
        assertEquals(LogUpload.Outcome.SlowDown(30), LogUpload.outcome(429, "", " 30 "))
        assertEquals(LogUpload.Outcome.SlowDown(3_600), LogUpload.outcome(429, "", "99999999"))
        assertEquals(LogUpload.Outcome.SlowDown(1), LogUpload.outcome(429, "", "-5"))
    }

    @Test
    fun `a code past a huge body is not looked for`() {
        assertNull(LogUpload.parseCode(" ".repeat(10_000) + """{"code":"TMP-7K3Q"}"""))
    }

    @Test
    fun `the bundle's cap is under the inbox's cap`() {
        assertTrue(LogBundle.MAX_BYTES < LogUpload.MAX_BYTES)
        assertTrue(LogUpload.fits("a".repeat(LogUpload.MAX_BYTES)))
        assertFalse(LogUpload.fits("a".repeat(LogUpload.MAX_BYTES + 1)))
    }
}

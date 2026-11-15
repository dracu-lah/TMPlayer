package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleUploadTest {

    @Test
    fun `a stored pair gives its code, and says when the inbox had it already`() {
        assertEquals(TitleUpload.Outcome.Sent("NAME-2PGE"), TitleUpload.outcome(201, """{"code":"NAME-2PGE","duplicate":false,"keepDays":30}"""))
        assertEquals(TitleUpload.Outcome.Sent("NAME-2PGE", duplicate = true), TitleUpload.outcome(200, """{"code":"NAME-2PGE","duplicate":true}"""))
    }

    @Test
    fun `an answer that is not a well formed code is a failure, not a send`() {
        assertEquals(TitleUpload.Outcome.Failed(201), TitleUpload.outcome(201, """{"code":"TMP-7K3Q"}"""))
        assertEquals(TitleUpload.Outcome.Failed(201), TitleUpload.outcome(201, """{"code":"NAME-0OIL"}"""))
        assertEquals(TitleUpload.Outcome.Failed(200), TitleUpload.outcome(200, "<html>Sign in to the Wi-Fi</html>"))
        assertEquals(TitleUpload.Outcome.Failed(201), TitleUpload.outcome(201, null))
    }

    @Test
    fun `the limit says how long to wait, kept within an hour`() {
        assertEquals(TitleUpload.Outcome.SlowDown(45), TitleUpload.outcome(429, "", "45"))
        assertEquals(TitleUpload.Outcome.SlowDown(60), TitleUpload.outcome(429, "", null))
        assertEquals(TitleUpload.Outcome.SlowDown(3_600), TitleUpload.outcome(429, "", "999999"))
        assertEquals(TitleUpload.Outcome.SlowDown(60), TitleUpload.outcome(429, "", "soon"))
    }

    @Test
    fun `only waiting answers are tried again`() {
        assertTrue(TitleUpload.worthRetrying(TitleUpload.Outcome.NoConnection))
        assertTrue(TitleUpload.worthRetrying(TitleUpload.Outcome.SlowDown(5)))
        assertTrue(TitleUpload.worthRetrying(TitleUpload.Outcome.Failed(503)))
        assertFalse(TitleUpload.worthRetrying(TitleUpload.Outcome.Failed(400)))
        assertFalse(TitleUpload.worthRetrying(TitleUpload.Outcome.Failed(413)))
        assertFalse(TitleUpload.worthRetrying(TitleUpload.Outcome.Failed(0)))
        assertFalse(TitleUpload.worthRetrying(TitleUpload.Outcome.Sent("NAME-2222")))
    }

    @Test
    fun `the titles address comes from the build's one address and is hidden without it`() {
        assertEquals("https://inbox.example/v1/titles", LogUpload.titlesUrl("https://inbox.example/v1/report"))
        assertEquals("https://inbox.example/v1/titles", LogUpload.titlesUrl(" https://inbox.example/v1/report/ "))
        assertEquals("https://inbox.example/v1/titles", LogUpload.titlesUrl("https://inbox.example"))
        assertEquals("https://inbox.example/v1/titles", LogUpload.titlesUrl("https://inbox.example/v1/report?x=1#y"))
        assertNull(LogUpload.titlesUrl(""))
        assertNull(LogUpload.titlesUrl(null))
        assertNull(LogUpload.titlesUrl("http://inbox.example/v1/report"))
        assertNull(LogUpload.titlesUrl("https://"))
    }

    @Test
    fun `the code is the same for the same pair and agrees with the inbox`() {
        val a = assertNotNull(TitleReport.of("Night.Train.2024.mkv", "Night Train"))
        // These two values are asserted in workers/log-inbox/test/inbox.test.ts as well: change both or neither.
        assertEquals("3742ef92863ebf19", a.hash)
        assertEquals("NAME-2PGE", a.code)
        val unicode = assertNotNull(TitleReport.of("Ñandú Ünï 夜行列車 😀.mkv", "Night Train"))
        assertEquals("99d103d629d04c7e", unicode.hash)
        assertEquals("NAME-Z3TT", unicode.code)
        assertEquals(a.code, assertNotNull(TitleReport.of(" NIGHT.TRAIN.2024.MKV ", "night   train", "A chat", true)).code)
        assertTrue(Regex("NAME-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}").matches(TitleReport.codeOf("ffffffffffffffff")))
        assertEquals("NAME-2222", TitleReport.codeOf("not hex"))
    }
}

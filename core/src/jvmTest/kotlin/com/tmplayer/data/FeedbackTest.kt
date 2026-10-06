package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLDecoder

class FeedbackTest {

    private val phone = Feedback.Facts("1.23.0", Feedback.Platform.Phone, "Xiaomi POCO X5, Android 14", "es-419")

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }

    @Test
    fun `the issue url fills the bug template's fields`() {
        val url = Feedback.issueUrl(phone)
        assertTrue(url.startsWith("https://github.com/dracu-lah/TMPlayer/issues/new?"))
        val q = query(url)
        assertEquals("bug_report.yml", q["template"])
        assertEquals("Android phone or tablet", q["platform"])
        assertEquals("1.23.0", q["version"])
        assertEquals("Xiaomi POCO X5, Android 14, app language es-419", q["device"])
        assertEquals(setOf("template", "platform", "version", "device"), q.keys)
    }

    @Test
    fun `spaces are percent encoded, not plus signs`() {
        val url = Feedback.issueUrl(phone)
        assertFalse(url.contains('+'))
        assertFalse(url.contains(' '))
        assertTrue(url.contains("Android%20phone%20or%20tablet"))
    }

    @Test
    fun `english is not repeated in the device line`() {
        assertEquals("Windows 11", Feedback.deviceLine(phone.copy(device = "Windows 11", language = "en")))
    }

    @Test
    fun `a platform the template lacks leaves the dropdown alone`() {
        val q = query(Feedback.issueUrl(phone.copy(platform = Feedback.Platform.Other, device = "macOS 15")))
        assertFalse("platform" in q)
        assertEquals("macOS 15, app language es-419", q["device"])
    }

    @Test
    fun `every platform option is one the template offers`() {
        val template = File("../.github/ISSUE_TEMPLATE/bug_report.yml").readText()
        for (platform in Feedback.Platform.entries) {
            if (platform.option.isEmpty()) continue
            assertTrue("${platform.option} is not in bug_report.yml", template.contains("- ${platform.option}\n"))
        }
        for (id in listOf("platform", "version", "device")) assertTrue(template.contains("id: $id\n"))
    }

    @Test
    fun `the mailto fallback carries the same facts`() {
        val mail = Feedback.mailto(phone)
        assertTrue(mail.startsWith("mailto:hello@tmplayer.org?subject="))
        assertFalse(mail.contains('+'))
        val q = query(mail.replaceFirst("mailto:", "mailto://x/"))
        assertEquals("TMPlayer 1.23.0: a problem", q["subject"])
        val body = q.getValue("body")
        assertTrue(body.contains("Platform: Android phone or tablet"))
        assertTrue(body.contains("App version: 1.23.0"))
        assertTrue(body.contains("Device: Xiaomi POCO X5, Android 14"))
        assertTrue(body.contains("App language: es-419"))
        assertTrue(body.contains("What happened:"))
    }

    @Test
    fun `non ascii device names survive the trip`() {
        val q = query(Feedback.issueUrl(phone.copy(device = "Téléviseur Ü")))
        assertEquals("Téléviseur Ü, app language es-419", q["device"])
    }

    @Test
    fun `the url stays short enough for a qr code across a room`() {
        assertTrue(Feedback.issueUrl(phone).length < 300)
    }
}

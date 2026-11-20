package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import dev.g000sha256.tdl.dto.Session
import dev.g000sha256.tdl.dto.SessionDeviceTypeAndroid
import dev.g000sha256.tdl.dto.SessionDeviceTypeChrome
import dev.g000sha256.tdl.dto.SessionDeviceTypeIpad
import dev.g000sha256.tdl.dto.SessionDeviceTypeUnknown
import dev.g000sha256.tdl.dto.SessionDeviceTypeWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSessionsTest {

    private fun session(id: Long, current: Boolean, lastActive: Int, model: String = "Pixel 8") = AccountSession(
        id = id, current = current, kind = AccountSession.Kind.Phone, device = model, platform = "Android",
        systemVersion = "15", appName = "TMPlayer", appVersion = "1.27.0", official = false,
        loginDate = 100, lastActiveDate = lastActive, ip = "203.0.113.5", location = "Berlin, Germany",
    )

    @Test
    fun `this device comes first, then the others newest first`() {
        val list = AccountSessions.arrange(listOf(session(1, false, 50), session(2, true, 10), session(3, false, 90)))
        assertEquals(listOf(2L, 3L, 1L), list.map { it.id })
    }

    @Test
    fun `a tdlib session is read field by field`() {
        val tdlib = Session(
            7, true, false, true, false, false, SessionDeviceTypeAndroid(), 1, "TMPlayer", "1.27.0", false,
            "Pixel 8", "Android", "15", 100, 200, "203.0.113.5", "Berlin, Germany",
        )
        val read = AccountSessions.of(tdlib)
        assertEquals(7L, read.id)
        assertTrue(read.current)
        assertTrue(read.unconfirmed)
        assertFalse(read.passwordPending)
        assertEquals(AccountSession.Kind.Phone, read.kind)
        assertEquals("Pixel 8", read.device)
        assertEquals(200, read.lastActiveDate)
        assertEquals("Berlin, Germany", read.location)
    }

    @Test
    fun `devices are told apart for their icons`() {
        assertEquals(AccountSession.Kind.Tablet, AccountSessions.kindOf(SessionDeviceTypeIpad()))
        assertEquals(AccountSession.Kind.Computer, AccountSessions.kindOf(SessionDeviceTypeWindows()))
        assertEquals(AccountSession.Kind.Browser, AccountSessions.kindOf(SessionDeviceTypeChrome()))
        assertEquals(AccountSession.Kind.Unknown, AccountSessions.kindOf(SessionDeviceTypeUnknown()))
    }

    @Test
    fun `the lines under a device say what and where`() {
        Translator.use(Languages.ENGLISH)
        val s = L.messages
        val other = session(1, false, lastActive = 10_000)
        assertEquals("TMPlayer 1.27.0  ·  Android 15", AccountSessions.appLine(other))
        assertEquals("Berlin, Germany  ·  Active 2 hours ago", AccountSessions.placeLine(other, s, nowSec = 10_000 + 7_300))
        assertEquals("Berlin, Germany  ·  Online now", AccountSessions.placeLine(session(2, true, 0), s, nowSec = 99))
        assertEquals("Active just now", AccountSessions.lastActive(1_000, s, 1_030))
        assertEquals("Active 5 minutes ago", AccountSessions.lastActive(1_000, s, 1_300))
        assertEquals("Active 1 day ago", AccountSessions.lastActive(1_000, s, 1_000 + 90_000))
        assertTrue(AccountSessions.lastActive(1_000, s, 1_000 + 40L * 86_400).startsWith("Active on "))
        assertEquals("Unknown device", AccountSessions.title(session(3, false, 1, model = "").copy(platform = ""), s))
    }

    private fun tdlib(id: Long, current: Boolean) = Session(
        id, current, false, false, false, false, SessionDeviceTypeAndroid(), 1, "TMPlayer", "1.27.0", false,
        "Pixel 8", "Android", "15", 100, 200, "203.0.113.5", "Berlin, Germany",
    )

    @Test
    fun `ending this device's own session is refused, any other is allowed`() {
        val list = arrayOf(tdlib(1, current = true), tdlib(2, current = false), tdlib(3, current = false))
        assertTrue(AccountSessions.endsThisDevice(list, 1))
        assertFalse(AccountSessions.endsThisDevice(list, 2))
        assertFalse(AccountSessions.endsThisDevice(list, 3))
        // A session that is no longer listed is not this device's.
        assertFalse(AccountSessions.endsThisDevice(list, 99))
        assertFalse(AccountSessions.endsThisDevice(emptyArray(), 1))
    }

    @Test
    fun `the end all question names the devices, the first few and how many more`() {
        Translator.use(Languages.ENGLISH)
        val s = L.messages
        val three = listOf(session(1, false, 1, "Pixel 8"), session(2, false, 1, "Desktop"), session(3, false, 1, "iPad"))
        assertEquals("Pixel 8, Desktop, iPad", AccountSessions.devicesLine(three, s))
        val five = three + listOf(session(4, false, 1, "Firefox"), session(5, false, 1, "Chrome"))
        assertEquals("Pixel 8, Desktop, iPad, and 2 more", AccountSessions.devicesLine(five, s))
        assertEquals("", AccountSessions.devicesLine(emptyList(), s))
        assertTrue(s.sessionsEndAllConfirmDevices("Pixel 8").startsWith("Sign TMPlayer out on Pixel 8?"))
    }

    @Test
    fun `the speed note holds a while after telegram says so, never for premium`() {
        assertTrue(SpeedLimit.limited(premium = false, limitedAtMs = 1_000, nowMs = 2_000))
        assertFalse(SpeedLimit.limited(premium = true, limitedAtMs = 1_000, nowMs = 2_000))
        assertFalse(SpeedLimit.limited(premium = false, limitedAtMs = 1_000, nowMs = 1_000 + SpeedLimit.HOLDS_MS + 1))
        assertFalse(SpeedLimit.limited(premium = false, limitedAtMs = 0, nowMs = 2_000))
    }
}

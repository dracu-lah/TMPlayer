package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInStepsTest {

    private val countries = listOf(
        Country("GB", "United Kingdom", listOf("44")),
        Country("US", "United States", listOf("1")),
        Country("BS", "Bahamas", listOf("1242")),
        Country("IN", "India", listOf("91")),
    )

    @Test
    fun `a pasted international number is split into its dial code and the rest`() {
        assertEquals("44" to "7700900123", PhoneCountries.splitInternational("+44 7700 900123", countries))
        assertEquals("91" to "9876543210", PhoneCountries.splitInternational("0091 98765 43210", countries))
        assertEquals("1242" to "3591234", PhoneCountries.splitInternational("+1 (242) 359-1234", countries))
        assertEquals("1" to "2025550123", PhoneCountries.splitInternational("+1 202 555 0123", countries))
    }

    @Test
    fun `a national number is not an international one`() {
        assertNull(PhoneCountries.splitInternational("07700 900123", countries))
        assertNull(PhoneCountries.splitInternational("7700900123", countries))
        assertNull(PhoneCountries.splitInternational("+", countries))
        assertNull(PhoneCountries.splitInternational("+99 123", countries))
    }

    @Test
    fun `digits are laid onto TDLib's pattern without a separator trailing the caret`() {
        assertEquals("7700", PhoneFormat.format("7700", "7700 ------"))
        assertEquals("7700 9", PhoneFormat.format("77009", "---- ------"))
        assertEquals("98765 43210", PhoneFormat.format("9876543210", "----- -----"))
        assertEquals("98765 432109", PhoneFormat.format("98765432109", "----- -----"))
        assertEquals("123", PhoneFormat.format("123", null))
        assertEquals("", PhoneFormat.format("", "--- ---"))
    }

    @Test
    fun `offsets map between the typed digits and the drawn number`() {
        val drawn = PhoneFormat.format("77009001", "---- ------")
        assertEquals("7700 9001", drawn)
        assertEquals(0, PhoneFormat.toFormatted(0, drawn))
        assertEquals(4, PhoneFormat.toFormatted(4, drawn))
        assertEquals(6, PhoneFormat.toFormatted(5, drawn))
        assertEquals(drawn.length, PhoneFormat.toFormatted(8, drawn))
        assertEquals(4, PhoneFormat.toRaw(5, drawn))
        assertEquals(8, PhoneFormat.toRaw(drawn.length, drawn))
        for (raw in 0..8) assertEquals(raw, PhoneFormat.toRaw(PhoneFormat.toFormatted(raw, drawn), drawn))
    }

    @Test
    fun `a whole international number is drawn with its dial code apart`() {
        assertEquals("+44 7700 9001", PhoneFormat.formatInternational("+4477009001", countries, "---- ------"))
        assertEquals("+44", PhoneFormat.formatInternational("+44", countries, null))
        assertEquals("+99123", PhoneFormat.formatInternational("+99123", countries, null))
        val drawn = PhoneFormat.formatInternational("+4477009001", countries, "---- ------")
        assertEquals(3, PhoneFormat.toFormatted(3, drawn))
        assertEquals(5, PhoneFormat.toFormatted(4, drawn))
        for (raw in 0.."+4477009001".length) assertEquals(raw, PhoneFormat.toRaw(PhoneFormat.toFormatted(raw, drawn), drawn))
    }

    @Test
    fun `a pasted text message gives up just its code`() {
        assertEquals("12345", CodeInput.clean("Telegram code: 12345\n\nDo not give this code to anyone, even if they say they are from Telegram! 2", true, 5))
        assertEquals("12345", CodeInput.clean("12 345", true, 5))
        assertEquals("12345", CodeInput.clean("1234567", true, 5))
        assertEquals("123", CodeInput.clean("1a2b3", true, 0))
        assertEquals("blue sky", CodeInput.clean("blue sky", false, 0))
    }

    @Test
    fun `a full code sends itself once, and only a code of digits`() {
        assertTrue(CodeInput.shouldAutoSubmit("12345", 5, true, null))
        assertFalse(CodeInput.shouldAutoSubmit("12345", 5, true, "12345"))
        assertTrue(CodeInput.shouldAutoSubmit("12346", 5, true, "12345"))
        assertFalse(CodeInput.shouldAutoSubmit("1234", 5, true, null))
        assertFalse(CodeInput.shouldAutoSubmit("12345", 5, false, null))
        assertFalse(CodeInput.shouldAutoSubmit("12345", 0, true, null))
    }

    @Test
    fun `a QR code warns before it changes and is veiled once TDLib could not renew it`() {
        val life = QrClock.lifetime(null)
        assertEquals(QrClock.DEFAULT_LIFETIME_MS, life)
        assertEquals(QrPhase.Fresh, QrClock.phase(0, life))
        assertEquals(QrPhase.ExpiringSoon, QrClock.phase(life - QrClock.SOON_MS, life))
        assertEquals(QrPhase.Renewing, QrClock.phase(life + 1, life))
        assertEquals(QrPhase.Expired, QrClock.phase(QrClock.STALE_MS, life))
        assertEquals(1f, QrClock.remaining(0, life))
        assertEquals(0f, QrClock.remaining(life * 2, life))
    }

    @Test
    fun `the lifetime follows the gap TDLib actually renews at, within reason`() {
        assertEquals(25_000L, QrClock.lifetime(25_000L))
        assertEquals(QrClock.DEFAULT_LIFETIME_MS, QrClock.lifetime(1_000L))
        assertEquals(QrClock.DEFAULT_LIFETIME_MS, QrClock.lifetime(120_000L))
    }

    @Test
    fun `the seconds until a moment never go below zero`() {
        assertEquals(30, SignInWaits.secondsUntil(1_000_030, 1_000_000_000L))
        assertEquals(0, SignInWaits.secondsUntil(10, 1_000_000_000L))
    }
}

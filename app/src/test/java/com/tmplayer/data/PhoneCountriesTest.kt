package com.tmplayer.data

import dev.g000sha256.tdl.dto.CountryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCountriesTest {

    private val india = CountryInfo("IN", "India", "India", false, arrayOf("91"))
    private val uk = CountryInfo("GB", "United Kingdom", "United Kingdom", false, arrayOf("44"))
    private val usa = CountryInfo("US", "United States", "United States", false, arrayOf("1"))
    private val bahamas = CountryInfo("BS", "Bahamas", "Bahamas", false, arrayOf("1242"))
    private val hidden = CountryInfo("XX", "Nowhere", "Nowhere", true, arrayOf("999"))

    private val all = PhoneCountries.from(listOf(uk, india, usa, bahamas, hidden))

    @Test
    fun `the list is sorted by name and drops what Telegram will not accept`() {
        assertEquals(listOf("Bahamas", "India", "United Kingdom", "United States"), all.map { it.name })
    }

    @Test
    fun `a country with no calling code has nothing to dial and is left out`() {
        val codeless = CountryInfo("ZZ", "Codeless", "Codeless", false, emptyArray())
        assertTrue(PhoneCountries.from(listOf(codeless)).isEmpty())
    }

    @Test
    fun `search takes a name, an ISO code or a dial code, with or without the plus`() {
        val country = PhoneCountries.byIso(all, "IN")!!
        assertTrue(PhoneCountries.matches(country, "ind"))
        assertTrue(PhoneCountries.matches(country, "INDIA"))
        assertTrue(PhoneCountries.matches(country, "in"))
        assertTrue(PhoneCountries.matches(country, "+91"))
        assertTrue(PhoneCountries.matches(country, "9"))
        assertTrue(PhoneCountries.matches(country, ""))
        assertFalse(PhoneCountries.matches(country, "44"))
    }

    @Test
    fun `a dial code picks the country that owns exactly it, not one that starts with it`() {
        assertEquals("US", PhoneCountries.byDialCode(all, "1")?.iso)
        assertEquals("BS", PhoneCountries.byDialCode(all, "1242")?.iso)
        assertNull(PhoneCountries.byDialCode(all, ""))
        assertNull(PhoneCountries.byDialCode(all, "12"))
    }

    @Test
    fun `a flag is the country's own letters, and nothing at all for a code that is not one`() {
        assertEquals("🇮🇳", flagEmoji("IN"))
        assertEquals("🇮🇳", flagEmoji("in"))
        assertEquals("", flagEmoji("USA"))
        assertEquals("", flagEmoji("1"))
        assertEquals("", flagEmoji(""))
    }
}

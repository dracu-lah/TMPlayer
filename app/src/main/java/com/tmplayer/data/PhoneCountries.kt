package com.tmplayer.data

import android.content.Context
import android.telephony.TelephonyManager
import dev.g000sha256.tdl.dto.CountryInfo
import java.util.Locale

/**
 * One row of the country picker.
 *
 * A trimmed [CountryInfo]: the picker sorts, searches and displays, and none of those want an
 * array of calling codes or a hidden flag hanging off them. Kept out of the UI package because
 * the matching below is ordinary logic that a test can reach.
 */
data class Country(
    /** ISO 3166-1 alpha-2, as Telegram spells it: "IN", "GB". */
    val iso: String,
    val name: String,
    /** Every code that reaches this country, most common first. */
    val dialCodes: List<String>,
) {
    /** The one shown in the field. Countries with several codes are led by their usual one. */
    val dialCode: String get() = dialCodes.firstOrNull().orEmpty()

    val flag: String get() = flagEmoji(iso)
}

object PhoneCountries {

    /** Telegram's list, in the shape the picker wants, sorted the way a reader scans it. */
    fun from(countries: List<CountryInfo>): List<Country> = countries
        .filterNot { it.isHidden }
        .map { Country(it.countryCode, it.name, it.callingCodes.toList()) }
        .filter { it.dialCode.isNotEmpty() }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

    /**
     * Whether a country answers to what has been typed in the search box.
     *
     * Both halves of the box are the same box: people look for "India" and people look for "91",
     * and Telegram accepts either without a mode switch, so a query is tried against the name and
     * against the codes. A leading plus is ignored, since typing one is the natural thing to do.
     */
    fun matches(country: Country, query: String): Boolean {
        val q = query.trim().removePrefix("+").lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        if (country.name.lowercase(Locale.ROOT).contains(q)) return true
        if (country.iso.lowercase(Locale.ROOT) == q) return true
        return country.dialCodes.any { it.startsWith(q) }
    }

    /** The country whose dial code the user has typed, longest code first so "1" loses to "1242". */
    fun byDialCode(countries: List<Country>, dial: String): Country? {
        val digits = dial.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return countries.filter { it.dialCodes.contains(digits) }.minByOrNull { it.name }
    }

    fun byIso(countries: List<Country>, iso: String): Country? =
        countries.firstOrNull { it.iso.equals(iso, ignoreCase = true) }

    /**
     * Where the device thinks it is, for when Telegram will not say.
     *
     * The SIM is asked before the network: a phone roaming abroad still holds the account
     * registered at home, which is the number being typed. The locale is the last resort and the
     * weakest of the three, since it says what language somebody reads rather than where they are.
     */
    fun deviceCountryCode(context: Context): String {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = telephony?.simCountryIso.orEmpty()
        if (sim.isNotBlank()) return sim.uppercase(Locale.ROOT)
        val network = telephony?.networkCountryIso.orEmpty()
        if (network.isNotBlank()) return network.uppercase(Locale.ROOT)
        return Locale.getDefault().country.uppercase(Locale.ROOT)
    }
}

/**
 * The flag for an ISO code, built out of regional indicator letters.
 *
 * There is no flag image to ship and no icon set to grow: the two letters of the country code map
 * onto the two code points the platform's own emoji font draws as that flag.
 */
fun flagEmoji(iso: String): String {
    val code = iso.trim().uppercase(Locale.ROOT)
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
    val builder = StringBuilder()
    for (letter in code) builder.appendCodePoint(REGIONAL_INDICATOR_A + (letter - 'A'))
    return builder.toString()
}

private const val REGIONAL_INDICATOR_A = 0x1F1E6

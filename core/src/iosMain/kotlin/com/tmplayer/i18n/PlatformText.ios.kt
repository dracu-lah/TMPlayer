@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.tmplayer.i18n

import com.tmplayer.platform.PlainLock
import com.tmplayer.platform.deviceLocaleTag
import platform.Foundation.NSBundle
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.NSLocaleScriptCode
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterPercentStyle
import platform.Foundation.NSString
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.ISOLanguageCodes
import platform.Foundation.availableLocaleIdentifiers
import platform.Foundation.canonicalLocaleIdentifierFromString
import platform.Foundation.componentsFromLocaleIdentifier
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.localizedStringForCountryCode
import platform.Foundation.localizedStringForLanguageCode
import platform.Foundation.localizedStringForScriptCode
import platform.Foundation.numberWithDouble
import platform.Foundation.numberWithLongLong
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.uppercaseStringWithLocale

/**
 * On iOS a locale is its BCP 47 tag, with Foundation's NSLocale behind it for formatting. The same
 * shape as the browser's, so code written against either reads the same.
 */
actual class TmLocale(val tag: String) {
    /** Foundation's locale for [tag], made once: NSLocale is immutable and safe to share. */
    internal val nsLocale: NSLocale by lazy {
        NSLocale(localeIdentifier = NSLocale.canonicalLocaleIdentifierFromString(tag))
    }

    /** The language part, as `java.util.Locale.language` reads it. */
    val language: String get() = tag.substringBefore('-').lowercase()

    /** The region, when the tag has one: "BR" for "pt-BR". */
    val country: String get() = tag.split('-').drop(1).firstOrNull { it.length == 2 }?.uppercase().orEmpty()

    override fun equals(other: Any?): Boolean = other is TmLocale && other.tag == tag
    override fun hashCode(): Int = tag.hashCode()
    override fun toString(): String = tag

    fun toLanguageTag(): String = tag

    /** The language's three letter code, as java.util.Locale.isO3Language; empty when it has none. */
    val isO3Language: String get() = PlatformText.iso3Of(language).orEmpty()

    /** This language named in [inLocale], as java.util.Locale.getDisplayLanguage does. */
    fun getDisplayLanguage(inLocale: TmLocale): String = PlatformText.languageName(language, inLocale).ifEmpty { tag }

    /** This locale's region named in [inLocale], as java.util.Locale.getDisplayCountry does; empty when it has none. */
    fun getDisplayCountry(inLocale: TmLocale): String =
        if (country.isEmpty()) "" else PlatformText.regionName(tag, inLocale) ?: country


    /** java.util.Locale's constants and factories, for the shared code that names them. */
    companion object {
        val ROOT = TmLocale("und")
        val US = TmLocale("en-US")
        val ENGLISH = TmLocale("en")
        fun forLanguageTag(tag: String): TmLocale = TmLocale(tag)

        /** The two letter codes of the languages Foundation knows, as java.util.Locale.getISOLanguages. */
        fun getISOLanguages(): Array<String> =
            NSLocale.ISOLanguageCodes.filterIsInstance<String>().filter { it.length == 2 }.toTypedArray()

        /** The device's locale, as Locale.getDefault() is on Android. */
        fun getDefault(): TmLocale = TmLocale(deviceLocaleTag())

        /** Every locale Foundation has data for, as Locale.getAvailableLocales(). */
        fun getAvailableLocales(): Array<TmLocale> =
            NSLocale.availableLocaleIdentifiers.filterIsInstance<String>().map { TmLocale(it.replace('_', '-')) }.toTypedArray()
    }
}

actual fun localeOf(tag: String): TmLocale = TmLocale(tag.ifBlank { "en" })

actual fun languageOf(locale: TmLocale): String = locale.language

/** Gregorian, as java.time's ISO chronology, whatever calendar the device is set to. */
private fun gregorian(): NSCalendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)

private fun dateOf(epochMillis: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)

internal actual object PlatformText {
    // Formatters are costly to make and safe to share once set up, so each configuration is made
    // once and never changed afterwards. Only the device's time zone is read at use, as the JVM does.
    private val lock = PlainLock()
    private val numbers = HashMap<String, NSNumberFormatter>()
    private val dates = HashMap<String, NSDateFormatter>()

    private fun number(key: String, make: () -> NSNumberFormatter): NSNumberFormatter =
        lock.locked { numbers.getOrPut(key, make) }

    private fun dateFormatter(key: String, make: () -> NSDateFormatter): NSDateFormatter =
        lock.locked { dates.getOrPut(key + "|" + NSTimeZone.localTimeZone.name, make) }

    actual fun decimal(locale: TmLocale, value: Double, minFraction: Int, maxFraction: Int): String =
        number("decimal|${locale.tag}|$minFraction|$maxFraction") {
            NSNumberFormatter().apply {
                this.locale = locale.nsLocale
                numberStyle = NSNumberFormatterDecimalStyle
                usesGroupingSeparator = true
                // The maximum first, as on the JVM: a minimum above the old maximum would raise it.
                maximumFractionDigits = maxFraction.toULong()
                minimumFractionDigits = minFraction.toULong()
            }
        }.stringFromNumber(NSNumber.numberWithDouble(value)).orEmpty()

    actual fun integer(locale: TmLocale, value: Long, grouping: Boolean, minDigits: Int): String =
        number("integer|${locale.tag}|$grouping|$minDigits") {
            NSNumberFormatter().apply {
                this.locale = locale.nsLocale
                numberStyle = NSNumberFormatterDecimalStyle
                maximumFractionDigits = 0u
                usesGroupingSeparator = grouping
                minimumIntegerDigits = minDigits.coerceAtLeast(1).toULong()
            }
        }.stringFromNumber(NSNumber.numberWithLongLong(value)).orEmpty()

    actual fun percent(locale: TmLocale, fraction: Double): String =
        number("percent|${locale.tag}") {
            NSNumberFormatter().apply {
                this.locale = locale.nsLocale
                numberStyle = NSNumberFormatterPercentStyle
                maximumFractionDigits = 0u
            }
        }.stringFromNumber(NSNumber.numberWithDouble(fraction)).orEmpty()

    actual fun date(locale: TmLocale, epochMillis: Long): String =
        styled(locale, NSDateFormatterMediumStyle, NSDateFormatterNoStyle).stringFromDate(dateOf(epochMillis))

    actual fun dateTime(locale: TmLocale, epochMillis: Long): String =
        styled(locale, NSDateFormatterMediumStyle, NSDateFormatterShortStyle).stringFromDate(dateOf(epochMillis))

    private fun styled(locale: TmLocale, dateStyle: ULong, timeStyle: ULong): NSDateFormatter =
        dateFormatter("styled|${locale.tag}|$dateStyle|$timeStyle") {
            NSDateFormatter().apply {
                this.locale = locale.nsLocale
                calendar = gregorian()
                timeZone = NSTimeZone.localTimeZone
                this.dateStyle = dateStyle
                this.timeStyle = timeStyle
            }
        }

    actual fun dayMonth(locale: TmLocale, epochMillis: Long, withYear: Boolean): String =
        dateFormatter("dayMonth|${locale.tag}|$withYear") {
            NSDateFormatter().apply {
                this.locale = locale.nsLocale
                calendar = gregorian()
                timeZone = NSTimeZone.localTimeZone
                dateFormat = if (withYear) "d MMM yyyy" else "d MMM"
            }
        }.stringFromDate(dateOf(epochMillis))

    actual fun languageName(iso1: String, locale: TmLocale): String =
        locale.nsLocale.localizedStringForLanguageCode(iso1).orEmpty()

    actual fun scriptName(tag: String, locale: TmLocale): String? =
        partOf(tag, NSLocaleScriptCode)?.let { locale.nsLocale.localizedStringForScriptCode(it) }?.takeIf { it.isNotEmpty() }

    actual fun regionName(tag: String, locale: TmLocale): String? =
        partOf(tag, NSLocaleCountryCode)?.let { locale.nsLocale.localizedStringForCountryCode(it) }?.takeIf { it.isNotEmpty() }

    /** One component of [tag] ("Hant" of "zh-Hant", "BR" of "pt-BR"), or null when it has none. */
    private fun partOf(tag: String, component: String?): String? =
        (NSLocale.componentsFromLocaleIdentifier(NSLocale.canonicalLocaleIdentifierFromString(tag))[component ?: return null] as? String)
            ?.takeIf { it.isNotEmpty() }

    // Foundation names languages but has no ISO 639-2 table: the one the browser uses.
    actual fun threeLetterCodes(): Map<String, String> = threeLetterLanguageCodes

    actual fun englishLanguageNames(): Map<String, String> {
        val english = TmLocale.ENGLISH.nsLocale
        return NSLocale.ISOLanguageCodes.filterIsInstance<String>().filter { it.length == 2 }.mapNotNull { code ->
            english.localizedStringForLanguageCode(code)?.takeIf { it.isNotEmpty() }?.let { it.lowercase() to code }
        }.toMap()
    }

    actual fun iso3Of(iso1: String): String? = threeLetterLanguageCodes.entries.firstOrNull { it.value == iso1 }?.key

    actual fun capitalize(text: String, locale: TmLocale): String {
        // As the JVM's replaceFirstChar: only a lower case first character changes, in the
        // language's own way (Turkish "i" becomes a dotted capital).
        if (text.isEmpty() || !text[0].isLowerCase()) return text
        return NSString.create(string = text.substring(0, 1)).uppercaseStringWithLocale(locale.nsLocale) + text.substring(1)
    }
}

/** A date in the language's medium form, in [zone] rather than the device's, as on the JVM. */
fun LocaleFormatter.date(epochMillis: Long, zone: java.time.ZoneId): String =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)
        .format(java.time.Instant.ofEpochMilli(epochMillis).atZone(zone))

/** A date in the medium form and a time in the short form, in [zone], as on the JVM. */
fun LocaleFormatter.dateTime(epochMillis: Long, zone: java.time.ZoneId): String =
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
        .withLocale(locale).format(java.time.Instant.ofEpochMilli(epochMillis).atZone(zone))

/** A catalog tag as the bundle names its files: letters, digits and hyphens only. */
private val catalogTag = Regex("[A-Za-z0-9-]{1,16}")

/**
 * `i18n/<tag>.json` from the app bundle, where the Xcode project copies the catalogs, or from the
 * folder a test binary runs in (core's build copies them there).
 */
internal actual fun bundledCatalog(tag: String): String? {
    if (!catalogTag.matches(tag)) return null
    val folder = NSBundle.mainBundle.resourcePath ?: return null
    return NSString.stringWithContentsOfFile("$folder/i18n/$tag.json", NSUTF8StringEncoding, null)
}

internal actual fun parseCatalog(json: String): Map<String, String> = catalogFromJson(json)

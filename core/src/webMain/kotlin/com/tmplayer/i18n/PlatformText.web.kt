package com.tmplayer.i18n

/** In the browser a locale is its BCP 47 tag; `Intl` does the formatting. */
actual class TmLocale(val tag: String) {
    /** The language part, as `java.util.Locale.language` reads it. */
    val language: String get() = tag.substringBefore('-').lowercase()

    override fun equals(other: Any?): Boolean = other is TmLocale && other.tag == tag
    override fun hashCode(): Int = tag.hashCode()
    override fun toString(): String = tag

    fun toLanguageTag(): String = tag

    /** This language named in [inLocale], as java.util.Locale.getDisplayLanguage does. */
    fun getDisplayLanguage(inLocale: TmLocale): String = PlatformText.languageName(language, inLocale).ifEmpty { tag }

    /** The region, when the tag has one: "BR" for "pt-BR". */
    val country: String get() = tag.split('-').drop(1).firstOrNull { it.length == 2 }?.uppercase().orEmpty()

    /** java.util.Locale's constants and factories, for the shared code that names them. */
    companion object {
        val ROOT = TmLocale("und")
        val US = TmLocale("en-US")
        val ENGLISH = TmLocale("en")
        fun forLanguageTag(tag: String): TmLocale = TmLocale(tag)
        fun getDefault(): TmLocale = TmLocale(com.tmplayer.i18n.Translator.messages.tag)
    }
}

actual fun localeOf(tag: String): TmLocale = TmLocale(tag.ifBlank { "en" })

actual fun languageOf(locale: TmLocale): String = locale.language

private fun jsDecimal(tag: String, value: Double, minF: Int, maxF: Int): String =
    js("(new Intl.NumberFormat(tag, { minimumFractionDigits: minF, maximumFractionDigits: maxF })).format(value)")

private fun jsInteger(tag: String, value: Double, grouping: Boolean, minD: Int): String =
    js("(new Intl.NumberFormat(tag, { maximumFractionDigits: 0, useGrouping: grouping, minimumIntegerDigits: minD })).format(value)")

private fun jsPercent(tag: String, value: Double): String =
    js("(new Intl.NumberFormat(tag, { style: 'percent', maximumFractionDigits: 0 })).format(value)")

private fun jsDate(tag: String, millis: Double): String =
    js("(new Intl.DateTimeFormat(tag, { dateStyle: 'medium' })).format(new Date(millis))")

private fun jsDateTime(tag: String, millis: Double): String =
    js("(new Intl.DateTimeFormat(tag, { dateStyle: 'medium', timeStyle: 'short' })).format(new Date(millis))")

private fun jsDayMonth(tag: String, millis: Double, year: Boolean): String =
    js("(new Intl.DateTimeFormat(tag, year ? { day: 'numeric', month: 'short', year: 'numeric' } : { day: 'numeric', month: 'short' })).format(new Date(millis))")

private fun jsDisplayName(tag: String, type: String, code: String): String =
    js("(() => { try { return (new Intl.DisplayNames([tag], { type: type, fallback: 'none' })).of(code) || ''; } catch (e) { return ''; } })()")

private fun jsScriptOf(tag: String): String =
    js("(() => { try { return (new Intl.Locale(tag)).script || ''; } catch (e) { return ''; } })()")

private fun jsRegionOf(tag: String): String =
    js("(() => { try { return (new Intl.Locale(tag)).region || ''; } catch (e) { return ''; } })()")

private fun jsUpperFirst(tag: String, text: String): String =
    js("text.length ? text.charAt(0).toLocaleUpperCase(tag) + text.slice(1) : text")

internal actual object PlatformText {
    actual fun decimal(locale: TmLocale, value: Double, minFraction: Int, maxFraction: Int): String =
        jsDecimal(locale.tag, value, minFraction, maxFraction)

    actual fun integer(locale: TmLocale, value: Long, grouping: Boolean, minDigits: Int): String =
        jsInteger(locale.tag, value.toDouble(), grouping, minDigits.coerceIn(1, 21))

    actual fun percent(locale: TmLocale, fraction: Double): String = jsPercent(locale.tag, fraction)

    actual fun date(locale: TmLocale, epochMillis: Long): String = jsDate(locale.tag, epochMillis.toDouble())

    actual fun dateTime(locale: TmLocale, epochMillis: Long): String = jsDateTime(locale.tag, epochMillis.toDouble())

    actual fun dayMonth(locale: TmLocale, epochMillis: Long, withYear: Boolean): String =
        jsDayMonth(locale.tag, epochMillis.toDouble(), withYear)

    actual fun languageName(iso1: String, locale: TmLocale): String = jsDisplayName(locale.tag, "language", iso1)

    actual fun scriptName(tag: String, locale: TmLocale): String? =
        jsScriptOf(tag).takeIf { it.isNotEmpty() }?.let { jsDisplayName(locale.tag, "script", it) }?.takeIf { it.isNotEmpty() }

    actual fun regionName(tag: String, locale: TmLocale): String? =
        jsRegionOf(tag).takeIf { it.isNotEmpty() }?.let { jsDisplayName(locale.tag, "region", it) }?.takeIf { it.isNotEmpty() }

    // The browser has no ISO 639-2 table of its own.
    actual fun threeLetterCodes(): Map<String, String> = threeLetterLanguageCodes

    actual fun englishLanguageNames(): Map<String, String> =
        threeLetterCodes().values.distinct().mapNotNull { code ->
            jsDisplayName("en", "language", code).takeIf { it.isNotEmpty() }?.let { it.lowercase() to code }
        }.toMap()

    actual fun iso3Of(iso1: String): String? = threeLetterCodes().entries.firstOrNull { it.value == iso1 }?.key

    actual fun capitalize(text: String, locale: TmLocale): String = jsUpperFirst(locale.tag, text)
}

/** The browser has no classpath: the page fetches catalogs and hands them to Translator.provide. */
internal actual fun bundledCatalog(tag: String): String? = null

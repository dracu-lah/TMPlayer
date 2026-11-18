package com.tmplayer.platform

import com.tmplayer.i18n.TmLocale
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

/** [zone] as the JDK's. */
fun TmZone.toZoneId(): ZoneId = id?.let(ZoneId::of) ?: ZoneId.systemDefault()

/** A JDK zone as the pure one. */
fun ZoneId.toTmZone(): TmZone = TmZone(id)

actual fun localEpochDay(epochMillis: Long, zone: TmZone): Long =
    Instant.ofEpochMilli(epochMillis).atZone(zone.toZoneId()).toLocalDate().toEpochDay()

actual fun localYear(epochMillis: Long, zone: TmZone): Int = Instant.ofEpochMilli(epochMillis).atZone(zone.toZoneId()).year

actual fun dayMonthIn(locale: TmLocale, epochMillis: Long, zone: TmZone, withYear: Boolean): String =
    DateTimeFormatter.ofPattern(if (withYear) "d MMM yyyy" else "d MMM", locale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone.toZoneId()))

actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = ConcurrentHashMap()

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO

actual fun stripCombiningMarks(text: String): String {
    // Plain ASCII has no marks, and the first Normalizer call loads ICU data, which on a weak phone
    // is long enough to hold a frame (Sentry ANR 161355951), so most titles never reach it.
    if (text.all { it.code < 0x80 }) return text
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    val builder = StringBuilder(decomposed.length)
    for (character in decomposed) {
        when (Character.getType(character).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> Unit
            else -> builder.append(character)
        }
    }
    return builder.toString()
}

actual fun isOtherSymbol(codePoint: Int): Boolean = Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()

actual fun textCollator(locale: TmLocale, primary: Boolean): TextCollator {
    val collator = Collator.getInstance(locale).apply { if (primary) strength = Collator.PRIMARY }
    return TextCollator { a, b -> collator.compare(a, b) }
}

actual fun deviceLocaleTag(): String = java.util.Locale.getDefault().toLanguageTag()

actual class PlainLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = synchronized(this) { block() }
}

actual fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { java.security.SecureRandom().nextBytes(it) }

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.tmplayer.platform

import com.tmplayer.i18n.TmLocale
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSCaseInsensitiveSearch
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDiacriticInsensitiveSearch
import platform.Foundation.NSLocale
import platform.Foundation.NSMakeRange
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSRegularExpression
import platform.Foundation.NSString
import platform.Foundation.NSTimeZone
import platform.Foundation.compare
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.firstMatchInString
import platform.Foundation.localTimeZone
import platform.Foundation.preferredLanguages
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneWithName
import platform.posix.arc4random_buf
import kotlin.time.TimeSource

// Foundation's answers to what pure code asks the platform. Each one follows the JVM's actual
// (jvmSharedMain), not the browser's, so a name groups, sorts and dates the same way on iOS as on
// Android and the desktop.

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun currentYear(): Int = localYear(nowMillis(), systemZone())

/**
 * The proleptic Gregorian calendar in [zone], as java.time's ISO chronology: never the device's
 * own calendar, which may be Buddhist or Japanese and would move the year.
 */
private fun gregorianIn(zone: TmZone): NSCalendar {
    val calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
    calendar.timeZone = zone.id?.let { NSTimeZone.timeZoneWithName(it) } ?: NSTimeZone.localTimeZone
    return calendar
}

private fun dateOf(epochMillis: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)

/** [this] as the java.time stand in's zone, as on the JVM. */
fun TmZone.toZoneId(): java.time.ZoneId = id?.let(java.time.ZoneId::of) ?: java.time.ZoneId.systemDefault()

/** A java.time zone as the pure one. */
fun java.time.ZoneId.toTmZone(): TmZone = TmZone(id)

actual fun localEpochDay(epochMillis: Long, zone: TmZone): Long {
    val parts = gregorianIn(zone).components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay,
        fromDate = dateOf(epochMillis),
    )
    return epochDayOf(parts.year, parts.month, parts.day)
}

/** Days from 1970-01-01 to a proleptic Gregorian date, as LocalDate.toEpochDay counts them. */
internal fun epochDayOf(year: Long, month: Long, day: Long): Long {
    // Howard Hinnant's days_from_civil: years start in March, so February's leap day comes last.
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthFromMarch = (month + 9) % 12
    val dayOfYear = (153 * monthFromMarch + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}

actual fun localYear(epochMillis: Long, zone: TmZone): Int =
    gregorianIn(zone).components(NSCalendarUnitYear, fromDate = dateOf(epochMillis)).year.toInt()

actual fun dayMonthIn(locale: TmLocale, epochMillis: Long, zone: TmZone, withYear: Boolean): String {
    val calendar = gregorianIn(zone)
    val formatter = NSDateFormatter()
    formatter.locale = locale.nsLocale
    formatter.calendar = calendar
    formatter.timeZone = calendar.timeZone
    formatter.dateFormat = if (withYear) "d MMM yyyy" else "d MMM"
    return formatter.stringFromDate(dateOf(epochMillis))
}

actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = LockedMap()

/**
 * A map behind one lock, for the few maps several threads share. ConcurrentHashMap's views are live;
 * these are copies taken under the lock, so reading them is safe and writing through them changes
 * nothing. No caller in the pure half reaches the map through its views.
 */
private class LockedMap<K, V> : MutableMap<K, V> {
    private val lock = PlainLock()
    private val map = HashMap<K, V>()

    override val size: Int get() = lock.locked { map.size }
    override fun isEmpty(): Boolean = lock.locked { map.isEmpty() }
    override fun containsKey(key: K): Boolean = lock.locked { map.containsKey(key) }
    override fun containsValue(value: V): Boolean = lock.locked { map.containsValue(value) }
    override fun get(key: K): V? = lock.locked { map[key] }
    override fun put(key: K, value: V): V? = lock.locked { map.put(key, value) }
    override fun remove(key: K): V? = lock.locked { map.remove(key) }
    override fun putAll(from: Map<out K, V>) = lock.locked { map.putAll(from) }
    override fun clear() = lock.locked { map.clear() }
    override val keys: MutableSet<K> get() = lock.locked { map.keys.toMutableSet() }
    override val values: MutableCollection<V> get() = lock.locked { map.values.toMutableList() }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = lock.locked { HashMap(map).entries }
}

/** The iOS build's Dispatchers.IO ([IosIo]): threads as the JVM's make them, idle ones let go. */
actual val ioDispatcher: CoroutineDispatcher get() = IosIo

actual fun stripCombiningMarks(text: String): String {
    val decomposed = NSString.create(string = text).decomposedStringWithCanonicalMapping
    val builder = StringBuilder(decomposed.length)
    for (character in decomposed) {
        when (character.category) {
            CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK -> Unit
            else -> builder.append(character)
        }
    }
    return builder.toString()
}

/** Whether a code point beyond the BMP is "Symbol, other", remembered: emoji repeat across names. */
private val astralOtherSymbols = LockedMap<Int, Boolean>()

/** ICU's own category test, for code points Kotlin's Char cannot hold. */
private val otherSymbolPattern: NSRegularExpression by lazy {
    requireNotNull(NSRegularExpression.regularExpressionWithPattern("^\\p{So}$", 0u, null))
}

actual fun isOtherSymbol(codePoint: Int): Boolean {
    if (codePoint < 0x10000) return codePoint.toChar().category == CharCategory.OTHER_SYMBOL
    if (codePoint > 0x10FFFF) return false
    astralOtherSymbols[codePoint]?.let { return it }
    val offset = codePoint - 0x10000
    val text = charArrayOf(((offset shr 10) + 0xD800).toChar(), ((offset and 0x3FF) + 0xDC00).toChar()).concatToString()
    val symbol = otherSymbolPattern.firstMatchInString(text, 0u, NSMakeRange(0u, 2u)) != null
    astralOtherSymbols[codePoint] = symbol
    return symbol
}

actual fun textCollator(locale: TmLocale, primary: Boolean): TextCollator {
    val nsLocale = locale.nsLocale
    // Collator.PRIMARY ignores case and accents; the default strength tells them apart.
    val options = if (primary) NSCaseInsensitiveSearch or NSDiacriticInsensitiveSearch else 0uL
    return TextCollator { source, target ->
        val text = NSString.create(string = source)
        text.compare(target, options, NSMakeRange(0u, text.length), nsLocale).toInt()
    }
}

/** The first of the viewer's preferred languages, which is what Android's default locale is too. */
actual fun deviceLocaleTag(): String = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en-US"

/** Reentrant, as the JVM's synchronized is. */
actual class PlainLock actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

private val processStart = TimeSource.Monotonic.markNow()

actual fun monotonicMillis(): Long = processStart.elapsedNow().inWholeMilliseconds

actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    if (count > 0) bytes.usePinned { arc4random_buf(it.addressOf(0), count.convert()) }
    return bytes
}

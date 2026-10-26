package com.tmplayer.data

import com.tmplayer.i18n.Decimal
import com.tmplayer.i18n.PluralRules
import com.tmplayer.platform.TmZone
import com.tmplayer.platform.compareDigitRuns
import com.tmplayer.platform.localEpochDay
import com.tmplayer.platform.sortedByKey
import com.tmplayer.platform.stripCombiningMarks
import com.tmplayer.platform.utf16
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of `:core`, run on the JVM and on Node (js): the same answers on both is the
 * point, since the browser build reads names, plurals and pieces with this very code.
 */
class PureCoreTest {

    private fun parse(name: String) = MediaName.parse(name, maxYear = 2027)

    @Test
    fun mediaNameReadsTheSameEverywhere() {
        val film = parse("City.Archive.1999.1080p.BluRay.x264-GROUP.mkv")
        assertEquals("City Archive", film.title)
        assertEquals(1999, film.year)
        val episode = parse("Harbour.Notes.S01E01E02.1080p.mkv")
        assertEquals("Harbour Notes", episode.title)
        assertEquals(1, episode.season)
        assertEquals(1, episode.episode)
        assertEquals(2, episode.lastEpisode)
        val underscores = parse("Weekend_Sketchbook_2001_720p_BRRip.mkv")
        assertEquals("Weekend Sketchbook", underscores.title)
        assertNull(parse("Studio Log 4021 1080p BluRay x265.mkv").year)
    }

    @Test
    fun decorationSymbolsAreStrippedLikeTheJvm() {
        // Emoji and dingbats are "Symbol, other": they come off, the words stay.
        val parsed = parse("🎬 Harbour Notes (2026) ✨.mkv")
        assertEquals("Harbour Notes", parsed.title)
        assertEquals(2026, parsed.year)
    }

    @Test
    fun splitPiecesAndVideoNames() {
        val piece = assertNotNull(SplitPieces.pieceOf("Night.Train.2019.mkv.001"))
        assertEquals(1, piece.number)
        assertEquals("Night.Train.2019.mkv", piece.base)
        assertTrue(SplitPieces.isArchiveVolume("Course.7z.003"))
        assertTrue(MediaText.looksLikeVideo("Film.mkv", "application/octet-stream"))
        assertFalse(MediaText.looksLikeVideo("Notes.pdf", "application/pdf"))
        assertEquals(listOf("1080p"), MediaText.qualityTags("Film (2020) 1080p WEB.mkv"))
        assertEquals(listOf("4K"), MediaText.qualityTags("Film.2160p.mkv"))
        assertTrue(MediaText.isGenericName("VID_20241013_120000.mp4"))
        assertFalse(MediaText.isGenericName("1917.2019.1080p.mkv"))
    }

    @Test
    fun decimalsKeepTheirVisibleDigits() {
        assertEquals(2, Decimal.parse("1.50")!!.scale)
        assertEquals("1.50", Decimal.parse("1.50").toString())
        assertEquals(1500L, Decimal.parse("1.5E3")!!.toLong())
        assertEquals("-0.05", Decimal.parse("-0.05").toString())
        assertTrue(Decimal.parse("2.0")!!.isWhole)
        assertEquals(0, Decimal.parse("2")!!.compareTo(Decimal.parse("2.00")!!))
        assertEquals("1", Decimal.of(3).minus(2).toString())
        assertNull(Decimal.parse("abc"))
    }

    @Test
    fun pluralRulesAgreeOnEveryPlatform() {
        assertEquals("one", PluralRules.select("en", 1))
        assertEquals("other", PluralRules.select("en", 1.5))
        assertEquals("few", PluralRules.select("ru", 3))
        assertEquals("many", PluralRules.select("ru", 11))
        assertEquals("few", PluralRules.select("ar", 105))
        assertEquals("other", PluralRules.select("ja", 1))
    }

    @Test
    fun collectionHelpersMatchTheJdk() {
        assertEquals(listOf(1, 2, 3), mapOf(3 to "c", 1 to "a", 2 to "b").sortedByKey().keys.toList())
        assertTrue(compareDigitRuns("002", "10") < 0)
        assertEquals(0, compareDigitRuns("007", "7"))
        assertEquals("Pokemon", stripCombiningMarks("Pokémon"))
        val be = byteArrayOf(0, 'H'.code.toByte(), 0, 'i'.code.toByte())
        assertEquals("Hi", utf16(be, 0, 4, bigEndian = true))
    }

    @Test
    fun calendarDaysCountFromTheEpoch() {
        // Noon UTC is the same calendar day in every zone a test machine is likely to be in.
        assertEquals(0L, localEpochDay(12 * 3_600_000L, TmZone()))
        assertEquals(1L, localEpochDay(36 * 3_600_000L, TmZone()))
    }

    @Test
    fun theQrEncoderSizesByLength() {
        assertEquals(21, QrCode.encode("hello")!!.size)
        assertTrue(QrCode.encode("tg://login?token=" + "A".repeat(60))!!.size >= 33)
    }
}

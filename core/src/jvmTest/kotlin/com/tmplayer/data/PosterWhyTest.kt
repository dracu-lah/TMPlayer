package com.tmplayer.data

import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
import com.tmplayer.online.MetaProvider
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.tmplayer.data.PosterWhy.Reason

/** The one line a poster-less title page says, and when it says nothing (TS). */
class PosterWhyTest {

    private val found = MetaResult.Found(MetaInfo(MetaProvider.Tmdb, "1", MetaKind.Film, "Night Train", 2019))

    private fun why(
        fileName: String = "Night.Train.2019.1080p.mkv",
        lookupsOn: Boolean = true,
        markedWrong: Boolean = false,
        picked: Boolean = false,
        settled: Boolean = true,
        matched: Boolean = false,
        remembered: MetaResult? = null,
    ): PosterWhy.Why? {
        val name = MediaName.parse(fileName)
        return PosterWhy.of(lookupsOn, markedWrong, picked, settled, matched, name, MetaQuery.of(fileName), remembered)
    }

    @Test
    fun `lookups off says so and the fix is turning them on`() {
        val w = why(lookupsOn = false)!!
        assertEquals(Reason.LookupsOff, w.reason)
        assertTrue(w.turnsOnLookups)
    }

    @Test
    fun `a match the viewer turned down says so`() {
        assertEquals(Reason.MarkedWrong, why(markedWrong = true)!!.reason)
    }

    @Test
    fun `a name with nothing to ask about says why in words`() {
        assertEquals(NameDiagnosis.NoTitle, why("1080p.mkv")!!.diagnosis)
        assertEquals(Reason.NotRecognised, why("1080p.mkv")!!.reason)
        assertEquals(NameDiagnosis.NoTitle, why("2160p.HEVC.x265.mkv")!!.diagnosis)
        assertEquals(Reason.NotRecognised, why("X.mkv")!!.reason)
        assertEquals(NameDiagnosis.TooShort, why("X.mkv")!!.diagnosis)
        // Two letters are asked about (and found or not, as any other name).
        assertNull(why("Ab.mkv"))
        assertFalse(why("1080p.mkv")!!.turnsOnLookups)
    }

    @Test
    fun `asked and not found, and found but not backed, are two lines`() {
        assertEquals(Reason.NothingFound, why(remembered = MetaResult.NoMatch)!!.reason)
        assertEquals(Reason.Unsure, why(remembered = found)!!.reason)
    }

    @Test
    fun `nothing is said for a matched video, a picked one, an answer still coming, or an unknown outcome`() {
        assertNull(why(matched = true, remembered = MetaResult.NoMatch))
        assertNull(why(picked = true, remembered = MetaResult.NoMatch))
        assertNull(why(settled = false, remembered = MetaResult.NoMatch))
        // Offline or throttled: not known to be "nothing found".
        assertNull(why(remembered = MetaResult.Unavailable))
        assertNull(why(remembered = null))
        // Lookups off with the answer still coming still says lookups are off (nothing will come).
        assertEquals(Reason.LookupsOff, why(lookupsOn = false, settled = false)!!.reason)
        // And a matched video never says it, whatever else is true.
        assertNull(why(lookupsOn = false, matched = true))
    }

    @Test
    fun `every name of the corpus is diagnosed without a throw, with at most one reason`() {
        for (case in NameCorpus.CASES) {
            val name = MediaName.parse(case.fileName)
            val first = NameDiagnosis.of(name)
            assertEquals(case.fileName, first, NameDiagnosis.of(name))
            for (query in listOf(MetaQuery.of(case.fileName, case.caption), null)) {
                for (remembered in listOf(null, MetaResult.NoMatch, found, MetaResult.Unavailable, MetaResult.Off)) {
                    val w = PosterWhy.of(true, false, false, true, false, name, query, remembered)
                    // A name that asks something and has nothing remembered has no line.
                    if (query != null && remembered == null) assertNull(case.fileName, w)
                    if (query == null) assertEquals(case.fileName, Reason.NotRecognised, w!!.reason)
                }
            }
        }
    }

    @Test
    fun `garbage names never throw`() {
        for (text in listOf("", " ", ".", "\u0000", "x".repeat(100_000), "1".repeat(100_000), "((((", "😀😀")) {
            NameDiagnosis.ofFile(text)
            PosterWhy.of(true, false, false, true, false, MediaName.parse(text), MetaQuery.of(text), MetaResult.NoMatch)
        }
    }
}

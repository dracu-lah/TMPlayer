package com.tmplayer.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** U5: when subtitles are fetched for the viewer, which hit is tried first, and what is kept. */
class SubtitleSuggestionTest {

    private fun hit(
        id: String,
        language: String = "en",
        hash: Boolean = false,
        hi: Boolean = false,
        machine: Boolean = false,
        provider: SubtitleProvider = SubtitleProvider.OpenSubtitles,
    ) = SubtitleHit(provider, id, language, release = "r$id", hashMatch = hash, hearingImpaired = hi, machine = machine)

    private fun gate(
        enabled: Boolean = true,
        available: Boolean = true,
        kids: Boolean = false,
        wantsNone: Boolean = false,
        language: String? = "en",
        tracks: List<String?> = emptyList(),
        handled: Boolean = false,
        metered: Boolean = false,
        accepted: Boolean = false,
    ) = SubtitleSuggestion.Gate(enabled, available, kids, wantsNone, language, tracks, handled, metered, accepted)

    private fun skipped(why: SubtitleSuggestion.Skip) = SubtitleSuggestion.Decision.Skipped(why)

    // ---- decide -------------------------------------------------------------------------------

    @Test
    fun `it is off by default and every reason to stay quiet beats the rest`() {
        assertEquals(skipped(SubtitleSuggestion.Skip.Off), SubtitleSuggestion.decide(gate(enabled = false)))
        assertEquals(skipped(SubtitleSuggestion.Skip.Unavailable), SubtitleSuggestion.decide(gate(available = false)))
        assertEquals(skipped(SubtitleSuggestion.Skip.KidsLock), SubtitleSuggestion.decide(gate(kids = true)))
        assertEquals(skipped(SubtitleSuggestion.Skip.WantsNone), SubtitleSuggestion.decide(gate(wantsNone = true)))
        assertEquals(skipped(SubtitleSuggestion.Skip.NoLanguage), SubtitleSuggestion.decide(gate(language = null)))
        assertEquals(skipped(SubtitleSuggestion.Skip.NoLanguage), SubtitleSuggestion.decide(gate(language = "und")))
        assertEquals(skipped(SubtitleSuggestion.Skip.Handled), SubtitleSuggestion.decide(gate(handled = true)))
        assertEquals(SubtitleSuggestion.Decision.Fetch, SubtitleSuggestion.decide(gate()))
    }

    @Test
    fun `a subtitle in the viewer's language, in any spelling, means nothing is fetched`() {
        for (spelling in listOf("en", "eng", "en-GB", "EN")) {
            assertEquals(spelling, skipped(SubtitleSuggestion.Skip.HasLanguage), SubtitleSuggestion.decide(gate(tracks = listOf("fr", spelling))))
        }
        // Another language, or an undetermined one, is not the viewer's.
        assertEquals(SubtitleSuggestion.Decision.Fetch, SubtitleSuggestion.decide(gate(tracks = listOf("fr", null, "und"))))
    }

    @Test
    fun `a metered line asks first, once said yes it goes ahead`() {
        assertEquals(SubtitleSuggestion.Decision.AskMetered, SubtitleSuggestion.decide(gate(metered = true)))
        assertEquals(SubtitleSuggestion.Decision.Fetch, SubtitleSuggestion.decide(gate(metered = true, accepted = true)))
        // The other reasons still win on a metered line: nothing is asked about what will not be done.
        assertEquals(skipped(SubtitleSuggestion.Skip.KidsLock), SubtitleSuggestion.decide(gate(metered = true, kids = true)))
        assertEquals(skipped(SubtitleSuggestion.Skip.HasLanguage), SubtitleSuggestion.decide(gate(metered = true, tracks = listOf("en"))))
    }

    // ---- candidates ---------------------------------------------------------------------------

    @Test
    fun `a hash match comes before a name match and only the viewer's language is tried`() {
        val hits = listOf(hit("1"), hit("2", language = "fr", hash = true), hit("3", hash = true), hit("4"))
        assertEquals(listOf("3", "1", "4"), SubtitleSuggestion.candidates(hits, "en", emptySet()).map { it.id })
    }

    @Test
    fun `an ordinary subtitle comes before a hearing impaired one of the same kind`() {
        val hits = listOf(hit("1", hi = true), hit("2"), hit("3", hash = true, hi = true), hit("4", hash = true))
        assertEquals(listOf("4", "3", "2", "1"), SubtitleSuggestion.candidates(hits, "en", emptySet()).map { it.id })
    }

    @Test
    fun `machine translations stay out unless the viewer allowed them`() {
        val hits = listOf(hit("1", machine = true), hit("2"))
        assertEquals(listOf("2"), SubtitleSuggestion.candidates(hits, "en", emptySet()).map { it.id })
        assertEquals(listOf("1", "2"), SubtitleSuggestion.candidates(hits, "en", emptySet(), allowMachine = true).map { it.id })
    }

    @Test
    fun `the search's own order breaks a tie, and a turned down hit is never offered again`() {
        val hits = listOf(hit("a"), hit("b"), hit("c"))
        assertEquals(listOf("a", "b", "c"), SubtitleSuggestion.candidates(hits, "en", emptySet()).map { it.id })
        val rejected = setOf(SubtitleSuggestion.keyOf(hits[0]))
        assertEquals(listOf("b", "c"), SubtitleSuggestion.candidates(hits, "en", rejected).map { it.id })
        // The same id from another provider is another subtitle.
        val other = hit("a", provider = SubtitleProvider.Subdl)
        assertTrue(SubtitleSuggestion.candidates(listOf(other), "en", rejected).isNotEmpty())
        assertTrue(SubtitleSuggestion.candidates(hits, "en", hits.map(SubtitleSuggestion::keyOf).toSet()).isEmpty())
    }

    @Test
    fun `garbage in the hits is skipped`() {
        val hits = listOf(hit(""), hit("1", language = ""), hit("2", language = "xx"), hit("3", language = "pt-BR"))
        assertEquals(emptyList<String>(), SubtitleSuggestion.candidates(hits, "en", emptySet()).map { it.id })
        assertEquals(listOf("3"), SubtitleSuggestion.candidates(hits, "pt", emptySet()).map { it.id })
        assertTrue(SubtitleSuggestion.candidates(hits, null, emptySet()).isEmpty())
        assertTrue(SubtitleSuggestion.candidates(hits, "", emptySet()).isEmpty())
        assertTrue(SubtitleSuggestion.candidates(emptyList(), "en", emptySet()).isEmpty())
    }

    @Test
    fun `the source says the service by name and whether the file itself was matched`() {
        assertEquals(SubtitleSuggestion.Source("OpenSubtitles", byFile = true), SubtitleSuggestion.sourceOf(hit("1", hash = true)))
        assertEquals(SubtitleSuggestion.Source("SubDL", byFile = false), SubtitleSuggestion.sourceOf(hit("2", provider = SubtitleProvider.Subdl)))
    }

    // ---- the setting and the cache ------------------------------------------------------------

    @Test
    fun `the switch is off by default and the store keeps it`() {
        val dir = Files.createTempDirectory("tm-suggest").toFile()
        val file = File(dir, "account.properties")
        assertFalse(OnlineSubtitlesStore(file).now.suggest)
        OnlineSubtitlesStore(file).update { it.copy(suggest = true) }
        assertTrue(OnlineSubtitlesStore(file).now.suggest)
        OnlineSubtitlesStore(file).update { it.copy(suggest = false) }
        assertFalse(OnlineSubtitlesStore(file).now.suggest)
    }

    @Test
    fun `the cache stays under its size by dropping the oldest files, never the one just written`() {
        val dir = Files.createTempDirectory("tm-cache-bound").toFile()
        var clock = 1_800_000_000_000L
        val cache = SubtitleCache(dir, now = { clock }, maxBytes = 1_000)
        val line = "x".repeat(400)
        val first = cache.putFile(SubtitleProvider.OpenSubtitles, "1", SubtitleText.Text("srt", line))
        clock += 1_000
        first.setLastModified(clock)
        clock += 1_000
        cache.putFile(SubtitleProvider.OpenSubtitles, "2", SubtitleText.Text("srt", line))
        clock += 1_000
        val third = cache.putFile(SubtitleProvider.OpenSubtitles, "3", SubtitleText.Text("srt", line))
        assertTrue("under the bound", cache.bytes() <= 1_000)
        assertTrue("the one just written stays", third.isFile)
        assertEquals(null, cache.file(SubtitleProvider.OpenSubtitles, "1"))
    }
}

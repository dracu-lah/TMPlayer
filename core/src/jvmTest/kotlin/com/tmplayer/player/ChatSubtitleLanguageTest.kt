package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/** The language a subtitle file's name states, from codes, full names and names with a place (TS). */
class ChatSubtitleLanguageTest {

    private data class Case(val name: String, val language: String?, val base: String, val forced: Boolean = false, val hearing: Boolean = false)

    private val table = listOf(
        Case("Hindi.srt", "hi", ""),
        Case("English.forced.srt", "en", "", forced = true),
        Case("Show.S01E02.Hindi.srt", "hi", "Show.S01E02"),
        Case("Show.S01E02.English.SDH.srt", "en", "Show.S01E02", hearing = true),
        Case("Show.S01E02.eng.forced.srt", "en", "Show.S01E02", forced = true),
        Case("Show.S01E02.en.srt", "en", "Show.S01E02"),
        Case("Show.S01E02.pt-BR.srt", "pt-br", "Show.S01E02"),
        Case("Show.S01E02.Spanish (Latin America).srt", "es-419", "Show.S01E02"),
        Case("Show S01E02 Spanish (Spain).srt", "es-es", "Show S01E02"),
        Case("Show.S01E02.Portuguese (Brazil).srt", "pt-br", "Show.S01E02"),
        Case("Show.S01E02.Brazilian Portuguese.srt", "pt-br", "Show.S01E02"),
        Case("Show.S01E02.Chinese (Simplified).srt", "zh-hans", "Show.S01E02"),
        Case("Show.S01E02.Traditional Chinese.srt", "zh-hant", "Show.S01E02"),
        Case("Show.S01E02.French (Canada).srt", "fr-ca", "Show.S01E02"),
        Case("Show.S01E02.Tamil.srt", "ta", "Show.S01E02"),
        Case("Show.S01E02.Telugu.dubbed.srt", "te", "Show.S01E02"),
        Case("Show.S01E02.Malayalam.srt", "ml", "Show.S01E02"),
        Case("Show.S01E02.Kannada.srt", "kn", "Show.S01E02"),
        Case("Show.S01E02.Bengali.srt", "bn", "Show.S01E02"),
        Case("Show.S01E02.Marathi.srt", "mr", "Show.S01E02"),
        Case("Show.S01E02.Punjabi.srt", "pa", "Show.S01E02"),
        Case("Show.S01E02.Gujarati.srt", "gu", "Show.S01E02"),
        Case("Show.S01E02.Urdu.srt", "ur", "Show.S01E02"),
        Case("Show.S01E02.Español.srt", "es", "Show.S01E02"),
        Case("Show.S01E02.Deutsch.srt", "de", "Show.S01E02"),
        Case("Show.S01E02.Français.srt", "fr", "Show.S01E02"),
        Case("Show.S01E02.Russian.srt", "ru", "Show.S01E02"),
        Case("Show.S01E02.Arabic.srt", "ar", "Show.S01E02"),
        Case("Show.S01E02.Japanese.srt", "ja", "Show.S01E02"),
        Case("Show.S01E02.Korean.srt", "ko", "Show.S01E02"),
        Case("Show.S01E02.Thai.srt", "th", "Show.S01E02"),
        Case("Show.S01E02.Vietnamese.srt", "vi", "Show.S01E02"),
        Case("Show.S01E02.Indonesian.srt", "id", "Show.S01E02"),
        Case("Show.S01E02.Turkish.srt", "tr", "Show.S01E02"),
        Case("Show.S01E02.Dutch.srt", "nl", "Show.S01E02"),
        Case("Show.S01E02.Swedish.srt", "sv", "Show.S01E02"),
        Case("Show.S01E02.Greek.srt", "el", "Show.S01E02"),
        Case("Show.S01E02.Hebrew.srt", "he", "Show.S01E02"),
        Case("Show.S01E02.Persian.srt", "fa", "Show.S01E02"),
        Case("Show.S01E02.Ukrainian.srt", "uk", "Show.S01E02"),
        Case("Show.S01E02.Swahili.srt", "sw", "Show.S01E02"),
        Case("Show.S01E02.中文.srt", "zh", "Show.S01E02"),
        Case("show s01e02 HINDI.srt", "hi", "show s01e02"),
    )

    @Test
    fun `a language is read from a name, a code or a name with a place`() {
        for (case in table) {
            val tags = ChatSubtitles.tagsOf(case.name)
            assertEquals(case.name, case.language, tags.language)
            assertEquals(case.name, case.base, tags.base)
            assertEquals(case.name, case.forced, tags.forced)
            assertEquals(case.name, case.hearing, tags.hearing)
        }
    }

    @Test
    fun `two language words keep the last, and a title that is a language word keeps its title`() {
        assertEquals("en", ChatSubtitles.tagsOf("Show.S01E02.Hindi.English.srt").language)
        assertNull(ChatSubtitles.tagsOf("Hindi Medium.srt").language)
        assertEquals("Hindi Medium", ChatSubtitles.tagsOf("Hindi Medium.srt").base)
        assertNull(ChatSubtitles.tagsOf("Show.S01E02.1080p.srt").language)
        // A bracket after something that is no language is part of the name.
        assertEquals("Show (2020)", ChatSubtitles.tagsOf("Show (2020).srt").base)
        assertNull(ChatSubtitles.tagsOf("Show (2020).srt").language)
    }

    @Test
    fun `a viewer who prefers Hindi sees the Hindi file first`() {
        val video = ChatSubtitles.Video(messageId = 100L shl 20, fileName = "Show.S01E02.1080p.mkv")
        val docs = listOf("Show.S01E02.English.srt", "Show.S01E02.Hindi.srt", "Show.S01E02.Tamil.srt").mapIndexed { i, name ->
            ChatSubtitles.Doc(messageId = (101L + i) shl 20, fileId = i + 1, fileName = name, size = 20_000, date = 0)
        }
        val ranked = ChatSubtitles.rank(video, docs, preferred = listOf("hi"))
        assertEquals("hi", ranked.first().language)
        assertEquals(3, ranked.size)
    }

    @Test
    fun `the base of a name does not depend on the language word, whatever it is or the extension`() {
        val aliases = listOf("Hindi", "hin", "English", "Spanish (Latin America)", "Brazilian Portuguese", "pt-BR", "Chinese (Simplified)", "Español")
        for (alias in aliases) for (extension in listOf("srt", "ass", "vtt", "sub", "ssa")) {
            val tagged = ChatSubtitles.tagsOf("Show.S01E02.$alias.$extension")
            assertEquals("$alias.$extension", "Show.S01E02", tagged.base)
            assertTrue("$alias.$extension", tagged.language != null)
        }
    }

    @Test
    fun `garbage names return quickly without a language`() {
        val long = "x.".repeat(50_000) + "srt"
        val names = listOf("", ".", "..srt", "(", ")", "()", "(((((", "Show (", "Show ()", " (Latin America)", "(Latin America).srt", "Show.[[[[]]]].srt", long, "\u0000.srt", "Hindi" + " (".repeat(5000))
        val ms = measureTimeMillis {
            for (name in names) {
                val tags = ChatSubtitles.tagsOf(name)
                assertFalse(name.take(20), tags.base.length > name.length)
            }
        }
        assertTrue("took $ms ms", ms < 500)
    }
}

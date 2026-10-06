package com.tmplayer.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TranslatorTest {

    private val english = mapOf(
        "a.hello" to "Hello",
        "a.named" to "Hello, {name}",
        "a.videos" to "{count, plural, one {# video} other {# videos}}",
        "a.hidden" to "{count, plural, =0 {Nothing hidden} one {# hidden} other {# hidden}}",
        "a.kind" to "{kind, select, audio {Audio} subtitle {Subtitles} other {Track}}",
        "a.both" to "{who} has {count, plural, one {# file} other {# files}}",
        "a.quote" to "Can't stop, it''s '{literal}', '{#}' and #",
        "a.offset" to "{count, plural, offset:1 =0 {Nobody} =1 {{name}} one {{name} and # other} other {{name} and # others}}",
        "a.nested" to "{count, plural, one {{kind, select, audio {# audio track} other {# track}}} other {{kind, select, audio {# audio tracks} other {# tracks}}}}",
    ).mapValues { Icu.parse(it.value) }

    private fun translate(tag: String, vararg entries: Pair<String, String>): Messages {
        val json = org.json.JSONObject().apply {
            val groups = entries.groupBy({ it.first.substringBefore('.') }, { it.first.substringAfter('.') to it.second })
            for ((group, pairs) in groups) put(group, org.json.JSONObject().apply { pairs.forEach { put(it.first, it.second) } })
        }
        return Translator.build(tag, json.toString(), english)
    }

    @After
    fun backToEnglish() {
        Translator.pseudoEnabled = false
        Translator.use(Languages.ENGLISH)
    }

    @Test
    fun `placeholders are filled and numbers use the locale's digits`() {
        val en = Translator.build("en", null, english)
        assertEquals("Hello, Ana", en.format("a.named", "name" to "Ana"))
        assertEquals("1,234 videos", en.format("a.videos", "count" to 1234))
        val de = translate("de", "a.videos" to "{count, plural, one {# Video} other {# Videos}}")
        assertEquals("1.234 Videos", de.format("a.videos", "count" to 1234))
        assertEquals("1 Video", de.format("a.videos", "count" to 1))
    }

    @Test
    fun `a missing argument stays visible instead of crashing`() {
        val en = Translator.build("en", null, english)
        assertEquals("Hello, {name}", en.text("a.named"))
    }

    @Test
    fun `english plurals, exact cases and select`() {
        val en = Translator.build("en", null, english)
        assertEquals("1 video", en.format("a.videos", "count" to 1))
        assertEquals("0 videos", en.format("a.videos", "count" to 0))
        assertEquals("Nothing hidden", en.format("a.hidden", "count" to 0))
        assertEquals("3 hidden", en.format("a.hidden", "count" to 3))
        assertEquals("Audio", en.format("a.kind", "kind" to "audio"))
        assertEquals("Track", en.format("a.kind", "kind" to "video"))
        assertEquals("Ana has 2 files", en.format("a.both", "who" to "Ana", "count" to 2))
    }

    @Test
    fun `offset and nested arguments`() {
        val en = Translator.build("en", null, english)
        assertEquals("Nobody", en.format("a.offset", "count" to 0, "name" to "Ana"))
        assertEquals("Ana", en.format("a.offset", "count" to 1, "name" to "Ana"))
        assertEquals("Ana and 1 other", en.format("a.offset", "count" to 2, "name" to "Ana"))
        assertEquals("Ana and 4 others", en.format("a.offset", "count" to 5, "name" to "Ana"))
        assertEquals("1 audio track", en.format("a.nested", "count" to 1, "kind" to "audio"))
        assertEquals("3 tracks", en.format("a.nested", "count" to 3, "kind" to "subtitle"))
    }

    @Test
    fun `apostrophes are literal unless they quote syntax`() {
        val en = Translator.build("en", null, english)
        assertEquals("Can't stop, it's {literal}, {#} and #", en.text("a.quote"))
    }

    @Test
    fun `russian and ukrainian pick one, few and many`() {
        for (tag in listOf("ru", "uk")) {
            val m = translate(tag, "a.videos" to "{count, plural, one {# видео1} few {# видео2} many {# видео5} other {# видео}}")
            assertEquals("1 видео1", m.format("a.videos", "count" to 1))
            assertEquals("21 видео1", m.format("a.videos", "count" to 21))
            assertEquals("3 видео2", m.format("a.videos", "count" to 3))
            assertEquals("11 видео5", m.format("a.videos", "count" to 11))
            assertEquals("25 видео5", m.format("a.videos", "count" to 25))
            assertEquals("1,5 видео", m.format("a.videos", "count" to 1.5))
        }
    }

    @Test
    fun `arabic picks all six categories`() {
        val m = translate("ar", "a.videos" to "{count, plural, zero {z} one {o} two {t} few {f #} many {m #} other {x #}}")
        assertEquals("z", m.format("a.videos", "count" to 0))
        assertEquals("o", m.format("a.videos", "count" to 1))
        assertEquals("t", m.format("a.videos", "count" to 2))
        assertTrue(m.format("a.videos", "count" to 5).startsWith("f "))
        assertTrue(m.format("a.videos", "count" to 15).startsWith("m "))
        assertTrue(m.format("a.videos", "count" to 100).startsWith("x "))
    }

    @Test
    fun `a category the translation lacks falls back to other`() {
        val m = translate("ru", "a.videos" to "{count, plural, one {# видео} other {# видео!}}")
        assertEquals("5 видео!", m.format("a.videos", "count" to 5))
    }

    @Test
    fun `one form languages always read other`() {
        val m = translate("ja", "a.videos" to "{count, plural, other {動画 # 本}}")
        assertEquals("動画 1 本", m.format("a.videos", "count" to 1))
        assertEquals("動画 2 本", m.format("a.videos", "count" to 2))
    }

    @Test
    fun `untranslated keys fall back to english one by one`() {
        val fr = translate("fr", "a.hello" to "Bonjour")
        assertEquals("Bonjour", fr.text("a.hello"))
        assertTrue(fr.translates("a.hello"))
        assertEquals("Hello, Ana", fr.format("a.named", "name" to "Ana"))
        assertFalse(fr.translates("a.named"))
    }

    @Test
    fun `a broken or mismatched translation reads in english`() {
        val fr = translate(
            "fr",
            "a.hello" to "Bonjour {",
            "a.named" to "Bonjour, {nom}",
            "a.videos" to "{count, plural, one {# vidéo}}",
            "a.kind" to "{kind, select, audio {Audio} other {Piste}}",
        )
        assertEquals("Hello", fr.text("a.hello"))
        assertEquals("Hello, Ana", fr.format("a.named", "name" to "Ana"))
        assertEquals("2 videos", fr.format("a.videos", "count" to 2))
        assertEquals("Piste", fr.format("a.kind", "kind" to "x"))
    }

    @Test
    fun `keys the english does not have are ignored and unknown keys read as themselves`() {
        val fr = translate("fr", "a.ghost" to "Fantôme")
        assertFalse(fr.translates("a.ghost"))
        assertEquals("a.ghost", fr.text("a.ghost"))
        assertEquals("Hello", Translator.build("fr", "not json", english).text("a.hello"))
    }

    @Test
    fun `a language with no catalog yet is all english`() {
        // `xx-empty` is a test resource: a catalog with no keys, as a new language has at first.
        val empty = Translator.load("xx-empty")
        assertEquals("xx-empty", empty.tag)
        assertEquals(Translator.english().commonRetry, empty.commonRetry)
        // The shipped catalogs are translated.
        assertNotEquals(Translator.english().commonRetry, Translator.load("es-419").commonRetry)
        assertNotEquals(Translator.english().commonRetry, Translator.load("ar").commonRetry)
    }

    @Test
    fun `the generated accessors read the current language`() {
        assertEquals("Retry", L.commonRetry)
        assertEquals("1 video", L.browseVideosCount(1))
        assertEquals("12 videos", L.browseVideosCount(12))
        assertEquals("No videos hidden", L.browseVideosHidden(0))
        assertEquals("Subtitle track", L.playerTrackKind("subtitle"))
        assertEquals("Now in Deutsch. You can change it in Settings.", L.settingsLanguageSwitched("Deutsch"))
    }

    @Test
    fun `select switches the active language and the flow follows`() {
        assertEquals("en", Translator.messages.tag)
        assertEquals("es-419", Translator.select("", listOf("es-CL")))
        assertEquals("es-419", Translator.active.value.tag)
        assertEquals("es-419", L.messages.tag)
        assertEquals("en", Translator.select("en", listOf("es-CL")))
        assertEquals("en", L.messages.tag)
    }

    @Test
    fun `system default names the device's language, not the one picked`() {
        // The picker's "System default" row read Arabic while Arabic was picked, and after
        // switching back, because it asked the process locale, which Android rewrites.
        Translator.select("ar", listOf("es-CL", "en-GB"))
        assertEquals("ar", Translator.messages.tag)
        assertEquals("es-419", Translator.systemDefault())
        Translator.select("", listOf("es-CL", "en-GB"))
        assertEquals("es-419", Translator.systemDefault())
        // Nothing the app ships: English, as the app itself would show.
        Translator.select("ar", listOf("xx-YY"))
        assertEquals("en", Translator.systemDefault(fallback = listOf("ar")))
    }

    @Test
    fun `system default falls back before the first select`() {
        Translator.select("ar", emptyList())
        assertEquals("es-419", Translator.systemDefault(fallback = listOf("es-MX")))
    }

    @Test
    fun `the pseudo-locale accents, brackets and stretches every message`() {
        val xa = Translator.pseudo(english)
        val hello = xa.text("a.hello")
        assertEquals("[Ĥéļļö ··]", hello)
        assertTrue(hello.length > "Hello".length * 13 / 10)
        // Arguments keep their names and values: only the catalog's own words change.
        assertEquals("[Ĥéļļö, Ana ···]", xa.format("a.named", "name" to "Ana"))
        assertEquals("[1 ṽîðéö ···]", xa.format("a.videos", "count" to 1))
        assertEquals("[5 ṽîðéöš ···]", xa.format("a.videos", "count" to 5))
        assertEquals(Languages.PSEUDO, xa.tag)
        assertEquals("en", xa.locale.language)
    }

    @Test
    fun `the pseudo-locale is reachable only when enabled`() {
        Translator.pseudoEnabled = false
        assertEquals("en", Translator.select("en-XA", emptyList()))
        Translator.pseudoEnabled = true
        assertEquals("en-XA", Translator.select("en-XA", emptyList()))
        assertTrue(L.commonRetry.startsWith("[Ŕéţŕý"))
        Translator.use("en")
        assertEquals("Retry", L.commonRetry)
    }

    @Test
    fun `the formatter writes sizes, durations and numbers in the language`() {
        val en = Translator.english().formatter
        assertEquals("", en.size(0))
        assertEquals("12 KB", en.size(12 * 1024))
        assertEquals("350 MB", en.size(350L * 1024 * 1024))
        assertEquals("1.5 GB", en.size(1536L * 1024 * 1024))
        assertEquals("1h 05m", en.duration(3900))
        assertEquals("42m", en.duration(42 * 60))
        assertEquals("1m", en.duration(5))
        assertEquals("1:02:03", en.clock(3_723_000))
        assertEquals("4:07", en.clock(247_000))
        assertEquals("62%", en.percent(0.629))
        assertEquals("1,234,567", en.number(1_234_567))
        assertEquals("Oct 6, 2026", en.date(1_791_244_800_000, ZoneOffset.UTC))

        val de = Translator.build("de", null, emptyMap()).formatter
        assertEquals("1.234.567", de.number(1_234_567))
        assertEquals("1,5", de.decimal(1.5, 1))
        assertEquals("06.10.2026", de.date(1_791_244_800_000, ZoneOffset.UTC))
    }

    @Test
    fun `track languages are named in the ui language from any iso code`() {
        val en = Translator.english().formatter
        assertEquals("German", en.trackLanguage("de"))
        assertEquals("German", en.trackLanguage("ger"))
        assertEquals("German", en.trackLanguage("deu"))
        assertEquals("French", en.trackLanguage("fre"))
        assertEquals("Malayalam", en.trackLanguage("mal"))
        assertEquals("Japanese", en.trackLanguage("jpn"))
        assertEquals("Portuguese", en.trackLanguage("pt-BR"))
        assertEquals("Indonesian", en.trackLanguage("in"))
        assertEquals(null, en.trackLanguage("und"))
        assertEquals(null, en.trackLanguage(""))
        assertEquals(null, en.trackLanguage(null))
        assertEquals("qqq", en.trackLanguage("qqq"))

        val de = Translator.build("de", null, emptyMap()).formatter
        assertEquals("Englisch", de.trackLanguage("eng"))
        val fr = Translator.build("fr", null, emptyMap()).formatter
        assertEquals("Anglais", fr.trackLanguage("en"))
        assertNotEquals("English", Translator.build("ja", null, emptyMap()).formatter.trackLanguage("en"))
    }

    @Test
    fun `a track language's script and region are named apart from the language`() {
        val en = Translator.english().formatter
        assertEquals("Simplified", en.trackLanguageVariant("zh-Hans"))
        assertEquals("Traditional", en.trackLanguageVariant("zh-Hant"))
        assertEquals("Brazil", en.trackLanguageVariant("pt-BR"))
        assertEquals(null, en.trackLanguageVariant("zh"))
        assertEquals(null, en.trackLanguageVariant(null))
    }
}

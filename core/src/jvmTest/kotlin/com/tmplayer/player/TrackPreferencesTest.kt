package com.tmplayer.player

import com.tmplayer.data.ResumeState
import com.tmplayer.data.TrackChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPreferencesTest {

    private val japaneseAudio = TrackPreferences(audioLanguage = "ja", mode = SubtitleMode.Always)

    // ---- normalisation ----------------------------------------------------------------------

    @Test
    fun `three letter codes come down to two letters`() {
        assertEquals("ja", TrackLanguages.normalise("jpn"))
        assertEquals("en", TrackLanguages.normalise("eng"))
        assertEquals("ml", TrackLanguages.normalise("mal"))
        assertEquals("ta", TrackLanguages.normalise("TAM"))
    }

    @Test
    fun `bibliographic codes match their terminology twins`() {
        assertEquals("de", TrackLanguages.normalise("ger"))
        assertEquals("de", TrackLanguages.normalise("deu"))
        assertEquals("fr", TrackLanguages.normalise("fre"))
        assertEquals("zh", TrackLanguages.normalise("chi"))
        assertTrue(TrackLanguages.same("ger", "de"))
        assertTrue(TrackLanguages.same("fre", "fra"))
    }

    @Test
    fun `regions, underscores, old codes and names are folded`() {
        assertEquals("en", TrackLanguages.normalise("en-US"))
        assertEquals("pt", TrackLanguages.normalise("pt_BR"))
        assertEquals("he", TrackLanguages.normalise("iw"))
        assertEquals("ja", TrackLanguages.normalise("Japanese"))
        assertEquals("fil", TrackLanguages.normalise("fil"))
    }

    @Test
    fun `undetermined and junk are no language`() {
        for (code in listOf(null, "", " ", "und", "mul", "zxx", "x", "12", "Klingonish")) {
            assertNull(code, TrackLanguages.normalise(code))
        }
        assertFalse(TrackLanguages.same("und", "und"))
        assertFalse(TrackLanguages.same(null, null))
    }

    @Test
    fun `mpv gets every spelling a file might carry`() {
        assertEquals(listOf("de", "deu", "ger"), TrackLanguages.spellings("ger"))
        assertEquals(listOf("ja", "jpn"), TrackLanguages.spellings("ja"))
        assertEquals(emptyList<String>(), TrackLanguages.spellings(null))
    }

    @Test
    fun `stored settings read back normalised`() {
        val prefs = TrackPreferences.from("jpn", "eng", "foreign")
        assertEquals("ja", prefs.audioLanguage)
        assertEquals("en", prefs.subtitleLanguage)
        assertEquals(SubtitleMode.ForeignAudio, prefs.mode)
        val off = TrackPreferences.from(null, TrackPreferences.OFF, "nonsense")
        assertTrue(off.subtitlesOff)
        assertEquals(TrackPreferences.OFF, off.subtitleKey)
        assertEquals(SubtitleMode.AsTheVideoSays, off.mode)
    }

    // ---- ordered lists (WP-80) --------------------------------------------------------------

    @Test
    fun `an old single stored code is a list of one and is stored the same way again`() {
        val prefs = TrackPreferences.from("jpn", "eng", null)
        assertEquals(listOf("ja"), prefs.audioLanguages)
        assertEquals(listOf("en"), prefs.subtitleLanguages)
        assertEquals("ja", prefs.audioStored)
        assertEquals("en", prefs.subtitleStored)
        // Nothing stored is Automatic, and Off is stored as it was.
        assertEquals(emptyList<String>(), TrackPreferences.from(null, null, null).audioLanguages)
        assertEquals("", TrackPreferences().audioStored)
        assertEquals(TrackPreferences.OFF, TrackPreferences.from(null, TrackPreferences.OFF, null).subtitleStored)
    }

    @Test
    fun `a stored list reads back in order, each language once, garbage skipped`() {
        val prefs = TrackPreferences.from("jpn, ko,ja,,Klingonish,12,eng", "und,ml,mal", null)
        assertEquals(listOf("ja", "ko", "en"), prefs.audioLanguages)
        assertEquals("ja", prefs.audioLanguage)
        assertEquals(listOf("ko", "en"), prefs.audioMore)
        assertEquals(listOf("ml"), prefs.subtitleLanguages)
        assertEquals("ja,ko,en", prefs.audioStored)
        // A list that is all garbage, or far too long to be real, is Automatic.
        assertEquals(emptyList<String>(), TrackPreferences.from("x,12,,und", null, null).audioLanguages)
        assertEquals(emptyList<String>(), TrackPreferences.from("en,".repeat(5_000), null, null).audioLanguages)
    }

    @Test
    fun `the list is capped, never has a language twice, and add refuses a repeat`() {
        val many = (0 until 200).map { i -> "${'a' + i % 26}${'a' + (i / 26) % 26}" }
        assertTrue(LanguageOrder.clean(many).size <= LanguageOrder.MAX)
        assertEquals(listOf("ja", "en"), LanguageOrder.clean(listOf("jpn", "ja", "eng", "en-US")))
        assertEquals(listOf("ja"), LanguageOrder.add(listOf("ja"), "jpn"))
        assertEquals(listOf("ja", "en"), LanguageOrder.add(listOf("ja"), "eng"))
        assertEquals(listOf("ja"), LanguageOrder.add(listOf("ja"), "und"))
        val full = LanguageOrder.clean(many)
        assertEquals(full, LanguageOrder.add(full, "zz"))
    }

    @Test
    fun `a language moves one place at a time and stays inside the list`() {
        val list = listOf("ja", "ko", "en")
        assertEquals(listOf("ko", "ja", "en"), LanguageOrder.move(list, "ko", -1))
        assertEquals(listOf("ja", "en", "ko"), LanguageOrder.move(list, "ko", 1))
        assertEquals(list, LanguageOrder.move(list, "ja", -1))
        assertEquals(list, LanguageOrder.move(list, "en", 1))
        assertEquals(list, LanguageOrder.move(list, "de", 1))
        assertEquals(listOf("ja", "en"), LanguageOrder.remove(list, "ko"))
    }

    @Test
    fun `settings audio takes the first language and carries the rest for the player`() {
        val prefs = TrackPreferences().withAudioLanguages(listOf("ja", "ko", "ja"))
        val plan = TrackRules.plan(null, TrackChoice(), prefs, "en")
        assertEquals("ja", plan.audioLanguage)
        assertEquals(listOf("ja", "ko"), plan.audioLanguages)
        // The series' or the file's own language is a single one, whatever Settings hold.
        val series = TrackRules.plan(null, TrackChoice(audioLanguage = "kor"), prefs, "en")
        assertEquals(listOf("ko"), series.audioLanguages)
        // An empty list falls back to the file's own default.
        assertEquals(emptyList<String>(), TrackRules.plan(null, TrackChoice(), TrackPreferences(), "en").audioLanguages)
    }

    @Test
    fun `the first subtitle language the file has wins, in the viewer's order`() {
        val prefs = TrackPreferences(mode = SubtitleMode.Always).withSubtitleLanguages(listOf("ko", "en", "ml"))
        val plan = TrackRules.plan(null, TrackChoice(), prefs, "de").subtitles
        assertEquals(SubtitlePlan.On("ko", fallBack = true, more = listOf("en", "ml")), plan)
        // The file has English and Malayalam: English is the first of the list it has, though Malayalam is the default.
        assertEquals(SubtitlePick.Track(1), TrackRules.pickSubtitle(plan, null, tracks))
        // Only the later language is there: it is taken. None of them: the file's default track (always falls back).
        assertEquals(SubtitlePick.Track(0), TrackRules.pickSubtitle(plan, null, tracks.drop(2)))
        assertEquals(SubtitlePick.Track(2), TrackRules.pickSubtitle(SubtitlePlan.On("ko", fallBack = true, more = listOf("fr")), null, tracks))
        assertEquals(SubtitlePick.Leave, TrackRules.pickSubtitle(SubtitlePlan.On("ko", more = listOf("fr")), null, tracks))
    }

    @Test
    fun `foreign audio counts every language in the list as yours`() {
        val prefs = TrackPreferences(mode = SubtitleMode.ForeignAudio).withSubtitleLanguages(listOf("ml", "ta"))
        val plan = TrackRules.plan(null, TrackChoice(), prefs, "en").subtitles as SubtitlePlan.IfForeignAudio
        assertEquals(setOf("ml", "ta", "en"), plan.viewerLanguages)
        assertEquals(listOf("ml", "ta"), plan.languages)
    }

    @Test
    fun `off beats the list and a list turns off off`() {
        val off = TrackPreferences(subtitlesOff = true)
        assertEquals(TrackPreferences.OFF, off.subtitleStored)
        assertEquals("ko", off.withSubtitleLanguages(listOf("ko")).subtitleStored)
        assertFalse(off.withSubtitleLanguages(listOf("ko")).subtitlesOff)
        assertEquals(emptyList<String>(), off.subtitleLanguages)
    }

    @Test
    fun `the choices include the app languages and the common ones, once each`() {
        val choices = TrackLanguages.choices()
        for (code in listOf("en", "ja", "ko", "hi", "ml", "ta", "te", "es", "pt", "zh", "bn")) {
            assertTrue(code, code in choices)
        }
        assertEquals(choices.distinct(), choices)
    }

    // ---- precedence -------------------------------------------------------------------------

    @Test
    fun `the file's saved tracks come first`() {
        val resume = ResumeState(audioTrack = 1, audioLanguage = "eng", subtitleTrack = ResumeState.SUBTITLES_OFF)
        val series = TrackChoice(audioLanguage = "kor", textLanguage = "eng", subtitlesOn = true)
        val plan = TrackRules.plan(resume, series, japaneseAudio, "en")
        assertEquals("en", plan.audioLanguage)
        assertEquals(TrackSource.SavedFile, plan.audioSource)
        assertEquals(SubtitlePlan.Off(), plan.subtitles)
        assertEquals(TrackSource.SavedFile, plan.subtitleSource)
    }

    @Test
    fun `the series comes before settings`() {
        val series = TrackChoice(audioLanguage = "kor", textLanguage = "eng", subtitlesOn = true)
        val plan = TrackRules.plan(null, series, japaneseAudio, "en")
        assertEquals("ko", plan.audioLanguage)
        assertEquals(TrackSource.Series, plan.audioSource)
        assertEquals(SubtitlePlan.On("en"), plan.subtitles)
        assertEquals(TrackSource.Series, plan.subtitleSource)
    }

    @Test
    fun `a series watched without subtitles keeps them off`() {
        val plan = TrackRules.plan(null, TrackChoice(audioLanguage = "jpn"), japaneseAudio, "en")
        assertEquals(SubtitlePlan.Off(), plan.subtitles)
        assertEquals(TrackSource.Series, plan.subtitleSource)
    }

    @Test
    fun `settings fill what the file and the series leave open`() {
        // A resume that knew only its speed says nothing about tracks.
        val plan = TrackRules.plan(ResumeState(speed = 1.5f), TrackChoice(), japaneseAudio, "en")
        assertEquals("ja", plan.audioLanguage)
        assertEquals(TrackSource.Settings, plan.audioSource)
        assertEquals(SubtitlePlan.On("en", fallBack = true), plan.subtitles)
        assertEquals(TrackSource.Settings, plan.subtitleSource)
    }

    @Test
    fun `automatic everywhere leaves the file's defaults`() {
        val plan = TrackRules.plan(null, TrackChoice(), TrackPreferences(), "en")
        assertNull(plan.audioLanguage)
        assertEquals(TrackSource.FileDefaults, plan.audioSource)
        assertEquals(SubtitlePlan.FileDefault, plan.subtitles)
    }

    @Test
    fun `off as the subtitle language beats any mode`() {
        val prefs = TrackPreferences(subtitlesOff = true, mode = SubtitleMode.Always)
        assertEquals(SubtitlePlan.Off(), TrackRules.plan(null, TrackChoice(), prefs, "en").subtitles)
    }

    @Test
    fun `foreign audio counts the subtitle language and the app language as yours`() {
        val prefs = TrackPreferences(subtitleLanguage = "ml", mode = SubtitleMode.ForeignAudio)
        val plan = TrackRules.plan(null, TrackChoice(), prefs, "en-US")
        assertEquals(SubtitlePlan.IfForeignAudio("ml", setOf("ml", "en")), plan.subtitles)
    }

    // ---- picking a track --------------------------------------------------------------------

    private val tracks = listOf(
        SubtitleTrackInfo("eng", forced = true),
        SubtitleTrackInfo("eng"),
        SubtitleTrackInfo("mal", isDefault = true),
    )

    @Test
    fun `a full track is picked over a forced one in the same language`() {
        assertEquals(SubtitlePick.Track(1), TrackRules.pickSubtitle(SubtitlePlan.On("en"), null, tracks))
    }

    @Test
    fun `always falls back to the default track, a remembered language does not`() {
        assertEquals(SubtitlePick.Track(2), TrackRules.pickSubtitle(SubtitlePlan.On("ko", fallBack = true), null, tracks))
        assertEquals(SubtitlePick.Leave, TrackRules.pickSubtitle(SubtitlePlan.On("ko"), null, tracks))
    }

    @Test
    fun `off unless chosen lets the forced track for the soundtrack through`() {
        val plan = SubtitlePlan.Off(allowForced = true)
        assertEquals(SubtitlePick.Track(0), TrackRules.pickSubtitle(plan, "en", tracks))
        assertEquals(SubtitlePick.None, TrackRules.pickSubtitle(plan, "jpn", tracks))
        assertEquals(SubtitlePick.None, TrackRules.pickSubtitle(SubtitlePlan.Off(), "en", tracks))
    }

    @Test
    fun `foreign audio turns subtitles on only for a soundtrack in another language`() {
        val plan = SubtitlePlan.IfForeignAudio("en", setOf("en"))
        assertEquals(SubtitlePick.Track(1), TrackRules.pickSubtitle(plan, "jpn", tracks))
        assertEquals(SubtitlePick.Track(0), TrackRules.pickSubtitle(plan, "eng", tracks))
        assertEquals(SubtitlePick.None, TrackRules.pickSubtitle(plan, "eng", tracks.drop(1)))
        assertEquals(SubtitlePick.None, TrackRules.pickSubtitle(plan, null, tracks.drop(1)))
    }

    @Test
    fun `no tracks means nothing to do`() {
        assertEquals(SubtitlePick.Leave, TrackRules.pickSubtitle(SubtitlePlan.On("en", fallBack = true), "ja", emptyList()))
    }
}

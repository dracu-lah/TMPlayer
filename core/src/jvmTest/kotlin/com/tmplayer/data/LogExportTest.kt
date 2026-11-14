package com.tmplayer.data

import java.time.LocalDateTime
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class LogExportTest {

    private val plain = LogScrubber()

    @Test
    fun telegramIdsOfSixDigitsOrMoreGo() {
        assertEquals(
            "I/Downloads: Re-sourced: file [number] in chat -[number] message [number]",
            plain.scrub("I/Downloads: Re-sourced: file 12345678 in chat -1001234567890 message 4521984"),
        )
    }

    @Test
    fun shortNumbersStay() {
        val line = "10-13 14:05:01.123  4521  4580 W TdClient: error 400 after 12 tries at line 88"
        assertEquals(line, plain.scrub(line))
    }

    @Test
    fun phoneNumbersGoHoweverTheyAreWritten() {
        assertEquals("code sent to [phone]", plain.scrub("code sent to +44 7700 900123"))
        assertEquals("code sent to [phone]", plain.scrub("code sent to +91-98470-12345"))
        assertEquals("code sent to [phone].", plain.scrub("code sent to +447700900123."))
    }

    @Test
    fun usernamesGoButObjectHashesAndShortHandlesStay() {
        assertEquals("opened @[user] from a link", plain.scrub("opened @cinema_paradiso from a link"))
        assertEquals("view android.widget.FrameLayout@1a2b3c", plain.scrub("view android.widget.FrameLayout@1a2b3c"))
        assertEquals("@Test", plain.scrub("@Test"))
    }

    @Test
    fun emailAddressesGo() {
        assertEquals("from [email] today", plain.scrub("from someone.else+tv@example.co.uk today"))
    }

    @Test
    fun fileNamesGoWithTheLastPartOfAPath() {
        assertEquals(
            "W/Downloads: could not move /storage/emulated/0/Movies/TMPlayer/[file]",
            plain.scrub("W/Downloads: could not move /storage/emulated/0/Movies/TMPlayer/Kerala Crime Files S01E02 1080p.mkv"),
        )
        assertEquals("could not save [file]: no space", plain.scrub("could not save Love.Under.Construction.E03.MP4: no space"))
        assertEquals("C:\\Users\\me\\Videos\\[file]", plain.scrub("C:\\Users\\me\\Videos\\Film (2024).srt"))
    }

    @Test
    fun javaClassAndSourceFilesStay() {
        val line = "\tat com.tmplayer.data.Td.send(Td.kt:120)"
        assertEquals(line, plain.scrub(line))
    }

    @Test
    fun knownNamesGoWholeWhateverTheirCase() {
        val scrubber = LogScrubber(listOf("STRIVER TUF", "Striver", "TV", "Kerala Crime Files"))
        assertEquals(
            "I/Downloads: Not downloading [name] Episode 2: its chat [name] does not allow saving",
            scrubber.scrub("I/Downloads: Not downloading Kerala crime files Episode 2: its chat STRIVER TUF does not allow saving"),
        )
        // Too short to take out everywhere.
        assertEquals("TV menu opened", scrubber.scrub("TV menu opened"))
    }

    @Test
    fun knownNamesWithRegexCharactersAreTakenLiterally() {
        val scrubber = LogScrubber(listOf("Films (HD) [2024]"))
        assertEquals("chat [name] opened", scrubber.scrub("chat Films (HD) [2024] opened"))
    }

    @Test
    fun anEarlierRunsCrashSitsBeforeTheLinesAndIsScrubbedLikeThem() {
        val facts = Feedback.Facts("1.28.5", Feedback.Platform.Phone, "OnePlus, Android 13", "en")
        val text = LogExport.report(
            facts,
            listOf("I/Td: running"),
            known = listOf("Kerala Crime Files"),
            now = LocalDateTime.of(2026, 11, 14, 13, 57),
            extra = listOf(
                "Safe mode: on, the last two runs crashed soon after opening",
                "Last crash, from an earlier run (not in the lines below)",
                "  java.lang.IllegalStateException: no row for Kerala Crime Files",
                "    at com.tmplayer.ui.browse.HomeScreen.build(HomeScreen.kt:88)",
            ),
        )
        val at = { needle: String -> text.indexOf(needle).also { assertTrue("missing: $needle", it >= 0) } }
        assertTrue(at("Safe mode: on") < at("Last crash"))
        assertTrue(at("Last crash") < at("I/Td: running"))
        assertTrue("the frame stays readable", "HomeScreen.build(HomeScreen.kt:88)" in text)
        assertFalse("a name in the message goes", "Kerala Crime Files" in text)
    }

    @Test
    fun theReportHasTheFactsAndOnlyTheLastLines() {
        val facts = Feedback.Facts("1.24.0", Feedback.Platform.AndroidTv, "Xiaomi MiBox, Android 9", "en")
        val lines = (1..LogExport.MAX_LINES + 10).map { "line $it from chat 987654321" }
        val text = LogExport.report(facts, lines, now = LocalDateTime.of(2026, 10, 13, 14, 5))
        assertTrue(text.startsWith("TMPlayer logs\nApp version: 1.24.0\nPlatform: Android TV or Google TV\n"))
        assertTrue("Device: Xiaomi MiBox, Android 9" in text)
        assertTrue("Saved: 2026-10-13 14:05" in text)
        assertFalse("line 10 from" in text)
        assertTrue("line 11 from chat [number]" in text)
        assertFalse("987654321" in text)
    }

    @Test
    fun fileNameCarriesTheMinute() {
        assertEquals("tmplayer-logs-20261013-1405.txt", LogExport.fileName(LocalDateTime.of(2026, 10, 13, 14, 5)))
    }

    @Test
    fun uiScaleStepsAndReadsBack() {
        assertEquals(UiScale.Larger, UiScale.Default.step(1))
        assertEquals(UiScale.Largest, UiScale.Largest.step(1))
        assertEquals(UiScale.Smaller, UiScale.Smaller.step(-1))
        assertEquals(UiScale.Largest, UiScale.from("Largest"))
        assertEquals(UiScale.Default, UiScale.from(null))
        assertEquals(UiScale.Default, UiScale.from("Huge"))
    }
}

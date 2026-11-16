package com.tmplayer.online

import com.tmplayer.player.Chapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipPolicyTest {

    private val minute = 60_000L
    private val duration = 24 * minute

    private fun seg(kind: SkipKind, start: Long, end: Long?, source: SkipSource = SkipSource.Online) = SkipSegment(kind, start, end, source)

    @After
    fun forget() = UserSkipMarks.reset()

    // ---- merge ----------------------------------------------------------------------------

    @Test
    fun `chapters win over the databases and the databases over a guess`() {
        val chapters = listOf(seg(SkipKind.Opening, 90_000, 180_000, SkipSource.Chapter))
        val online = listOf(seg(SkipKind.Opening, 95_000, 185_000), seg(SkipKind.Ending, 22 * minute, null))
        val guess = listOf(seg(SkipKind.Intro, 100_000, 190_000, SkipSource.Guess))
        val merged = SkipMerge.merge(chapters + online + guess, duration)
        assertEquals(
            listOf(seg(SkipKind.Opening, 90_000, 180_000, SkipSource.Chapter), seg(SkipKind.Ending, 22 * minute, null)),
            merged,
        )
    }

    @Test
    fun `the viewer's own mark beats the chapters and a guess fills only an empty place`() {
        val own = seg(SkipKind.Intro, 40_000, 120_000, SkipSource.Mark)
        val chapter = seg(SkipKind.Opening, 90_000, 180_000, SkipSource.Chapter)
        assertEquals(listOf(own), SkipMerge.merge(listOf(chapter, own), duration))
        val guess = seg(SkipKind.Intro, 60_000, 150_000, SkipSource.Guess)
        // No other intro anywhere: the guess stands.
        assertEquals(listOf(guess), SkipMerge.merge(listOf(guess), duration))
        // A database's intro far from the guess still wins: the guess is the last word.
        val online = seg(SkipKind.Opening, 300_000, 390_000)
        assertEquals(listOf(online), SkipMerge.merge(listOf(guess, online), duration))
    }

    @Test
    fun `segments out of range or impossible are dropped`() {
        val wrong = listOf(
            seg(SkipKind.Intro, -1, 50_000), // starts before zero
            seg(SkipKind.Intro, 50_000, 50_000), // ends where it starts
            seg(SkipKind.Intro, 60_000, 10_000), // ends before it starts
            seg(SkipKind.Recap, duration, duration + 5_000), // starts at the end
            seg(SkipKind.Preview, 10 * minute, duration + 60_000), // runs a minute past the end
            seg(SkipKind.Credits, 30 * minute, null), // starts past the end
            seg(SkipKind.Intro, 0, 20 * minute), // most of the file
        )
        assertEquals(emptyList<SkipSegment>(), SkipMerge.merge(wrong, duration))
    }

    @Test
    fun `an end a little past the file is pulled back, and credits with no end stay open`() {
        val merged = SkipMerge.merge(listOf(seg(SkipKind.Ending, 22 * minute, duration + 1_500), seg(SkipKind.Recap, 0, 40_000)), duration)
        assertEquals(listOf(seg(SkipKind.Recap, 0, 40_000), seg(SkipKind.Ending, 22 * minute, duration)), merged)
        assertEquals(listOf(seg(SkipKind.Credits, 22 * minute, null)), SkipMerge.merge(listOf(seg(SkipKind.Credits, 22 * minute, null)), duration))
    }

    @Test
    fun `overlaps inside one source are dropped and neighbours stay`() {
        val online = listOf(
            seg(SkipKind.Intro, 0, 60_000),
            seg(SkipKind.Intro, 30_000, 90_000), // overlaps the first
            seg(SkipKind.Credits, 20 * minute, 21 * minute),
            seg(SkipKind.Credits, 21 * minute, 23 * minute), // touches, does not overlap
        )
        assertEquals(
            listOf(seg(SkipKind.Intro, 0, 60_000), seg(SkipKind.Credits, 20 * minute, 21 * minute), seg(SkipKind.Credits, 21 * minute, 23 * minute)),
            SkipMerge.merge(online, duration),
        )
    }

    @Test
    fun `with an unknown length nothing is cut against the end`() {
        val merged = SkipMerge.merge(listOf(seg(SkipKind.Intro, 5_000, 95_000), seg(SkipKind.Credits, 3_000_000, null)), 0)
        assertEquals(2, merged.size)
    }

    // ---- modes ----------------------------------------------------------------------------

    @Test
    fun `modes round trip and a bad line is just the default`() {
        val modes = SkipModes().with(SkipGroup.Intro, SkipMode.Auto).with(SkipGroup.Recap, SkipMode.Off)
        assertEquals("Recap=Off,Intro=Auto", modes.encode())
        assertEquals(modes, SkipModes.decode(modes.encode()))
        assertEquals(SkipMode.Ask, modes.of(SkipGroup.Credits))
        assertEquals(SkipMode.Auto, modes.of(SkipKind.Opening))
        assertEquals("", modes.with(SkipGroup.Intro, SkipMode.Ask).with(SkipGroup.Recap, SkipMode.Ask).encode())
        for (bad in listOf(null, "", "x", "=", "Intro=", "Intro=Maybe", "Nope=Off", ",,,", "Intro=Auto=Off", "a".repeat(5_000), "Intro=Auto,Intro=Off")) {
            SkipModes.decode(bad)
        }
        assertEquals(SkipMode.Off, SkipModes.decode("Bogus=Auto, Credits = Off").of(SkipGroup.Credits))
    }

    // ---- the session ----------------------------------------------------------------------

    private fun session(modes: SkipModes = SkipModes()) = SkipSession(modes).apply {
        online(listOf(seg(SkipKind.Opening, 60_000, 150_000), seg(SkipKind.Recap, 0, 30_000)))
    }

    @Test
    fun `ask mode offers the stretch the playhead is in and nothing outside`() {
        val s = session()
        assertEquals(SkipOffer.Ask(seg(SkipKind.Recap, 0, 30_000)), s.offer(5_000, duration, true, 0))
        assertNull(s.offer(45_000, duration, true, 0))
        assertEquals(SkipOffer.Ask(seg(SkipKind.Opening, 60_000, 150_000)), s.offer(61_000, duration, true, 0))
        // The last second of a stretch is not offered, and a stretch skipped before does not come back.
        assertNull(s.offer(149_500, duration, true, 0))
        assertNull(s.offer(61_000, duration, true, 0, skipped = setOf(60_000L)))
        assertNull(s.offer(61_000, 0, true, 0))
    }

    @Test
    fun `off hides a group and the others still show`() {
        val s = session(SkipModes().with(SkipGroup.Recap, SkipMode.Off))
        assertNull(s.offer(5_000, duration, true, 0))
        assertTrue(s.offer(61_000, duration, true, 0) is SkipOffer.Ask)
    }

    @Test
    fun `by itself counts three seconds of playing, then fires`() {
        val s = session(SkipModes().with(SkipGroup.Intro, SkipMode.Auto))
        val at = 61_000L
        assertEquals(3, (s.offer(at, duration, true, 10_000) as SkipOffer.Counting).secondsLeft)
        assertEquals(3, (s.offer(at, duration, true, 10_400) as SkipOffer.Counting).secondsLeft)
        assertEquals(2, (s.offer(at, duration, true, 11_400) as SkipOffer.Counting).secondsLeft)
        // A pause holds the count however long it lasts.
        assertEquals(2, (s.offer(at, duration, false, 60_000) as SkipOffer.Counting).secondsLeft)
        assertEquals(2, (s.offer(at, duration, true, 60_400) as SkipOffer.Counting).secondsLeft)
        assertEquals(1, (s.offer(at, duration, true, 61_400) as SkipOffer.Counting).secondsLeft)
        assertTrue(s.offer(at, duration, true, 62_400) is SkipOffer.Fire)
    }

    @Test
    fun `leaving the stretch resets the count and Cancel turns it into a plain button`() {
        val s = session(SkipModes().with(SkipGroup.Intro, SkipMode.Auto))
        s.offer(61_000, duration, true, 0)
        s.offer(61_000, duration, true, 1_000)
        assertNull(s.offer(200_000, duration, true, 1_200))
        assertEquals(3, (s.offer(61_000, duration, true, 5_000) as SkipOffer.Counting).secondsLeft)
        val opening = seg(SkipKind.Opening, 60_000, 150_000)
        s.cancel(opening)
        assertEquals(SkipOffer.Ask(opening), s.offer(61_000, duration, true, 6_000))
        assertEquals(SkipOffer.Ask(opening), s.offer(61_000, duration, true, 60_000))
    }

    @Test
    fun `a long gap between ticks is not three seconds watched`() {
        val s = session(SkipModes().with(SkipGroup.Intro, SkipMode.Auto))
        s.offer(61_000, duration, true, 0)
        assertTrue(s.offer(61_000, duration, true, 600_000) is SkipOffer.Counting)
    }

    @Test
    fun `the session merges what arrives in any order`() {
        val s = SkipSession()
        s.online(listOf(seg(SkipKind.Opening, 200_000, 290_000)))
        assertEquals(SkipOffer.Ask(seg(SkipKind.Opening, 200_000, 290_000)), s.offer(210_000, duration, true, 0))
        s.chapters(listOf(Chapter(60_000, "Opening"), Chapter(150_000, "Story")))
        assertEquals(seg(SkipKind.Opening, 60_000, 150_000, SkipSource.Chapter), s.offer(70_000, duration, true, 0)?.segment)
        assertNull(s.offer(210_000, duration, true, 0))
        assertEquals(1, s.segments(duration).size)
    }

    // ---- the viewer's marks ---------------------------------------------------------------

    @Test
    fun `two taps make a mark, a quick second tap does not`() {
        val marking = IntroMarking()
        assertEquals(MarkStep.Started(61_000), marking.tap(61_000))
        assertEquals(MarkStep.TooShort, marking.tap(62_000))
        assertTrue(marking.started)
        assertEquals(MarkStep.Done(SkipMark(61_000, 150_000)), marking.tap(150_000))
        assertFalse(marking.started)
        // Seeking back before the start begins again; a second tap ages out after ten minutes.
        marking.tap(100_000)
        assertEquals(MarkStep.Started(40_000), marking.tap(40_000))
        assertEquals(MarkStep.Restarted(40_000 + 11 * minute), marking.tap(40_000 + 11 * minute))
        marking.cancel()
        assertFalse(marking.started)
    }

    @Test
    fun `a mark is the viewer's word here and a guess for the season`() {
        val key = UserSkipMarks.seasonKey(7L, "some show", 2)
        assertTrue(UserSkipMarks.save("7:1", key, SkipMark(61_000, 150_000)))
        assertEquals(listOf(seg(SkipKind.Intro, 61_000, 150_000, SkipSource.Mark)), UserSkipMarks.segmentsFor("7:1", key, duration))
        assertEquals(listOf(seg(SkipKind.Intro, 61_000, 150_000, SkipSource.Guess)), UserSkipMarks.segmentsFor("7:2", key, duration))
        // Another season, another chat and another show get nothing; a short file the guess would run past too.
        assertEquals(emptyList<SkipSegment>(), UserSkipMarks.segmentsFor("7:2", UserSkipMarks.seasonKey(7L, "some show", 3), duration))
        assertEquals(emptyList<SkipSegment>(), UserSkipMarks.segmentsFor("8:2", UserSkipMarks.seasonKey(8L, "some show", 2), duration))
        assertEquals(emptyList<SkipSegment>(), UserSkipMarks.segmentsFor("7:2", key, 100_000))
        // A newer mark replaces the season's guess; the episode keeps its own.
        UserSkipMarks.save("7:3", key, SkipMark(30_000, 110_000))
        assertEquals(30_000L, UserSkipMarks.segmentsFor("7:2", key, duration).single().startMs)
        assertEquals(61_000L, UserSkipMarks.segmentsFor("7:1", key, duration).single().startMs)
        UserSkipMarks.forget("7:3", key)
        assertEquals(emptyList<SkipSegment>(), UserSkipMarks.segmentsFor("7:2", key, duration))
        assertFalse(UserSkipMarks.hasOwn("7:3"))
        assertNull(UserSkipMarks.seasonKey(7L, " ", 1))
    }

    @Test
    fun `an unusable mark is not saved`() {
        assertFalse(UserSkipMarks.save("1:1", null, SkipMark(10, 11)))
        assertFalse(UserSkipMarks.save("1:1", null, SkipMark(-5, 90_000)))
        assertFalse(UserSkipMarks.save("1:1", null, SkipMark(0, 11 * minute)))
        assertFalse(UserSkipMarks.save(" ", null, SkipMark(0, 60_000)))
    }

    private class Memory(var text: String? = null) : UserSkipMarks.Persistence {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    @Test
    fun `marks survive a restart and a damaged file loses only the damaged entries`() {
        val memory = Memory()
        UserSkipMarks.open(memory)
        UserSkipMarks.save("9:1", "k", SkipMark(61_000, 150_000))
        val saved = memory.text!!
        UserSkipMarks.reset()
        assertEquals(emptyList<SkipSegment>(), UserSkipMarks.segmentsFor("9:1", "k", duration))
        UserSkipMarks.open(Memory(saved))
        assertEquals(SkipSource.Mark, UserSkipMarks.segmentsFor("9:1", "k", duration).single().source)
        UserSkipMarks.reset()
        for (bad in listOf("", "{", "[]", "null", "{\"own\":5}", "{\"own\":[null,1,{\"key\":3},{\"key\":\"a\",\"start\":\"x\"},{\"key\":\"b\",\"start\":0,\"end\":1}]}", "x".repeat(10_000))) {
            UserSkipMarks.open(Memory(bad))
            UserSkipMarks.segmentsFor("a", "k", duration)
        }
        UserSkipMarks.open(Memory("{\"own\":[{\"key\":\"ok\",\"start\":1000,\"end\":90000},{\"key\":\"bad\",\"start\":5,\"end\":6}]}"))
        assertTrue(UserSkipMarks.hasOwn("ok"))
        assertFalse(UserSkipMarks.hasOwn("bad"))
    }

    @Test
    fun `the lists are bounded`() {
        UserSkipMarks.open(Memory())
        repeat(UserSkipMarks.OWN_LIMIT + 50) { UserSkipMarks.save("c:$it", "s$it", SkipMark(1_000, 60_000)) }
        assertFalse(UserSkipMarks.hasOwn("c:0"))
        assertTrue(UserSkipMarks.hasOwn("c:${UserSkipMarks.OWN_LIMIT + 49}"))
    }
}

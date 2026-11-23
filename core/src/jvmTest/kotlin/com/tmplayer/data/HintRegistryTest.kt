package com.tmplayer.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The one-time hint registry (WP-68): the three old flags, counted hints, garbage and the tour rule. */
class HintRegistryTest {

    private val file = Files.createTempDirectory("tm-hints").resolve(SettingsStore.FILE_NAME).toFile()
    private val data = SettingsStore.openDataStore(file)
    private val settings = SettingsStore(data)

    private fun setOld(name: String) = runBlocking { data.edit { it[booleanPreferencesKey(name)] = true } }

    @Test
    fun `a fresh install has seen nothing`() = runBlocking {
        assertTrue(settings.hintsSeen.first().isEmpty())
        assertFalse(settings.playerHintSeenNow())
        assertFalse(settings.tvBrowseHintSeenNow())
        assertFalse(settings.externalHintSeenNow())
        assertTrue(settings.hintState.first().firstRun)
    }

    @Test
    fun `the old player flag reads as seen`() = runBlocking {
        setOld("player_hint_seen")
        assertTrue(settings.playerHintSeenNow())
        assertFalse(settings.tvBrowseHintSeenNow())
        assertEquals(setOf("player"), settings.hintsSeen.first())
    }

    @Test
    fun `the old tv browse flag reads as seen`() = runBlocking {
        setOld("tv_browse_hint_seen")
        assertTrue(settings.tvBrowseHintSeenNow())
        assertFalse(settings.playerHintSeenNow())
        assertEquals(setOf("tv_browse"), settings.hintsSeen.first())
    }

    @Test
    fun `the old external flag reads as seen`() = runBlocking {
        setOld("external_hint_seen")
        assertTrue(settings.externalHintSeenNow())
        assertEquals(setOf("external"), settings.hintsSeen.first())
    }

    @Test
    fun `the wrappers mark through the registry`() = runBlocking {
        settings.markPlayerHintSeen()
        settings.markTvBrowseHintSeen()
        settings.markExternalHintSeen()
        assertEquals(setOf("player", "tv_browse", "external"), settings.hintsSeen.first())
        assertTrue(settings.playerHintSeenNow() && settings.tvBrowseHintSeenNow() && settings.externalHintSeenNow())
    }

    @Test
    fun `a hint marked twice stays at one and another id is untouched`() = runBlocking {
        settings.markHintSeen("tile_options")
        settings.markHintSeen("tile_options")
        assertEquals(1, settings.hintCount("tile_options"))
        assertEquals(0, settings.hintCount("other"))
    }

    @Test
    fun `a counted hint counts up and then stops being allowed`() = runBlocking {
        assertEquals(1, settings.bumpHint("tile_options"))
        assertEquals(2, settings.bumpHint("tile_options"))
        assertEquals(2, settings.hintCount("tile_options"))
        val state = settings.hintState.first()
        assertTrue(state.count("tile_options") < 3)
        assertFalse(state.count("tile_options") < 2)
    }

    @Test
    fun `a device fact is recorded like a hint`() = runBlocking {
        assertFalse(settings.hintSeenNow(HintIds.PAGE_KEY_ARRIVED))
        settings.markHintSeen(HintIds.PAGE_KEY_ARRIVED)
        assertTrue(settings.hintSeenNow(HintIds.PAGE_KEY_ARRIVED))
    }

    @Test
    fun `two skips of the tour switch every hint off`() = runBlocking {
        assertFalse(settings.hintsOff.first())
        settings.markTourSkipped()
        assertFalse(settings.hintsOff.first())
        assertEquals(SettingsStore.TourState.Done, settings.tourState.first())
        settings.markTourSkipped()
        assertTrue(settings.hintsOff.first())
    }

    @Test
    fun `a skip, a sign out and a second skip silence the hints`() = runBlocking {
        // The real path: the Tips card is turned down, the person signs out (the tour is offered
        // again on the next sign in), and skips again. The count lives past the sign out.
        settings.markTourSkipped()
        settings.markHintSeen("tile_options")
        settings.clearEverything()
        assertEquals(SettingsStore.TourState.Never, settings.tourState.first())
        assertEquals("only the skip count survives", 0, settings.hintCount("tile_options"))
        assertEquals(1, settings.hintCount(HintIds.TOUR_SKIPPED))
        assertFalse(settings.hintsOff.first())
        settings.markTourSkipped()
        assertTrue(settings.hintsOff.first())
        assertEquals(SettingsStore.TourState.Done, settings.tourState.first())
    }

    @Test
    fun `a skip marks the tour done and counts in one write`() = runBlocking {
        settings.openTour()
        settings.markTourSkipped()
        assertEquals(SettingsStore.TourState.Done, settings.tourState.first())
        assertEquals(1, settings.hintCount(HintIds.TOUR_SKIPPED))
    }

    @Test
    fun `garbage in the stored value keeps what is good`() = runBlocking {
        data.edit { it[stringPreferencesKey("hints_seen")] = "a=2,,=3,b=x,c=0,BAD ID=1,d=99999999999,e" + "=1,".repeat(3) }
        val state = settings.hintState.first()
        assertEquals(2, state.count("a"))
        assertEquals(0, state.count("b"))
        assertEquals(0, state.count("c"))
        assertEquals(0, state.count("d"))
        assertEquals(1, state.count("e"))
    }

    @Test
    fun `a huge string is capped and unknown ids are kept`() {
        val huge = (1..5_000).joinToString(",") { "id$it=1" }
        val state = HintRegistry.parse(huge)
        assertTrue(state.counts.size <= HintRegistry.MAX_IDS)
        assertTrue(HintRegistry.parse("future_hint=3").seen("future_hint"))
        val grown = (1..500).fold(HintState()) { s, i -> HintRegistry.raise(s, "h$i") }
        assertEquals(HintRegistry.MAX_IDS, grown.counts.size)
        assertTrue(grown.seen("h500"))
        assertFalse(grown.seen("h1"))
    }

    @Test
    fun `a value that is not a valid id is ignored`() {
        val state = HintRegistry.raise(HintState(), "Has Space")
        assertTrue(state.counts.isEmpty())
        assertEquals(HintRegistry.MAX_COUNT, HintRegistry.raise(HintState(), "a", Int.MAX_VALUE).count("a"))
    }

    @Test
    fun `the stored value round trips`() {
        val state = HintRegistry.bump(HintRegistry.raise(HintState(), "a"), "b")
        assertEquals(state, HintRegistry.parse(HintRegistry.encode(state)))
    }

    @Test
    fun `hints are cleared with everything on sign out`() = runBlocking {
        settings.markHintSeen("a")
        settings.clearEverything()
        assertFalse(settings.hintSeenNow("a"))
    }
}

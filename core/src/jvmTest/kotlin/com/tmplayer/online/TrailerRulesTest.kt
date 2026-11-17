package com.tmplayer.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a trailer plays in TMPlayer and when it opens outside (private/TRAILERS-PLAN.md): only
 * with the setting on, a real key, a network and a web view; every YouTube error and a player
 * not ready within 12 s fall back; a failed trailer stays failed.
 */
class TrailerRulesTest {

    private val key = "YE7VzlLtp-4"

    @Test
    fun inTheAppOnlyWithTheSettingOnAValidKeyANetworkAndAWebView() {
        assertEquals(TrailerRoute.InApp, TrailerRules.route(enabled = true, key = key, online = true, canEmbed = true))
        assertEquals(TrailerRoute.External, TrailerRules.route(enabled = false, key = key, online = true, canEmbed = true))
        assertEquals(TrailerRoute.External, TrailerRules.route(enabled = true, key = key, online = false, canEmbed = true))
        assertEquals(TrailerRoute.External, TrailerRules.route(enabled = true, key = key, online = true, canEmbed = false))
        for (bad in listOf(null, "", "promoharbour", "x\"><y>", "١٢٣٤٥٦٧٨٩٠١")) {
            assertEquals(TrailerRoute.External, TrailerRules.route(enabled = true, key = bad, online = true, canEmbed = true), bad.toString())
        }
    }

    @Test
    fun theSettingIsOnByDefaultAndKeptOnDisk() {
        assertTrue(MetaSettings().trailersInApp)
        val dir = kotlin.io.path.createTempDirectory("tm-trailers").toFile()
        val file = java.io.File(dir, "metadata.properties")
        // A file from before the setting, and one cut short, read as on.
        file.writeText("enabled=true\nskip_times=false\n")
        assertTrue(MetadataStore(file).now.trailersInApp)
        file.writeText("trailers_in_app=fal")
        assertTrue(MetadataStore(file).now.trailersInApp)
        MetadataStore(file).update { it.copy(trailersInApp = false) }
        assertFalse(MetadataStore(file).now.trailersInApp)
        dir.deleteRecursively()
    }

    @Test
    fun everyYouTubeErrorFallsBackWithItsReason() {
        assertEquals(setOf(2, 5, 100, 101, 150, 153), TrailerRules.FALLBACK_ERRORS)
        val expected = mapOf(
            2 to TrailerFailure.Broken,
            5 to TrailerFailure.Broken,
            100 to TrailerFailure.NotFound,
            101 to TrailerFailure.NotEmbeddable,
            150 to TrailerFailure.NotEmbeddable,
            153 to TrailerFailure.NotEmbeddable,
        )
        for ((code, failure) in expected) {
            val after = TrailerRules.next(TrailerState(TrailerPhase.Playing), TrailerMessage.Error(code))
            assertTrue(after.fallsBack, "error $code")
            assertEquals(failure, after.failure, "error $code")
        }
        // A code YouTube adds later, and its script not loading, fall back too.
        assertEquals(TrailerFailure.Broken, TrailerRules.next(TrailerState(), TrailerMessage.Error(999)).failure)
        assertEquals(TrailerFailure.NotEmbeddable, TrailerRules.failure(152))
        assertEquals(TrailerFailure.Unreachable, TrailerRules.next(TrailerState(), TrailerMessage.Error(-1)).failure)
    }

    @Test
    fun noReadyWithinTwelveSecondsFallsBackAndReadyInTimeDoesNot() {
        assertEquals(12_000L, TrailerRules.READY_TIMEOUT_MS)
        val waiting = TrailerState()
        assertEquals(TrailerPhase.Loading, waiting.phase)
        assertFalse(waiting.controllable)
        val late = TrailerRules.timedOut(waiting)
        assertTrue(late.fallsBack)
        assertEquals(TrailerFailure.TimedOut, late.failure)
        val ready = TrailerRules.next(waiting, TrailerMessage.Ready)
        assertEquals(TrailerPhase.Ready, ready.phase)
        assertTrue(ready.controllable)
        assertEquals(ready, TrailerRules.timedOut(ready))
        // Playing before the ready message arrived counts as ready.
        val playing = TrailerRules.next(waiting, TrailerMessage.State(1))
        assertEquals(TrailerPhase.Playing, playing.phase)
        assertEquals(playing, TrailerRules.timedOut(playing))
    }

    @Test
    fun thePlayersStatesAreFollowed() {
        var state = TrailerRules.next(TrailerState(), TrailerMessage.Ready)
        val seen = listOf(3, 1, 2, 1, 0, 5, -1).map { code ->
            state = TrailerRules.next(state, TrailerMessage.State(code))
            state.phase
        }
        assertEquals(
            listOf(TrailerPhase.Buffering, TrailerPhase.Playing, TrailerPhase.Paused, TrailerPhase.Playing, TrailerPhase.Ended, TrailerPhase.Ready, TrailerPhase.Ready),
            seen,
        )
        // Unstarted and cued before the player is ready leave it loading.
        assertEquals(TrailerPhase.Loading, TrailerRules.next(TrailerState(), TrailerMessage.State(5)).phase)
        assertEquals(TrailerPhase.Loading, TrailerRules.next(TrailerState(), TrailerMessage.State(-1)).phase)
    }

    @Test
    fun aFailedTrailerStaysFailedAndAnUnreadableMessageChangesNothing() {
        val failed = TrailerRules.next(TrailerState(TrailerPhase.Playing), TrailerMessage.Error(150))
        assertEquals(failed, TrailerRules.next(failed, TrailerMessage.Ready))
        assertEquals(failed, TrailerRules.next(failed, TrailerMessage.State(1)))
        assertEquals(failed, TrailerRules.next(failed, TrailerMessage.Error(2)))
        assertEquals(failed, TrailerRules.timedOut(failed))
        val playing = TrailerState(TrailerPhase.Playing)
        assertEquals(playing, TrailerRules.next(playing, TrailerEmbed.parseMessage("rubbish")))
        assertNull(TrailerState().failure)
    }
}

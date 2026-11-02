package com.tmplayer.player

import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** HX74: the session hears state changes only, not each chunk's "is loading" flip. */
@RunWith(RobolectricTestRunner::class)
class StateChangeOnlyPlayerTest {
    private fun events(vararg flags: Int): Player.Events {
        val set = FlagSet.Builder()
        flags.forEach { set.add(it) }
        return Player.Events(set.build())
    }

    @Test
    fun loadingAloneIsDropped() {
        assertNull(StateChangeOnlyPlayer.withoutNoise(events(Player.EVENT_IS_LOADING_CHANGED)))
    }

    @Test
    fun loadingNextToAStateChangeKeepsTheStateChange() {
        val kept = StateChangeOnlyPlayer.withoutNoise(
            events(Player.EVENT_IS_LOADING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED),
        )
        assertNotNull(kept)
        assertEquals(1, kept!!.size())
        assertEquals(Player.EVENT_PLAYBACK_STATE_CHANGED, kept.get(0))
    }

    @Test
    fun stateChangesPassUntouched() {
        val e = events(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
        assertSame(e, StateChangeOnlyPlayer.withoutNoise(e))
    }
}

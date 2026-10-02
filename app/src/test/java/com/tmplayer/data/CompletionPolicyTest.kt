package com.tmplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionPolicyTest {
    @Test fun `95 percent is complete`() {
        assertFalse(CompletionPolicy.isComplete(94_999, 100_000))
        assertTrue(CompletionPolicy.isComplete(95_000, 100_000))
    }

    @Test fun `unknown duration is never complete`() {
        assertFalse(CompletionPolicy.isComplete(100_000, 0, endedNormally = true))
    }

    @Test fun `playback error does not complete a video`() {
        assertFalse(CompletionPolicy.isComplete(99_000, 100_000, playbackError = true))
    }

    @Test fun `normal end completes a video with a valid duration`() {
        assertTrue(CompletionPolicy.isComplete(80_000, 100_000, endedNormally = true))
    }

    @Test fun `explicitly unwatched legacy progress is not recovered`() {
        assertFalse(CompletionPolicy.canRecoverLegacy(99_000, 100_000, explicitlyUnwatched = true))
        assertTrue(CompletionPolicy.canRecoverLegacy(99_000, 100_000, explicitlyUnwatched = false))
    }
}

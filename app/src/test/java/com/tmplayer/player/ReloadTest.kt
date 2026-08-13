package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The one decision on the failure sheet that can destroy something, so it is the one worth pinning
 * down: which videos Reload is allowed to throw away.
 */
class ReloadTest {

    @Test
    fun `a streamed video is cleared and fetched again`() {
        assertEquals(
            Reload.Plan.StartOver,
            Reload.plan(fileId = 7, keptForOffline = false, queuedForOffline = false),
        )
    }

    @Test
    fun `a video downloaded on purpose is never thrown away`() {
        assertEquals(
            Reload.Plan.PlainRetry,
            Reload.plan(fileId = 7, keptForOffline = true, queuedForOffline = false),
        )
    }

    /** The service owns those bytes, and it is still writing into them. */
    @Test
    fun `a video the download service is working on is left alone`() {
        assertEquals(
            Reload.Plan.PlainRetry,
            Reload.plan(fileId = 7, keptForOffline = false, queuedForOffline = true),
        )
    }

    @Test
    fun `a video with no file id has nothing to clear`() {
        assertEquals(
            Reload.Plan.PlainRetry,
            Reload.plan(fileId = 0, keptForOffline = false, queuedForOffline = false),
        )
    }

    @Test
    fun `the two plans do not read the same on the button`() {
        assertNotEquals(Reload.label(Reload.Plan.StartOver), Reload.label(Reload.Plan.PlainRetry))
        assertNotEquals(Reload.status(Reload.Plan.StartOver), Reload.status(Reload.Plan.PlainRetry))
    }
}

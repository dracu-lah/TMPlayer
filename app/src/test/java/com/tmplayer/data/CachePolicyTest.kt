package com.tmplayer.data

import com.tmplayer.data.CachePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GB = 1024L * 1024 * 1024
private const val MB = 1024L * 1024

class CachePolicyTest {

    @Test
    fun `re-watching the cached film never clears anything`() {
        // The film on disk IS the cache; clearing would delete the thing being opened.
        val decision = CachePolicy.decide(
            fileSizeBytes = 4 * GB,
            alreadyCached = true,
            cacheBytes = 4 * GB,
            freeBytes = 100 * MB,
        )
        assertEquals(Decision.Proceed, decision)
    }

    @Test
    fun `an empty cache just plays`() {
        val decision = CachePolicy.decide(
            fileSizeBytes = 1 * GB,
            alreadyCached = false,
            cacheBytes = 0,
            freeBytes = 5 * GB,
        )
        assertEquals(Decision.Proceed, decision)
    }

    @Test
    fun `a previous film is offered up for deletion`() {
        val decision = CachePolicy.decide(
            fileSizeBytes = 1 * GB,
            alreadyCached = false,
            cacheBytes = 2 * GB,
            freeBytes = 5 * GB,
        )
        assertEquals(Decision.FreeUp(2 * GB), decision)
    }

    @Test
    fun `scraps below the threshold are not worth a prompt`() {
        val decision = CachePolicy.decide(
            fileSizeBytes = 1 * GB,
            alreadyCached = false,
            cacheBytes = 10 * MB,
            freeBytes = 5 * GB,
        )
        assertEquals(Decision.Proceed, decision)
    }

    @Test
    fun `a film larger than the whole device reports the shortfall`() {
        // 1 GB free plus 1 GB reclaimable is 2 GB; a 4 GB film needs 4 GB plus headroom.
        val decision = CachePolicy.decide(
            fileSizeBytes = 4 * GB,
            alreadyCached = false,
            cacheBytes = 1 * GB,
            freeBytes = 1 * GB,
        )
        assertTrue(decision is Decision.TooLarge)
        val tooLarge = decision as Decision.TooLarge
        assertEquals(4 * GB + CachePolicy.HEADROOM_BYTES - 2 * GB, tooLarge.shortfallBytes)
        assertEquals(1 * GB, tooLarge.reclaimBytes)
    }

    @Test
    fun `clearing the cache is enough to make a tight film fit`() {
        // Only 500 MB free, but 3 GB of old film to reclaim: it fits once that goes.
        val decision = CachePolicy.decide(
            fileSizeBytes = 2 * GB,
            alreadyCached = false,
            cacheBytes = 3 * GB,
            freeBytes = 500 * MB,
        )
        assertEquals(Decision.FreeUp(3 * GB), decision)
    }

    @Test
    fun `headroom is respected, not just raw size`() {
        // Exactly the film's size free and nothing to reclaim leaves no room for the database.
        val decision = CachePolicy.decide(
            fileSizeBytes = 1 * GB,
            alreadyCached = false,
            cacheBytes = 0,
            freeBytes = 1 * GB,
        )
        assertTrue(decision is Decision.TooLarge)
    }

    @Test
    fun `an unknown file size does not block playback`() {
        // Some documents arrive with no size; refusing to play them would be worse than trying.
        val decision = CachePolicy.decide(
            fileSizeBytes = 0,
            alreadyCached = false,
            cacheBytes = 0,
            freeBytes = 10 * MB,
        )
        assertEquals(Decision.Proceed, decision)
    }
}

package com.tmplayer.data

import com.tmplayer.data.CacheShelf.Held
import com.tmplayer.data.CacheShelf.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GB = 1024L * 1024 * 1024
private const val MB = 1024L * 1024

/** The video being opened, which is never in the cache unless a test puts it there. */
private const val TARGET = 1

/** The one film the last press of Play left behind. */
private fun cache(fileId: Int, bytes: Long): List<Held> = listOf(Held(fileId, bytes))

class CacheShelfTest {

    @Test
    fun `re-watching the video already here never deletes anything`() {
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 4 * GB,
            targetPartialBytes = 0,
            alreadyCached = true,
            cached = cache(TARGET, 4 * GB),
            freeBytes = 100 * MB,
        )
        assertEquals(Plan.Proceed, plan)
    }

    @Test
    fun `an empty cache just plays`() {
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 1 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            cached = emptyList(),
            freeBytes = 5 * GB,
        )
        assertEquals(Plan.Proceed, plan)
    }

    @Test
    fun `the previous film goes even when there is room for both`() {
        // The whole of the rule: one cached video on every device, phone and television alike.
        // Room on the disk is not a reason to keep a copy of something nobody asked to keep.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 1 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            cached = cache(2, 2 * GB),
            freeBytes = 50 * GB,
        )
        assertEquals(Plan.Evict(listOf(2), 2 * GB), plan)
    }

    @Test
    fun `what the viewer downloaded is never offered up`() {
        // The downloads are quoted, not evicted: 1 GB free and a 4 GB film is a refusal, however
        // many gigabytes of the viewer's own downloads are sitting beside it.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 4 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            cached = emptyList(),
            keptBytes = 6 * GB,
            freeBytes = 1 * GB,
        )
        val short = plan as? Plan.NotEnoughSpace
        assertTrue("expected NotEnoughSpace, got $plan", short != null)
        assertEquals(6 * GB, short!!.reclaimBytes)
    }

    @Test
    fun `a video larger than the whole device reports the shortfall`() {
        // 1 GB free plus 1 GB of cache is 2 GB; a 4 GB video needs 4 GB plus headroom.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 4 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            cached = cache(2, 1 * GB),
            freeBytes = 1 * GB,
        )
        val short = plan as? Plan.NotEnoughSpace
        assertTrue("expected NotEnoughSpace, got $plan", short != null)
        assertEquals(4 * GB + CacheShelf.HEADROOM_BYTES - 2 * GB, short!!.shortfallBytes)
    }

    @Test
    fun `nothing is deleted for a play that cannot happen anyway`() {
        // Giving up last night's film and then failing to play tonight's is the worst of both.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 40 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            cached = cache(2, 1 * GB),
            freeBytes = 1 * GB,
        )
        assertTrue("expected NotEnoughSpace, got $plan", plan is Plan.NotEnoughSpace)
    }

    @Test
    fun `headroom is respected, not just raw size`() {
        // Exactly the video's size free and nothing to give up leaves no room for the database.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 1 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            cached = emptyList(),
            freeBytes = 1 * GB,
        )
        assertTrue(plan is Plan.NotEnoughSpace)
    }

    @Test
    fun `carrying on with a half-downloaded video keeps the half it has`() {
        // The 2 GB already here belongs to the video being opened, and only the other 2 GB has to
        // fit. Deleting it would restart the download from byte zero, which is precisely what
        // somebody resuming a video is trying not to do.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 4 * GB,
            targetPartialBytes = 2 * GB,
            alreadyCached = false,
            cached = cache(TARGET, 2 * GB),
            freeBytes = 3 * GB,
        )
        assertEquals(Plan.Proceed, plan)
    }

    @Test
    fun `an unknown file size does not block playback`() {
        // Some documents arrive with no size; refusing to play them would be worse than trying.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 0,
            targetPartialBytes = 0,
            alreadyCached = false,
            cached = emptyList(),
            freeBytes = 10 * MB,
        )
        assertEquals(Plan.Proceed, plan)
    }

    @Test
    fun `an unknown size still replaces the cached film`() {
        // Nothing can be measured against a size of zero, but the cache holds one video whatever
        // the arithmetic says, and the download itself can report what will not fit.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 0,
            targetPartialBytes = 0,
            alreadyCached = false,
            cached = cache(2, 100 * MB),
            freeBytes = 50 * MB,
        )
        assertEquals(Plan.Evict(listOf(2), 100 * MB), plan)
    }

    @Test
    fun `the same file in two chats is counted once`() {
        // One TDLib file, two rows in the index, because it was forwarded into a second chat.
        // Counting its bytes twice promised space that deleting it could not return.
        val cached = listOf(Held(2, 2 * GB, updatedAt = 100), Held(2, 2 * GB, updatedAt = 200))
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 2 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            cached = cached,
            freeBytes = 500 * MB,
        )
        assertEquals(Plan.Evict(listOf(2), 2 * GB), plan)
    }
}

/**
 * Several videos ticked at once, which is the same decision as one video taken repeatedly.
 *
 * What these are really guarding is that a tick is never refused for a reason other than room, and
 * that the room the batch counts on is the cache rather than anything the viewer chose to keep.
 */
class CacheBatchTest {

    private fun candidates(vararg sizes: Long): List<CacheShelf.Candidate> =
        sizes.mapIndexed { index, size -> CacheShelf.Candidate(fileId = index + 10, sizeBytes = size) }

    @Test
    fun `a selection that fits is taken whole`() {
        val batch = CacheShelf.planBatch(
            candidates = candidates(GB, GB, GB),
            cached = emptyList(),
            freeBytes = 20 * GB,
        )
        assertEquals(listOf(0, 1, 2), batch.fits)
        assertFalse(batch.outOfSpace)
        assertTrue(batch.reclaimFileIds.isEmpty())
    }

    @Test
    fun `three ticked on a device that caches one are three downloads`() {
        // The old count limit refused two of these. Ticking three videos is a viewer asking for
        // three videos, and the cache has nothing to say about it.
        val batch = CacheShelf.planBatch(
            candidates = candidates(GB, GB, GB),
            cached = emptyList(),
            freeBytes = 20 * GB,
        )
        assertEquals(listOf(0, 1, 2), batch.fits)
    }

    @Test
    fun `the cached film is spent to make room for videos actually asked for`() {
        // 2 GB free will not hold a 2 GB film with headroom, but the 3 GB of cache will cover it.
        val batch = CacheShelf.planBatch(
            candidates = candidates(2 * GB),
            cached = cache(2, 3 * GB),
            freeBytes = 2 * GB,
        )
        assertEquals(listOf(0), batch.fits)
        assertEquals(listOf(2), batch.reclaimFileIds)
    }

    @Test
    fun `a ticked video that is itself the cached one is not deleted to fit itself`() {
        val batch = CacheShelf.planBatch(
            candidates = listOf(CacheShelf.Candidate(fileId = 10, sizeBytes = GB)),
            cached = cache(10, GB),
            freeBytes = 20 * GB,
        )
        assertTrue(batch.reclaimFileIds.isEmpty())
        assertEquals(listOf(0), batch.fits)
    }

    @Test
    fun `an empty selection asks for nothing and refuses nothing`() {
        val batch = CacheShelf.planBatch(
            candidates = emptyList(),
            cached = emptyList(),
            freeBytes = 20 * GB,
        )
        assertTrue(batch.fits.isEmpty())
        assertTrue(batch.alreadyHere.isEmpty())
        assertFalse(batch.outOfSpace)
    }

    @Test
    fun `one oversized video in the middle does not refuse the small ones behind it`() {
        val batch = CacheShelf.planBatch(
            candidates = candidates(GB, 60 * GB, GB),
            cached = emptyList(),
            freeBytes = 8 * GB,
        )
        assertEquals(listOf(0, 2), batch.fits)
        assertTrue(batch.outOfSpace)
    }

    @Test
    fun `a video already on the device costs the batch nothing`() {
        val batch = CacheShelf.planBatch(
            candidates = listOf(
                CacheShelf.Candidate(fileId = 10, sizeBytes = GB, alreadyHere = true),
                CacheShelf.Candidate(fileId = 11, sizeBytes = GB),
            ),
            cached = emptyList(),
            freeBytes = 20 * GB,
        )
        assertEquals(listOf(0), batch.alreadyHere)
        assertEquals(listOf(1), batch.fits)
        assertFalse(batch.outOfSpace)
    }

    @Test
    fun `a full disk takes none of them and says so`() {
        val batch = CacheShelf.planBatch(
            candidates = candidates(4 * GB, 4 * GB),
            cached = emptyList(),
            freeBytes = 500 * MB,
        )
        assertTrue("nothing should have been taken: ${batch.fits}", batch.fits.isEmpty())
        assertTrue(batch.outOfSpace)
    }
}

/**
 * The ceiling, which used to be a constant four gigabytes and is now a share of the volume.
 *
 * The case that mattered is the first one: the stick this app was written for reports 4.85 GB of
 * data partition, so the old constant let the app fill the device and call it correct.
 */
class CacheCeilingTest {

    @Test
    fun `a stick keeps well under its own disk`() {
        val stick = 4_849L * MB
        val ceiling = CacheShelf.ceiling(stick)
        assertTrue("$ceiling should leave the device room", ceiling <= stick - 2 * GB)
        assertTrue("$ceiling should still hold a film", ceiling >= CacheShelf.MIN_CEILING_BYTES)
    }

    @Test
    fun `a phone with room to spare stops at the cap`() {
        assertEquals(CacheShelf.MAX_CEILING_BYTES, CacheShelf.ceiling(128 * GB))
    }

    @Test
    fun `a small phone takes its share rather than the cap`() {
        // 40 per cent of eight gigabytes, which is under the cap, so the share is what binds.
        assertEquals((8 * GB * 4 / 10), CacheShelf.ceiling(8 * GB))
    }

    @Test
    fun `an unreadable volume falls back to the cap rather than to nothing`() {
        assertEquals(CacheShelf.MAX_CEILING_BYTES, CacheShelf.ceiling(0))
    }
}

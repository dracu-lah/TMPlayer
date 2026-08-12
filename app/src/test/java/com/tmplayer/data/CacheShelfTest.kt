package com.tmplayer.data

import com.tmplayer.data.CacheShelf.Held
import com.tmplayer.data.CacheShelf.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val GB = 1024L * 1024 * 1024
private const val MB = 1024L * 1024

/** The video being opened, which is never on the shelf unless a test puts it there. */
private const val TARGET = 1

private fun shelf(vararg entries: Pair<Int, Long>): List<Held> =
    entries.mapIndexed { index, (fileId, bytes) -> Held(fileId, bytes, updatedAt = index.toLong()) }

class CacheShelfTest {

    @Test
    fun `re-watching the video already here never deletes anything`() {
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 4 * GB,
            targetPartialBytes = 0,
            alreadyCached = true,
            held = shelf(TARGET to 4 * GB),
            freeBytes = 100 * MB,
            keepCount = 1,
        )
        assertEquals(Plan.Proceed, plan)
    }

    @Test
    fun `an empty shelf just plays`() {
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 1 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = emptyList(),
            freeBytes = 5 * GB,
            keepCount = 1,
        )
        assertEquals(Plan.Proceed, plan)
    }

    @Test
    fun `the television gives up its one previous video`() {
        // Room on the disk for both, but the viewer asked to keep one, so the old one goes.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 1 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = shelf(2 to 2 * GB),
            freeBytes = 5 * GB,
            keepCount = 1,
        )
        assertEquals(Plan.Evict(listOf(2), 2 * GB), plan)
    }

    @Test
    fun `a phone keeping three gives up nothing until the fourth`() {
        val held = shelf(2 to 1 * GB, 3 to 1 * GB)
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 1 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = held,
            freeBytes = 5 * GB,
            keepCount = 3,
        )
        assertEquals(Plan.Proceed, plan)
    }

    @Test
    fun `the oldest is the one that goes when the shelf is full`() {
        // Three held and three wanted, so exactly one has to go: the one played longest ago.
        val held = listOf(
            Held(fileId = 2, bytes = 1 * GB, updatedAt = 300),
            Held(fileId = 3, bytes = 1 * GB, updatedAt = 100),
            Held(fileId = 4, bytes = 1 * GB, updatedAt = 200),
        )
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 1 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = held,
            freeBytes = 5 * GB,
            keepCount = 3,
        )
        assertEquals(Plan.Evict(listOf(3), 1 * GB), plan)
    }

    @Test
    fun `a full disk gives up more than the count alone would`() {
        // The count is happy with three, but 500 MB free will not hold a 2 GB video, so the shelf
        // keeps giving up the oldest until it does.
        val held = listOf(
            Held(fileId = 2, bytes = 1 * GB, updatedAt = 100),
            Held(fileId = 3, bytes = 1 * GB, updatedAt = 200),
        )
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 2 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = held,
            freeBytes = 500 * MB,
            keepCount = 5,
        )
        assertEquals(Plan.Evict(listOf(2, 3), 2 * GB), plan)
    }

    @Test
    fun `a video larger than the whole device reports the shortfall`() {
        // 1 GB free plus 1 GB on the shelf is 2 GB; a 4 GB video needs 4 GB plus headroom.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 4 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = shelf(2 to 1 * GB),
            freeBytes = 1 * GB,
            keepCount = 3,
        )
        val short = plan as? Plan.NotEnoughSpace
        assertTrue("expected NotEnoughSpace, got $plan", short != null)
        assertEquals(4 * GB + CacheShelf.HEADROOM_BYTES - 2 * GB, short!!.shortfallBytes)
        assertEquals(1 * GB, short.reclaimBytes)
    }

    @Test
    fun `headroom is respected, not just raw size`() {
        // Exactly the video's size free and nothing to give up leaves no room for the database.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 1 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = emptyList(),
            freeBytes = 1 * GB,
            keepCount = 3,
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
            held = shelf(TARGET to 2 * GB),
            freeBytes = 3 * GB,
            keepCount = 1,
        )
        assertEquals(Plan.Proceed, plan)
    }

    @Test
    fun `no limit lets the disk have the last word`() {
        val held = shelf(2 to 1 * GB, 3 to 1 * GB, 4 to 1 * GB)
        val roomy = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 1 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = held,
            freeBytes = 5 * GB,
            keepCount = CacheShelf.UNLIMITED,
        )
        assertEquals(Plan.Proceed, roomy)

        val tight = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 1 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = held,
            freeBytes = 100 * MB,
            keepCount = CacheShelf.UNLIMITED,
        )
        assertEquals(Plan.Evict(listOf(2, 3), 2 * GB), tight)
    }

    @Test
    fun `an unknown file size does not block playback`() {
        // Some documents arrive with no size; refusing to play them would be worse than trying.
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 0,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = emptyList(),
            freeBytes = 10 * MB,
            keepCount = 3,
        )
        assertEquals(Plan.Proceed, plan)
    }

    @Test
    fun `an unknown size never empties the shelf to make room`() {
        // Nothing can be measured against a size of zero, so giving up three videos for it would
        // be giving them up for nothing. The count is still allowed its say.
        val held = listOf(
            Held(fileId = 2, bytes = 100 * MB, updatedAt = 100),
            Held(fileId = 3, bytes = 100 * MB, updatedAt = 200),
            Held(fileId = 4, bytes = 100 * MB, updatedAt = 300),
        )
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 0,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = held,
            freeBytes = 50 * MB,
            keepCount = CacheShelf.UNLIMITED,
        )
        assertEquals(Plan.Proceed, plan)
    }

    @Test
    fun `the same file in two chats is counted once`() {
        // One TDLib file, two rows in the index, because it was forwarded into a second chat.
        // Counting its bytes twice promised space that deleting it could not return.
        val held = listOf(
            Held(fileId = 2, bytes = 2 * GB, updatedAt = 100),
            Held(fileId = 2, bytes = 2 * GB, updatedAt = 200),
        )
        val plan = CacheShelf.plan(
            targetFileId = TARGET,
            targetSizeBytes = 3 * GB,
            targetPartialBytes = 0,
            alreadyCached = false,
            held = held,
            freeBytes = 500 * MB,
            keepCount = 5,
        )
        val short = plan as? Plan.NotEnoughSpace
        assertTrue("expected NotEnoughSpace, got $plan", short != null)
        assertEquals(2 * GB, short!!.reclaimBytes)
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

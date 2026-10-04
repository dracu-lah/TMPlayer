package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The decisions of a storage move: refusals in order, the dialog's words, what moves, what resumes. */
class StorageRelocationPlanTest {

    private val gb = 1024L * 1024 * 1024
    private val cache = File("/home/me/.cache/TMPlayer/cache")
    private val downloads = File("/home/me/Downloads/TMPlayer")

    private fun candidate(
        root: String = "/mnt/big",
        writable: Boolean = true,
        free: Long = 500 * gb,
        type: String = "ext4",
        marker: Boolean = false,
    ) = StorageRelocationPlan.Candidate(File(root), writable, free, type, marker)

    private fun validate(c: StorageRelocationPlan.Candidate, currentRoot: File? = null, downloadsBytes: Long = 38 * gb) =
        StorageRelocationPlan.validate(c, listOf(cache, downloads), currentRoot, downloadsBytes)

    @Test
    fun `a writable roomy local drive is allowed without a warning`() {
        assertEquals(StorageRelocationPlan.Verdict.Allowed(adopt = false, warning = null), validate(candidate()))
    }

    @Test
    fun `a folder TMPlayer cannot write in is refused first`() {
        val verdict = validate(candidate(writable = false, free = 0))
        assertEquals(StorageRelocationPlan.Verdict.Refused(StorageRelocationPlan.CANNOT_WRITE), verdict)
    }

    @Test
    fun `a folder inside the current downloads, or holding them, is refused`() {
        assertEquals(
            StorageRelocationPlan.Verdict.Refused(StorageRelocationPlan.NESTED),
            validate(candidate(root = "/home/me/Downloads/TMPlayer/sub")),
        )
        // ~/Downloads/TMPlayer is the default downloads folder itself.
        assertEquals(
            StorageRelocationPlan.Verdict.Refused(StorageRelocationPlan.NESTED),
            validate(candidate(root = "/home/me/Downloads")),
        )
        // The new TMPlayer folder would hold the cache.
        assertEquals(
            StorageRelocationPlan.Verdict.Refused(StorageRelocationPlan.NESTED),
            StorageRelocationPlan.validate(candidate(root = "/data"), listOf(File("/data/TMPlayer/cache")), null, 0),
        )
    }

    @Test
    fun `a drive without room for the downloads and a gigabyte says how much each is`() {
        val verdict = validate(candidate(free = 20 * gb), downloadsBytes = 38 * gb)
        verdict as StorageRelocationPlan.Verdict.Refused
        assertEquals("That drive has 20.0 GB free. The downloads alone need 39.0 GB.", verdict.reason)
    }

    @Test
    fun `the same location again is not a move`() {
        val verdict = validate(candidate(root = "/mnt/big"), currentRoot = File("/mnt/big/"))
        assertEquals(StorageRelocationPlan.Verdict.Refused(StorageRelocationPlan.SAME_PLACE), verdict)
    }

    @Test
    fun `a folder TMPlayer used before is offered for adopting`() {
        assertEquals(StorageRelocationPlan.Verdict.Allowed(adopt = true, warning = null), validate(candidate(marker = true)))
    }

    @Test
    fun `network drives are allowed with a warning`() {
        val nfs = validate(candidate(type = "nfs4")) as StorageRelocationPlan.Verdict.Allowed
        assertEquals(StorageRelocationPlan.NETWORK_WARNING, nfs.warning)
        assertTrue(StorageRelocationPlan.isNetworkPath("\\\\server\\films"))
    }

    @Test
    fun `the confirm dialog says what moves and what is cleared`() {
        val (title, body) = StorageRelocationPlan.confirmText(
            where = "E:\\",
            newDownloads = "E:\\TMPlayer\\downloads",
            newCache = "E:\\TMPlayer\\cache",
            downloadCount = 12,
            downloadsBytes = 38 * gb,
            cacheBytes = (3.1 * gb).toLong(),
            adopt = false,
        )
        assertEquals("Move TMPlayer's files to E:\\?", title)
        assertEquals(
            "Downloads (12 videos, 38.0 GB) move to E:\\TMPlayer\\downloads. The cache (3.1 GB) is cleared and " +
                "starts again at E:\\TMPlayer\\cache. Nothing is removed from Telegram. Playback and downloads " +
                "pause while this happens.",
            body,
        )
    }

    @Test
    fun `only downloads with a file that is not in the new folder move`() {
        fun record(id: Long, path: String?) = ResumeRecord(1, id, id.toInt(), "v$id", "c", 1, 1, 0, 0, 0, path)
        val records = listOf(
            record(1, "/home/me/Downloads/TMPlayer/a.mkv"),
            record(2, "/mnt/big/TMPlayer/downloads/b.mkv"),
            record(3, null),
            record(4, "/home/me/Downloads/TMPlayer/gone.mkv"),
        )
        val moving = StorageRelocationPlan.toMove(records, File("/mnt/big/TMPlayer/downloads")) { it.name != "gone.mkv" }
        assertEquals(listOf(1L), moving.map { it.messageId })
    }

    @Test
    fun `a pending move reads back as written, and blank is none`() {
        val pending = StorageRelocationPlan.Pending("", "/home/me/.cache/TMPlayer/cache", "/mnt/big/TMPlayer/downloads")
        assertEquals(pending, StorageRelocationPlan.Pending.decode(pending.encode()))
        assertNull(StorageRelocationPlan.Pending.decode(""))
        assertNull(StorageRelocationPlan.Pending.decode("garbage"))
    }
}

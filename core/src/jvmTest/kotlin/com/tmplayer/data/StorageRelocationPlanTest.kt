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
        assertEquals("That drive has 20.0 GB free. The downloads and 1 GB to spare need 39.0 GB.", verdict.reason)
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
            "Downloads (12 videos, 38.0 GB) move to E:\\TMPlayer\\downloads. Recently played videos (3.1 GB) are cleared and " +
                "start again at E:\\TMPlayer\\cache. Nothing is removed from Telegram. Playback and downloads " +
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

    @Test
    fun `a pending move written before the downloads tick still reads, and moves them`() {
        val old = "\u001F" 
        val three = listOf("/mnt/big", "/old/cache", "/old/downloads").joinToString(old)
        assertEquals(true, StorageRelocationPlan.Pending.decode(three)!!.moveDownloads)
        val left = StorageRelocationPlan.Pending("/mnt/big", "/c", "/d", moveDownloads = false)
        assertEquals(left, StorageRelocationPlan.Pending.decode(left.encode()))
        assertNull(StorageRelocationPlan.Pending.decode(listOf("a", "b", "c", "1", "extra").joinToString(old)))
    }

    @Test
    fun `a folder on Windows whose path leaves no room for a file name is refused`() {
        val deep = "E:\\" + "very long folder name ".repeat(10)
        val verdict = StorageRelocationPlan.validate(candidate(root = deep), listOf(cache, downloads), null, 0, pathLimit = 259)
        assertEquals(StorageRelocationPlan.Verdict.Refused(com.tmplayer.i18n.L.storagePathTooLong), verdict)
        // The same path where no limit applies, and a short one under it.
        assertTrue(StorageRelocationPlan.validate(candidate(root = deep), listOf(cache, downloads), null, 0) is StorageRelocationPlan.Verdict.Allowed)
        assertTrue(StorageRelocationPlan.validate(candidate(root = "E:\\Video"), listOf(cache, downloads), null, 0, pathLimit = 259) is StorageRelocationPlan.Verdict.Allowed)
    }

    @Test
    fun `a folder reached through a link to the current one is the current one`() {
        val tmp = java.nio.file.Files.createTempDirectory("tm-link").toFile()
        try {
            val real = File(tmp, "real/TMPlayer/downloads").apply { mkdirs() }
            val link = File(tmp, "link")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), File(tmp, "real").toPath())
            } catch (_: Exception) {
                return // no symbolic links here (Windows without the right): nothing to check
            }
            val verdict = StorageRelocationPlan.validate(
                candidate(root = link.path), listOf(File(tmp, "real/TMPlayer/cache"), real), null, 0,
            )
            assertEquals(StorageRelocationPlan.Verdict.Refused(StorageRelocationPlan.NESTED), verdict)
            val same = StorageRelocationPlan.validate(candidate(root = link.path), listOf(cache, downloads), File(tmp, "real"), 0)
            assertEquals(StorageRelocationPlan.Verdict.Refused(StorageRelocationPlan.SAME_PLACE), same)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `a file in a link to the new folder counts as there, and files gone are counted as missing`() {
        fun record(id: Long, path: String?) = ResumeRecord(1, id, id.toInt(), "v$id", "c", 1, 1, 0, 0, 0, path)
        val records = listOf(record(1, "/a/one.mkv"), record(2, "/b/two.mkv"), record(3, null))
        assertEquals(listOf(2L), StorageRelocationPlan.missing(records) { it.name == "one.mkv" }.map { it.messageId })
    }

    @Test
    fun `a record whose file is gone but sits by name in the new folder is adoptable`() {
        fun record(id: Long, path: String?) = ResumeRecord(1, id, id.toInt(), "v$id", "c", 1, 1, 0, 0, 0, path)
        val records = listOf(record(1, "/old/a.mkv"), record(2, "/old/b.mkv"), record(3, null))
        val there = setOf("/new/a.mkv", "/old/b.mkv")
        val found = StorageRelocationPlan.adoptable(records, File("/new")) { it.path in there }
        assertEquals(listOf(1L), found.map { it.first.messageId })
        assertEquals(File("/new/a.mkv"), found.single().second)
    }

    @Test
    fun `room for the downloads needs the gigabyte spare, and nothing to move always fits`() {
        assertTrue(StorageRelocationPlan.fits(0, 0))
        assertTrue(StorageRelocationPlan.fits(10 * gb, 11 * gb))
        assertTrue(!StorageRelocationPlan.fits(10 * gb, 11 * gb - 1))
    }

    @Test
    fun `a full disk is told by the words each system uses`() {
        assertTrue(StorageRelocationPlan.isNoSpace(java.io.IOException("No space left on device")))
        assertTrue(StorageRelocationPlan.isNoSpace(java.io.IOException("x", java.io.IOException("There is not enough space on the disk"))))
        assertTrue(!StorageRelocationPlan.isNoSpace(java.io.IOException("Permission denied")))
    }

    @Test
    fun `the confirm says downloads stay when they are not asked to move`() {
        val (_, body) = StorageRelocationPlan.confirmText(
            where = "/mnt/big", newDownloads = "/mnt/big/TMPlayer/downloads", newCache = "/mnt/big/TMPlayer/cache",
            downloadCount = 2, downloadsBytes = 3 * gb, cacheBytes = 0, adopt = false, moveDownloads = false,
        )
        assertTrue(body.startsWith("The 2 downloads (3.0 GB) stay where they are and keep playing. New ones go to /mnt/big/TMPlayer/downloads."))
    }
}

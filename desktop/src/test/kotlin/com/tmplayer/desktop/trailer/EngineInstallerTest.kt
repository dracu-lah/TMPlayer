package com.tmplayer.desktop.trailer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Chromium's download and install for in-app trailers ([EngineInstaller]) with a fake network and
 * a fake drive: the free space offer (where it fits, the second place, too little, unknown),
 * nothing written before the install, a whole install found again, and every way it can go wrong
 * (cancel half way, a failed download, a changed file, too many bytes, an archive reaching outside,
 * the drive filling up, another build's files) leaving no partial file behind.
 */
class EngineInstallerTest {

    private val root: File = Files.createTempDirectory("tm-trailer-engine").toFile()
    private val first = File(root, "chosen/TMPlayer/browser")
    private val second = File(root, "data/browser")

    private fun tarball(entries: List<Triple<String, ByteArray?, Int>>): ByteArray {
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(tar)).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, body, mode) in entries) {
                val entry = TarArchiveEntry(name)
                if (body != null) entry.size = body.size.toLong()
                entry.mode = mode
                out.putArchiveEntry(entry)
                body?.let { out.write(it) }
                out.closeArchiveEntry()
            }
        }
        val jar = ByteArrayOutputStream()
        ZipOutputStream(jar).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.putNextEntry(ZipEntry("jcef-natives-linux-amd64.tar.gz"))
            zip.write(tar.toByteArray())
        }
        return jar.toByteArray()
    }

    private fun meta(tag: String = CefNatives.RELEASE_TAG, platform: String = "linux-amd64") =
        """{"jcef_url":"x","release_tag":"$tag","release_url":"y","platform":"$platform"}""".toByteArray()

    private val goodEntries = listOf(
        Triple("locales/", null, 0b111_101_101),
        Triple("build_meta.json", meta(), 0b110_100_100),
        Triple("libjcef.so", ByteArray(5000) { (it % 251).toByte() }, 0b111_101_101),
        Triple("locales/en-US.pak", ByteArray(1200) { 7 }, 0b110_100_100),
    )

    private fun nativeFor(jar: ByteArray, unpacked: Long = 6200L + meta().size) = CefNative(
        platform = "linux-amd64",
        bytes = jar.size.toLong(),
        unpackedBytes = unpacked,
        sha256 = MessageDigest.getInstance("SHA-256").digest(jar).joinToString("") { "%02x".format(it) },
    )

    /** Serves [bodies] by URL; a URL with none fails. [hold] stops half way until cancelled. */
    private class FakeNet(val bodies: Map<String, ByteArray>, val hold: CompletableDeferred<Unit>? = null) : EngineDownloader {
        val asked = mutableListOf<String>()
        override suspend fun download(url: String, sink: OutputStream, onBytes: (Long) -> Unit) {
            asked += url
            val body = bodies[url] ?: throw IOException("HTTP 503")
            val half = body.size / 2
            sink.write(body, 0, half)
            onBytes(half.toLong())
            if (hold != null) {
                hold.complete(Unit)
                awaitCancellation()
            }
            sink.write(body, half, body.size - half)
            onBytes(body.size.toLong())
        }
    }

    private class Drive(var free: Long?) : FreeSpace {
        val perPlace = mutableMapOf<String, Long?>()
        override fun usableBytes(dir: File): Long? = if (perPlace.containsKey(dir.path)) perPlace[dir.path] else free
    }

    private fun installer(native: CefNative, net: EngineDownloader, drive: FreeSpace = Drive(10_000_000_000)) =
        EngineInstaller(native, { listOf(first, second) }, drive, net)

    private fun leftovers(): List<String> = root.walkTopDown().filter { it.name == EngineInstaller.PART || it.name == EngineInstaller.STAGING }.map { it.path }.toList()

    private fun fits(i: EngineInstaller) = i.offer() as EngineOffer.Fits

    @Test
    fun `the offer is the first place with room, and writes nothing`() {
        val jar = tarball(goodEntries)
        val native = nativeFor(jar)
        val drive = Drive(10_000_000_000)
        val offer = installer(native, FakeNet(emptyMap()), drive).offer()
        assertEquals(EngineOffer.Fits(native, first, 10_000_000_000), offer)
        assertFalse("nothing made before Download", first.exists() || second.exists())
        assertEquals(native.bytes + native.unpackedBytes + EngineInstaller.HEADROOM_BYTES, offer.neededBytes)
    }

    @Test
    fun `a full chosen drive offers the default place where it fits`() {
        val native = nativeFor(tarball(goodEntries))
        val drive = Drive(10_000_000_000).apply { perPlace[first.path] = 1_000 }
        assertEquals(EngineOffer.Fits(native, second, 10_000_000_000), installer(native, FakeNet(emptyMap()), drive).offer())
    }

    @Test
    fun `nowhere with room says what the preferred place lacks, unknown space is said as such`() {
        val native = nativeFor(tarball(goodEntries))
        val tight = Drive(1_000)
        val offer = installer(native, FakeNet(emptyMap()), tight).offer()
        assertEquals(EngineOffer.NoRoom(native, first, 1_000), offer)
        assertTrue(offer.neededBytes > 1_000)
        assertEquals(EngineOffer.SpaceUnknown(native, first), installer(native, FakeNet(emptyMap()), Drive(null)).offer())
    }

    @Test
    fun `a whole install is checked, unpacked, locked and found again`() = runBlocking {
        val jar = tarball(goodEntries)
        val native = nativeFor(jar)
        val net = FakeNet(mapOf(native.urls[0] to jar))
        val i = installer(native, net)
        val progress = mutableListOf<EngineProgress>()
        val dir = i.install(fits(i)) { progress += it }
        assertEquals(File(first, EngineInstaller.FOLDER), dir)
        assertTrue(File(dir, EngineInstaller.LOCK).isFile)
        assertEquals(5000L, File(dir, "libjcef.so").length())
        assertTrue(File(dir, "locales/en-US.pak").isFile)
        if (!com.tmplayer.desktop.os.OsInfo.isWindows) assertTrue("the mode's executable bit is kept", File(dir, "libjcef.so").canExecute())
        assertEquals(dir, i.installed())
        assertEquals(emptyList<String>(), leftovers())
        assertTrue(progress.first() is EngineProgress.Downloading)
        assertTrue(progress.last() is EngineProgress.Unpacking)
        assertEquals(listOf(native.urls[0]), net.asked)
    }

    @Test
    fun `cancel half way leaves no partial file and no install`() = runBlocking {
        val jar = tarball(goodEntries)
        val native = nativeFor(jar)
        val held = CompletableDeferred<Unit>()
        val i = installer(native, FakeNet(mapOf(native.urls[0] to jar), hold = held))
        val offer = fits(i)
        val job = async(start = CoroutineStart.UNDISPATCHED) { runCatching { i.install(offer) {} } }
        withTimeout(10_000) { held.await() }
        assertTrue("the half download is only ever the temp name", File(first, EngineInstaller.PART).isFile)
        job.cancelAndJoin()
        assertEquals(emptyList<String>(), leftovers())
        assertNull(i.installed())
        assertFalse(File(first, EngineInstaller.FOLDER).exists())
    }

    @Test
    fun `a failed download fails, tries the second source first, and leaves nothing`() = runBlocking {
        val jar = tarball(goodEntries)
        val native = nativeFor(jar)
        val net = FakeNet(emptyMap())
        val i = installer(native, net)
        try {
            i.install(fits(i)) {}
            fail("expected a failure")
        } catch (e: IOException) {
            assertFalse(e is EngineChecksumMismatch)
        }
        assertEquals(native.urls, net.asked)
        assertEquals(emptyList<String>(), leftovers())
        assertNull(i.installed())
    }

    @Test
    fun `the second source is used when the first fails`() = runBlocking {
        val jar = tarball(goodEntries)
        val native = nativeFor(jar)
        val i = installer(native, FakeNet(mapOf(native.urls[1] to jar)))
        assertNotNull(i.install(fits(i)) {})
        assertNotNull(i.installed())
    }

    @Test
    fun `a changed file is refused by its SHA-256 and nothing is unpacked`() = runBlocking {
        val jar = tarball(goodEntries)
        val native = nativeFor(jar)
        val tampered = jar.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val i = installer(native, FakeNet(mapOf(native.urls[0] to tampered, native.urls[1] to tampered)))
        try {
            i.install(fits(i)) {}
            fail("expected the checksum to refuse it")
        } catch (e: EngineChecksumMismatch) {
            assertTrue(e.message.orEmpty().contains("SHA-256"))
        }
        assertEquals(emptyList<String>(), leftovers())
        assertNull(i.installed())
        assertFalse(File(first, EngineInstaller.FOLDER).exists())
    }

    @Test
    fun `more bytes than the pinned file is refused`() = runBlocking {
        val jar = tarball(goodEntries)
        val native = nativeFor(jar)
        val longer = jar + ByteArray(10)
        val i = installer(native, FakeNet(mapOf(native.urls[0] to longer, native.urls[1] to longer)))
        try {
            i.install(fits(i)) {}
            fail("expected a refusal")
        } catch (e: EngineChecksumMismatch) {
            // refused
        }
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `an archive entry reaching outside the folder is refused and nothing is kept`() = runBlocking {
        val jar = tarball(goodEntries + Triple("../../escaped.txt", "x".toByteArray(), 0b110_100_100))
        val native = nativeFor(jar)
        val i = installer(native, FakeNet(mapOf(native.urls[0] to jar)))
        try {
            i.install(fits(i)) {}
            fail("expected a refusal")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("outside"))
        }
        assertFalse(root.walkTopDown().any { it.name == "escaped.txt" })
        assertEquals(emptyList<String>(), leftovers())
        assertNull(i.installed())
    }

    @Test
    fun `another build's files are refused`() = runBlocking {
        val jar = tarball(listOf(Triple("build_meta.json", meta(tag = "jcef-other+cef-1.0"), 0b110_100_100)))
        val native = nativeFor(jar, unpacked = 200)
        val i = installer(native, FakeNet(mapOf(native.urls[0] to jar)))
        try {
            i.install(fits(i)) {}
            fail("expected a refusal")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("another build"))
        }
        assertNull(i.installed())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `a drive that filled up since the offer is refused before a byte is written`() = runBlocking {
        val jar = tarball(goodEntries)
        val native = nativeFor(jar)
        val drive = Drive(10_000_000_000)
        val net = FakeNet(mapOf(native.urls[0] to jar))
        val i = installer(native, net, drive)
        val offer = fits(i)
        drive.free = 1_000
        try {
            i.install(offer) {}
            fail("expected no room")
        } catch (e: EngineNoRoom) {
            // refused
        }
        assertEquals("nothing was fetched", emptyList<String>(), net.asked)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `what a killed run left is swept on the next look, and an older build goes after an install`() = runBlocking {
        first.mkdirs()
        File(first, EngineInstaller.PART).writeBytes(ByteArray(100))
        File(first, "${EngineInstaller.STAGING}/half").apply { parentFile.mkdirs() }.writeBytes(ByteArray(10))
        val old = File(first, "jcef-140.0.1").apply { mkdirs() }
        File(old, EngineInstaller.META).writeText("{}")
        val mine = File(first, "notes").apply { mkdirs() }
        val jar = tarball(goodEntries)
        val native = nativeFor(jar)
        val i = installer(native, FakeNet(mapOf(native.urls[0] to jar)))
        val offer = fits(i)
        assertEquals(emptyList<String>(), leftovers())
        i.install(offer) {}
        assertFalse("the older build is removed", old.exists())
        assertTrue("a folder TMPlayer did not make stays", mine.exists())
    }

    @Test
    fun `cancel is a cancellation, not a failure`() = runBlocking {
        val jar = tarball(goodEntries)
        val native = nativeFor(jar)
        val held = CompletableDeferred<Unit>()
        val i = installer(native, FakeNet(mapOf(native.urls[0] to jar), hold = held))
        val offer = fits(i)
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { i.install(offer) {} } }
        held.await()
        result.cancel()
        val outcome = runCatching { result.await() }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
    }
}

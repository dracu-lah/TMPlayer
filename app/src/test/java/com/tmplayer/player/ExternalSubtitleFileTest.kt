package com.tmplayer.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The subtitle handed to VLC or MX Player with a video: what is picked, what is skipped, and the address it goes out at. */
@RunWith(RobolectricTestRunner::class)
class ExternalSubtitleFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun file(name: String, text: String = "1\n00:00:01,000 --> 00:00:02,000\nHi\n"): File =
        File(folder.root, name).apply { writeText(text) }

    @Test
    fun `a file that is gone, empty or not a subtitle is skipped`() {
        assertFalse(ExternalSubtitleFile.usable(null))
        assertFalse(ExternalSubtitleFile.usable(File(folder.root, "gone.srt")))
        assertFalse(ExternalSubtitleFile.usable(file("empty.srt", "")))
        assertFalse(ExternalSubtitleFile.usable(file("pic.sub")))
        assertFalse(ExternalSubtitleFile.usable(file("notes.txt")))
        assertTrue(ExternalSubtitleFile.usable(file("ok.srt")))
        assertTrue(ExternalSubtitleFile.usable(file("ok.ass")))
        assertTrue(ExternalSubtitleFile.usable(file("ok.VTT")))
    }

    @Test
    fun `a file over the size limit is skipped`() {
        val big = File(folder.root, "big.srt")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(ExternalSubtitle.MAX_BYTES + 1) }
        assertFalse(ExternalSubtitleFile.usable(big))
    }

    @Test
    fun `the remembered file wins, and a remembered file that is gone falls back to a sidecar`() {
        val video = file("Film.mkv", "x")
        val side = file("Film.srt")
        val remembered = file("fetched.srt")
        assertEquals(remembered, ExternalSubtitleFile.pick(remembered, ExternalSubtitleFile.sidecarsOf(video)))
        remembered.delete()
        assertEquals(side, ExternalSubtitleFile.pick(remembered, ExternalSubtitleFile.sidecarsOf(video)))
        side.delete()
        assertNull(ExternalSubtitleFile.pick(remembered, ExternalSubtitleFile.sidecarsOf(video)))
    }

    @Test
    fun `sidecars are the files named as the video, the preferred language first, and never another video's`() {
        val video = file("Film.2019.mkv", "x")
        file("Film.2019.srt")
        file("Film.2019.fr.srt")
        file("Film.2019.en.srt")
        file("Film.2019.en.extra.srt")
        file("Other.srt")
        file("Film.20190.srt")
        val names = ExternalSubtitleFile.sidecarsOf(video, "en").map { it.name }
        // The preferred language, then the plain name, then the other languages.
        assertEquals(listOf("Film.2019.en.srt", "Film.2019.srt", "Film.2019.fr.srt"), names)
        assertEquals("Film.2019.en.srt", names.first())
        assertFalse(names.contains("Other.srt"))
        assertFalse(names.contains("Film.20190.srt"))
        assertFalse(names.contains("Film.2019.en.extra.srt"))
    }

    @Test
    fun `a video with no folder or no name has no sidecars`() {
        assertTrue(ExternalSubtitleFile.sidecarsOf(File("/")).isEmpty())
        assertTrue(ExternalSubtitleFile.sidecarsOf(File(folder.root, ".mkv")).isEmpty())
    }

    @Test
    fun `the memory keeps a video's file, bounds itself and reads garbage as empty`() {
        val store = File(folder.root, "memory.json")
        val memory = SubtitleMemory(store, max = 3)
        memory.remember("1:1", "/a.srt")
        memory.remember("1:2", "/b.srt")
        memory.remember("1:3", "/c.srt")
        memory.remember("1:4", "/d.srt")
        assertNull("the oldest went", memory.recall("1:1"))
        assertEquals("/d.srt", memory.recall("1:4"))
        // A new instance reads it back.
        assertEquals("/c.srt", SubtitleMemory(store, max = 3).recall("1:3"))
        memory.forget("1:3")
        assertNull(SubtitleMemory(store, max = 3).recall("1:3"))
        store.writeText("{not json")
        assertNull(SubtitleMemory(store).recall("1:4"))
        // A blank key or an overlong path is not stored.
        memory.remember("", "/x.srt")
        memory.remember("1:9", "p".repeat(1000))
        assertNull(memory.recall("1:9"))
    }

    @Test
    fun `the provider serves the two cache folders the subtitles live in, and a file elsewhere gets no address`() {
        // Robolectric's provider is built over a different cache directory than the test's, so the
        // address itself is checked on a device; here, that the folders are listed and that a file
        // outside every listed folder is not offered.
        val xml = context.resources.getXml(com.tmplayer.R.xml.update_paths)
        val roots = mutableListOf<Pair<String, String>>()
        while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "cache-path") {
                roots += (xml.getAttributeValue(null, "name") ?: "") to (xml.getAttributeValue(null, "path") ?: "")
            }
            xml.next()
        }
        assertTrue(roots.contains("subtitles" to "subtitles/"))
        assertTrue(roots.contains("online_subtitles" to "online-subtitles/files/"))
        assertNull(ExternalSubtitleFile.handoutUri(context, file("elsewhere.srt")))
    }

    @Test
    fun `forVideo gives nothing once the remembered file is gone, and nothing for a video never remembered`() {
        val cached = File(context.cacheDir, "subtitles").apply { mkdirs() }.let { File(it, "2-1.srt").apply { writeText("x") } }
        val key = SubtitleMemory.keyOf(-100123L, 77L)
        SubtitleMemory.at(context).remember(key, cached.absolutePath)
        cached.delete()
        assertNull(ExternalSubtitleFile.forVideo(context, key, null))
        assertNull(ExternalSubtitleFile.forVideo(context, SubtitleMemory.keyOf(5L, 5L), null))
    }
}

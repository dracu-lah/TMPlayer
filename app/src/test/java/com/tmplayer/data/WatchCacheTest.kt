package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The walk that finds the videos nothing in the app can name.
 *
 * This is the piece that answers a viewer holding two and a half gigabytes and a Downloads screen
 * showing one film, so what it must not do is miss a file or offer up one that is spoken for.
 */
class WatchCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun make(path: String, bytes: Int): File {
        val target = File(folder.root, path)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(bytes))
        return target
    }

    @Test
    fun `finds videos in every directory a film can be in`() {
        make("videos/one.mp4", 100)
        make("documents/two.mkv", 200)
        make("animations/three.gif", 50)
        make("temp/four.mp4.part", 300)

        val found = WatchCache.straysIn(folder.root, emptySet())

        assertEquals(4, found.size)
        assertEquals(650L, found.sumOf { it.bytes })
    }

    @Test
    fun `leaves the pictures alone`() {
        make("photos/chat.jpg", 100)
        make("thumbnails/small.jpg", 20)
        make("profile_photos/me.jpg", 30)
        make("videos/film.mp4", 400)

        val found = WatchCache.straysIn(folder.root, emptySet())

        assertEquals(1, found.size)
        assertEquals("film", found.single().title)
    }

    @Test
    fun `a file the app already knows about is not a stray`() {
        val known = make("videos/downloaded.mp4", 500)
        make("videos/orphan.mp4", 300)

        val found = WatchCache.straysIn(folder.root, setOf(known.absolutePath))

        assertEquals(1, found.size)
        assertEquals(300L, found.single().bytes)
    }

    @Test
    fun `the biggest is offered first, since that is what the space is in`() {
        make("videos/small.mp4", 10)
        make("videos/huge.mp4", 900)
        make("videos/middling.mp4", 400)

        val found = WatchCache.straysIn(folder.root, emptySet())

        assertEquals(listOf(900L, 400L, 10L), found.map { it.bytes })
    }

    @Test
    fun `an empty file is not worth a row`() {
        make("videos/nothing.mp4", 0)

        assertTrue(WatchCache.straysIn(folder.root, emptySet()).isEmpty())
    }

    @Test
    fun `a missing directory is not an error`() {
        assertTrue(WatchCache.straysIn(File(folder.root, "never-existed"), emptySet()).isEmpty())
    }

    @Test
    fun `a name is made from the file, since that is all there is`() {
        make("videos/Kerala_Crime_Files_S01E04.mkv", 100)

        assertEquals(
            "Kerala Crime Files S01E04",
            WatchCache.straysIn(folder.root, emptySet()).single().title,
        )
    }
}

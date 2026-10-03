package com.tmplayer.desktop.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SubtitleDropTest {

    @Test
    fun `picks the first subtitle among the dropped files`() {
        val srt = File("/films/Night Train.srt")
        val picked = SubtitleDrop.pick(listOf(File("/films/cover.jpg").toURI().toString(), srt.toURI().toString()))
        assertEquals(srt.absolutePath, picked)
    }

    @Test
    fun `extensions are matched whatever their case`() {
        assertEquals(File("/a/B.ASS").absolutePath, SubtitleDrop.pick(listOf("/a/B.ASS")))
        assertEquals(File("/a/c.VtT").absolutePath, SubtitleDrop.pick(listOf("/a/c.VtT")))
    }

    @Test
    fun `nothing that is not a subtitle`() {
        assertNull(SubtitleDrop.pick(listOf("/a/video.mkv", "/a/readme.txt")))
        assertNull(SubtitleDrop.pick(emptyList()))
    }

    @Test
    fun `file uris with spaces decode`() {
        assertEquals(File("/a b/c d.srt").absolutePath, SubtitleDrop.pick(listOf("file:/a%20b/c%20d.srt")))
    }
}

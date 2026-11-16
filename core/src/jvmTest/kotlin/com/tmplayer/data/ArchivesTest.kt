package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivesTest {

    @Test
    fun `archives are told by their names and their volumes`() {
        assertEquals(ArchiveKind.Zip, Archives.kindOf("Season 1.zip"))
        assertEquals(ArchiveKind.Rar, Archives.kindOf("Lectures.part2.rar"))
        assertEquals(ArchiveKind.Rar, Archives.kindOf("Lectures.r01"))
        assertEquals(ArchiveKind.SevenZip, Archives.kindOf("Course.7z.003"))
        assertEquals(ArchiveKind.Other, Archives.kindOf("backup.tar.gz"))
        assertEquals(ArchiveKind.Zip, Archives.kindOf("download", "application/zip"))
        assertTrue(Archives.isVolume("Course.7z.003"))
        assertTrue(Archives.isVolume("Lectures.part2.rar"))
        assertFalse(Archives.isVolume("Season 1.zip"))
    }

    @Test
    fun `videos and their pieces are never archives`() {
        assertNull(Archives.kindOf("Holiday.mkv"))
        assertNull(Archives.kindOf("Holiday.mkv.001"))
        assertNull(Archives.kindOf("Lesson 3.mp4", "video/mp4"))
    }

    @Test
    fun `a page of archives is merged newest first, each once`() {
        fun a(id: Long) = ArchiveFile(1, id, id.toInt(), "f$id.zip", 10, 0, ArchiveKind.Zip)
        val merged = Archives.merge(listOf(a(5), a(3)), listOf(a(9), a(3)))
        assertEquals(listOf(9L, 5L, 3L), merged.map { it.messageId })
    }
}

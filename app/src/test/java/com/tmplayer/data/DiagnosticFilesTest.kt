package com.tmplayer.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sign-out and "Delete your data" leave no trace, marks, test result, app log or waiting report behind. */
class DiagnosticFilesTest {

    private fun temp(): File = File.createTempFile("diag", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun `every kept file and folder goes`() {
        val files = temp()
        val cache = temp()
        for (name in DiagnosticFiles.FILES_NAMES) {
            File(files, name).writeText("x")
            File(files, "$name.part").writeText("x")
        }
        File(files, "logs").mkdirs()
        File(files, "logs/tmplayer-app.log").writeText("a name")
        File(cache, "logs").mkdirs()
        File(cache, "logs/tmplayer-logs.txt").writeText("a name")
        File(files, "keep.txt").writeText("not ours")

        DiagnosticFiles.forgetIn(files, cache)

        assertEquals(listOf("keep.txt"), files.list()!!.sorted())
        assertFalse(File(cache, "logs").exists())
    }

    @Test
    fun `the trace file and the marks file are on the list by their own names`() {
        assertTrue("playback-trace.jsonl" in DiagnosticFiles.FILES_NAMES)
        assertTrue("playback-marks.jsonl" in DiagnosticFiles.FILES_NAMES)
    }

    @Test
    fun `nothing there is not an error`() {
        DiagnosticFiles.forgetIn(File(temp(), "missing"), File(temp(), "missing"))
    }
}

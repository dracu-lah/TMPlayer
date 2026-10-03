package com.tmplayer.desktop.os

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeInventoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the report names TDLib, the mpv runtime and JNA with where each comes from`() {
        val lines = NativeInventory.report(tmp.root)
        lines.forEach(::println)
        val tdlib = lines.single { it.startsWith("tdlib:") }
        assertTrue(tdlib, tdlib.contains("tdl-coroutines"))
        val mpv = lines.single { it.startsWith("mpv runtime:") }
        assertTrue(mpv, mpv.contains("mediamp-mpv-runtime-${OsInfo.osTag}-${OsInfo.archTag}"))
        assertTrue(lines.single { it.startsWith("jna:") }.startsWith("jna: 5.16.0 from "))
        assertTrue(lines.single { it.startsWith("mpv runtime dir:") }.contains("no libmpv"))
    }
}

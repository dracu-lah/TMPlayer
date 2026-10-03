package com.tmplayer.desktop.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class OpenExternalTest {

    @Test
    fun `each OS has its own opener`() {
        val url = "https://github.com/dracu-lah/TMPlayer/releases?a=1&b=2"
        // One argument, so the & in a URL is never read by a shell.
        assertEquals(listOf("rundll32", "url.dll,FileProtocolHandler", url), OpenExternal.command("windows", url))
        assertEquals(listOf("open", url), OpenExternal.command("macos", url))
        assertEquals(listOf("xdg-open", url), OpenExternal.command("linux", url))
    }

    @Test
    fun `reveal selects the file where the OS can`() {
        val f = File("/videos/a b.mkv")
        assertEquals(listOf("explorer.exe", "/select,", f.absolutePath), OpenExternal.revealCommand("windows", f))
        assertEquals(listOf("open", "-R", f.absolutePath), OpenExternal.revealCommand("macos", f))
        assertNull(OpenExternal.revealCommand("linux", f))
    }
}

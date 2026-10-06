package com.tmplayer.ui.about

import com.tmplayer.data.SupportReminder
import com.tmplayer.ui.about.About.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutTest {

    @Test
    fun `wrapped lines join and links keep only their words`() {
        val blocks = About.readable(
            """
            # Notices

            See [the exception](LICENSE-OPENSSL-EXCEPTION.md) and
            <https://example.org/a>, built with `--flag`.

            - Source: <https://example.org/src>
              continued here
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Block.Heading(1, "Notices"),
                Block.Paragraph("See the exception and https://example.org/a, built with --flag."),
                Block.Bullet("Source: https://example.org/src continued here"),
            ),
            blocks,
        )
    }

    @Test
    fun `a table row becomes one bullet named by its header`() {
        val blocks = About.readable(
            """
            | Component | Windows x64 | Linux x64 | Licence |
            |---|---|---|---|
            | mpv (libmpv) | 0.41.0 | 0.41.0 | LGPL-2.1-or-later |
            """.trimIndent(),
        )
        assertEquals(
            listOf(Block.Bullet("mpv (libmpv). Windows x64: 0.41.0. Linux x64: 0.41.0. Licence: LGPL-2.1-or-later")),
            blocks,
        )
    }

    @Test
    fun `the bundled notices are the real file`() {
        val first = About.notices.first()
        assertEquals(Block.Heading(1, "Third-party notices"), first)
        assertTrue(About.notices.any { it.text.contains("mediamp") })
        assertTrue(About.notices.none { it.text.contains("<http") || it.text.contains("](") })
    }

    @Test
    fun `only a release version links to its own release`() {
        assertEquals("${About.SOURCE}/releases/tag/v1.22.1", About.releaseUrl("1.22.1"))
        assertEquals("${About.SOURCE}/releases", About.releaseUrl("1.0.0-dev"))
    }

    @Test
    fun `the support group follows the one switch`() {
        assertEquals(About.SUPPORT_TITLE, About.groups("1.22.1").last().title)
        assertEquals(listOf(About.supportUrl("about")), About.groups("1.22.1").last().links.map { it.url })
        assertEquals("https://tmplayer.org/support/?utm_source=about&utm_medium=app", About.supportUrl("about"))
        assertTrue(About.groups("1.22.1", support = false).none { it.support })
        try {
            SupportReminder.enabled = false
            assertTrue(About.groups("1.22.1").none { it.support })
        } finally {
            SupportReminder.enabled = true
        }
    }

    @Test
    fun `a supporter is thanked in the heading`() {
        val group = About.groups("1.22.1", supporter = true).last()
        assertTrue(group.support)
        assertEquals(About.SUPPORT_THANKS_TITLE, group.title)
        assertEquals(About.SUPPORT_THANKS_NOTE, group.note)
    }
}

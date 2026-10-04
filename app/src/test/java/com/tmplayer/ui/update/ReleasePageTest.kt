package com.tmplayer.ui.update

import com.tmplayer.data.Release
import com.tmplayer.data.UpdateFeed
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the release page link points at, and what the TV's QR pane prints under the code. */
class ReleasePageTest {

    @Test
    fun `an offered release points at its own page`() {
        val release = Release(version = "1.21.0", pageUrl = "https://github.com/dracu-lah/TMPlayer/releases/tag/v1.21.0")
        assertEquals(release.pageUrl, releasePageUrl(release))
    }

    @Test
    fun `nothing on offer points at the list of releases`() {
        assertEquals(UpdateFeed.RELEASES_PAGE, releasePageUrl(null))
        assertEquals(UpdateFeed.RELEASES_PAGE, releasePageUrl(Release(version = "1.21.0", pageUrl = "")))
    }

    @Test
    fun `the address is printed without its scheme`() {
        assertEquals("github.com/dracu-lah/TMPlayer/releases", readableUrl("https://github.com/dracu-lah/TMPlayer/releases"))
    }
}

package com.tmplayer.ui.components

import com.tmplayer.data.NameInitial
import com.tmplayer.ui.about.About
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service list (HX93): every service has a bundled official mark or an initial to wear, only
 * services whose brand rules were read wear a mark, the list matches the web app's, and the places
 * that name a service in About carry one.
 */
class ServiceRegistryTest {

    @Test
    fun `every service has a bundled logo or an initial fallback`() {
        for (service in Service.entries) {
            val logo = service.logo
            if (logo != null) {
                assertTrue("${service.label} logo has no size", logo.defaultWidth.value > 0f && logo.defaultHeight.value > 0f)
            } else {
                assertNotNull("${service.label} has no letter to draw", NameInitial.of(service.label))
            }
        }
    }

    @Test
    fun `only TMDB wears a logo until another service's brand rules are read`() {
        assertEquals(listOf(Service.Tmdb), Service.entries.filter { it.logo != null })
        // Telegram's API terms (2.4) forbid its logo: an initial, always.
        assertNull(Service.Telegram.logo)
    }

    @Test
    fun `the TMDB logo keeps the shape the file has`() {
        val logo = Service.Tmdb.logo!!
        assertEquals(190.24f, logo.viewportWidth, 0.001f)
        assertEquals(81.52f, logo.viewportHeight, 0.001f)
        assertEquals(ServiceLogos.TMDB_SQUARE_ASPECT, logo.viewportWidth / logo.viewportHeight, 0.001f)
    }

    @Test
    fun `names are unique and found again`() {
        assertEquals(Service.entries.size, Service.entries.map { it.label.lowercase() }.toSet().size)
        assertEquals(Service.OpenSubtitles, Service.named("OpenSubtitles.com"))
        assertEquals(Service.TvMaze, Service.named(" tvmaze "))
        assertNull(Service.named("Nobody"))
        assertNull(Service.named(null))
    }

    @Test
    fun `the web app lists the same services in the same order`() {
        val file = listOf("../webapp/src/ui/services.ts", "webapp/src/ui/services.ts").map(::File).firstOrNull { it.exists() }
            ?: return // a checkout without the web app has nothing to compare with
        val labels = Regex("""label: '([^']+)', logo: (null|'[a-z-]+')""").findAll(file.readText())
            .map { it.groupValues[1] to (it.groupValues[2] != "null") }
            .toList()
        assertEquals(Service.entries.map { it.label to (it.logo != null) }, labels)
    }

    @Test
    fun `About names its services with a tile`() {
        val links = About.groups("1.22.1", online = true, metadata = true).flatMap { it.links }
        val byTitle = links.associateBy { it.title }
        assertEquals(Service.OpenSubtitles, byTitle.getValue("OpenSubtitles.com").service)
        assertEquals(Service.SubDl, byTitle.getValue("SubDL").service)
        assertEquals(Service.Tmdb, byTitle.getValue("TMDB").service)
        assertEquals(Service.TvMaze, byTitle.getValue("TVmaze").service)
        assertEquals(Service.AniList, byTitle.getValue("AniList").service)
        assertEquals(Service.Sentry, byTitle.getValue("Sentry").service)
        // The notice TMDB's terms require stays with its group.
        assertTrue(About.groups("1.22.1", metadata = true).any { it.tmdbLogo && it.note?.contains("TMDB") == true })
    }
}

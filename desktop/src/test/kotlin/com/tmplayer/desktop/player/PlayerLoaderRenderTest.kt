package com.tmplayer.desktop.player

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.ui.player.LoaderContent
import com.tmplayer.ui.player.LoaderWords
import com.tmplayer.ui.player.PlayerLoader
import com.tmplayer.ui.player.loaderWords
import com.tmplayer.ui.theme.TmMaterialTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pre-roll loader at the sizes it has to hold together on: a phone either way up, a 1080p
 * television, and desktop windows from small to a maximised 1440p, with the promo build's sample
 * art and without any. The pictures land in build/loader-shots for a look; the assertions only
 * check that something was drawn and that the words come out as the brief says.
 */
class PlayerLoaderRenderTest {

    private val words = LoaderWords(
        title = "Big Buck Bunny",
        line = "2008  ·  10m",
        overview = "A large and lovable rabbit deals with three tiny bullies, led by a flying squirrel, " +
            "who are determined to squelch his happiness.",
    )

    private fun art(name: String) =
        SkImage.makeFromEncoded(File(DEMO_DIR, "$name.webp").readBytes()).toComposeImageBitmap()

    @Test
    fun loaderAtEverySize() {
        val backdrop = art("promo_bbb_backdrop")
        val poster = art("promo_bbb_poster")
        val full = LoaderContent(words, backdrop, poster)
        val bare = LoaderContent(words.copy(overview = ""))
        val sizes = listOf(
            Shot("phone-portrait", 1080, 2400, 2.625f, tv = false, insets = PaddingValues(top = 32.dp, bottom = 24.dp)),
            Shot("phone-landscape", 2400, 1080, 2.625f, tv = false, insets = PaddingValues(start = 32.dp, bottom = 16.dp)),
            Shot("tv-1080p", 1920, 1080, 2f, tv = true),
            Shot("tv-4k", 3840, 2160, 4f, tv = true),
            Shot("desktop-small", 960, 600, 1f, tv = false),
            Shot("desktop-1080p", 1920, 1080, 1f, tv = false),
            Shot("desktop-1440p", 2560, 1440, 1f, tv = false),
        )
        for (shot in sizes) {
            for ((suffix, content) in listOf("" to full, "-noart" to bare)) {
                val png = render(shot, content)
                assertTrue("${shot.name}$suffix drew nothing", png.size > 5_000)
                File(OUT).apply { mkdirs() }.resolve("${shot.name}$suffix.png").writeBytes(png)
            }
        }
    }

    @Test
    fun wordsWithoutAMatchComeFromTheName() {
        val episode = loaderWords(null, "Loki", "S01E04", 2021, "47m", "Loki 2021 S01E04 1080p.mkv")
        assertEquals("Loki", episode.title)
        assertEquals("S01E04", episode.line)
        val film = loaderWords(null, "Big Buck Bunny", null, 2008, "10m", "bbb.mp4")
        assertEquals("2008  ·  10m", film.line)
        assertEquals("", film.overview)
        val nothing = loaderWords(null, "", null, null, "", "clip.mp4")
        assertEquals("clip.mp4", nothing.title)
    }

    private data class Shot(
        val name: String,
        val width: Int,
        val height: Int,
        val density: Float,
        val tv: Boolean,
        val insets: PaddingValues = PaddingValues(0.dp),
    )

    private fun render(shot: Shot, content: LoaderContent): ByteArray =
        ImageComposeScene(shot.width, shot.height, Density(shot.density)) {
            TmMaterialTheme(dark = true) {
                PlayerLoader(
                    content = content,
                    progress = 0.42f,
                    status = "Resuming from 13:54",
                    note = "Caching  ·  160 KB/s",
                    tv = shot.tv,
                    motion = false,
                    insets = shot.insets,
                )
            }
        }.use { scene ->
            scene.render(0)
            scene.render(1_000_000_000)
            scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private companion object {
        val DEMO_DIR = File("../app/src/promo/res/drawable-nodpi")
        const val OUT = "build/loader-shots"
    }
}

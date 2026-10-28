package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
import com.tmplayer.online.MetaProvider
import com.tmplayer.ui.online.TitleArt
import com.tmplayer.ui.online.prefetchTitleLogo
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The title logo slot in each of its states (HX15), in both themes, at a carousel's slot and a
 * title page's: the placeholder while the logo is on its way, the logo, the name where TMDB has
 * none, and the name at once with lookups off (no placeholder ever). Pictures go to
 * `$TMPLAYER_SHOTS` when it is set.
 */
class TitleLogoShotsTest {

    private val bunny = MetaInfo(MetaProvider.Tmdb, "10378", MetaKind.Film, "Big Buck Bunny", 2008)
    private val harbour = MetaInfo(MetaProvider.Tmdb, "7002", MetaKind.Show, "Harbour Lights", 2023)

    @Test
    fun everyState() {
        val states = listOf("loading", "logo", "nologo", "off")
        for (state in states) {
            val gate = if (state == "loading") CompletableDeferred<Unit>() else null
            val online = MetaFixture.install(if (state == "off") "off" else "on", gate)
            val meta = if (state == "nologo") harbour else bunny
            if (state == "logo" || state == "nologo") runBlocking { prefetchTitleLogo(meta) }
            try {
                for (dark in listOf(true, false)) {
                    val theme = if (dark) "dark" else "light"
                    save("title-logo-$state-carousel-$theme.png", render(520, 200, dark) {
                        TitleArt(meta, maxWidth = 420.dp, maxHeight = 120.dp, settled = true) { Name(meta.title) }
                    })
                    save("title-logo-$state-page-$theme.png", render(520, 200, dark) {
                        Box(Modifier.height(150.dp), contentAlignment = Alignment.BottomStart) {
                            TitleArt(meta, maxWidth = 380.dp, maxHeight = 120.dp, settled = true, skeletonHeight = 120.dp * 0.62f) {
                                Name(meta.title)
                            }
                        }
                    })
                }
            } finally {
                gate?.complete(Unit)
                com.tmplayer.online.OnlineMetadata.current = null
            }
        }
    }

    /**
     * A poster card's one score at its foot: IMDb's with its badge where the extras are kept on the
     * device, nothing at all for a title whose extras are not (no placeholder on cards).
     */
    @Test
    fun cardScore() {
        val online = MetaFixture.install("on")
        try {
            runBlocking { online.extras(bunny) }
            val art = org.jetbrains.skia.Image.makeFromEncoded(File("../app/src/promo/res/drawable-nodpi/promo_bbb_poster.webp").readBytes())
                .toComposeImageBitmap()
            for (dark in listOf(true, false)) {
                save("card-score-${if (dark) "dark" else "light"}.png", render(360, 280, dark) {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (meta in listOf(bunny, harbour)) {
                            Box(Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(12.dp))) {
                                androidx.compose.foundation.Image(art, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                com.tmplayer.ui.online.CardScore(meta, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                            }
                        }
                    }
                })
            }
        } finally {
            com.tmplayer.online.OnlineMetadata.current = null
        }
    }

    @Composable
    private fun Name(title: String) {
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = Tone.text, maxLines = 2)
    }

    private fun render(width: Int, height: Int, dark: Boolean, content: @Composable () -> Unit): ByteArray =
        ImageComposeScene(width, height, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Bottom) { content() }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(400)
            scene.render(400_000_000)
            Thread.sleep(200)
            // Inside the 2.5 s ceiling, so a logo still on its way is still its placeholder.
            scene.render(1_200_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 300)
        val dir = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(dir).apply { mkdirs() }.resolve(name).writeBytes(png)
    }
}

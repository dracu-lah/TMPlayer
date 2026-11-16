package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.use
import com.tmplayer.data.FreeUpReview
import com.tmplayer.data.FreeUpReview.Group
import com.tmplayer.data.FreeUpReview.Item
import com.tmplayer.data.FreeUpReview.Lock
import com.tmplayer.data.FreeUpReview.Section
import com.tmplayer.data.StorageChart
import com.tmplayer.ui.components.FreeUpReviewPanel
import com.tmplayer.ui.components.LowStorageOffer
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Free up space's review as the desktop draws it, light and dark, in a wide window and a narrow
 * one (the list scrolls between the heading and the buttons), as it opens (caches ticked,
 * downloads not), part way through a choice with two downloads ticked, the question that then
 * comes before anything is deleted, and nothing left to free. Also the overview card it opens
 * from. PNGs under `build/device-test/review/` for checking by eye.
 */
class FreeUpReviewRenderTest {

    private val mb = 1024L * 1024
    private val gb = 1024 * mb

    private val sections = listOf(
        Section(
            Group.Unfinished, 1_500 * mb,
            items = listOf(
                Item("41", "Harbour Lights S01E04", 300 * mb),
                Item("42", "Mountain railway, the full ride 1080p", 600 * mb, Lock.Downloading),
            ),
        ),
        Section(
            Group.Recent, 1_280 * mb, count = 2,
            items = listOf(Item("31", "Night market walk 1080p", 900 * mb), Item("32", "Lecture 8, graphs", 380 * mb, Lock.Playing)),
        ),
        Section(Group.Pictures, 46 * mb),
        Section(
            Group.Downloads, 6_267 * mb,
            items = listOf(
                Item("24", "Coast walk at dawn, a long and slow video from the north shore 2160p", 3_400 * mb),
                Item("23", "Harbour Lights S01E03", 845 * mb),
                Item("21", "Harbour Lights S01E01", 812 * mb),
                Item("22", "Harbour Lights S01E02", 790 * mb),
                Item("25", "Lecture 7, sorting algorithms", 420 * mb),
            ),
        ),
    )

    private val figures = StorageChart.Figures(
        downloads = 6_267 * mb, unfinished = 900 * mb, recent = 1_280 * mb, unmatched = 0, pictures = 46 * mb,
        deviceTotal = 256 * gb, deviceFree = 9 * gb,
    )

    @Test
    fun reviewSheet() {
        val twoTicked = FreeUpReview.Picks(
            FreeUpReview.initial(sections).ids + FreeUpReview.id(Group.Downloads, "24") + FreeUpReview.id(Group.Downloads, "23"),
        )
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            for ((w, h) in listOf(1280 to 800, 420 to 640)) {
                val size = "${w}x$h"
                save("free-up-review-$size-$theme.png", render(w, h, dark) { Sheet(lowStorage = w < 1000) })
                save("free-up-review-$size-$theme-downloads.png", render(w, h, dark) { Sheet(picks = twoTicked) })
                save("free-up-review-$size-$theme-confirm.png", render(w, h, dark) { Sheet(picks = twoTicked, confirming = true) })
            }
            save("free-up-review-420x640-$theme-nothing.png", render(420, 640, dark) { Sheet(sections = emptyList()) })
            save("free-up-overview-420x320-$theme.png", render(420, 320, dark) { Overview(result = "Freed 2.1 GB. 1 item was left because it is in use") })
            save("free-up-overview-420x320-$theme-nothing.png", render(420, 320, dark) { Overview(nothing = true) })
        }
    }

    @Composable
    private fun Sheet(
        sections: List<Section> = this.sections,
        picks: FreeUpReview.Picks? = null,
        confirming: Boolean = false,
        lowStorage: Boolean = false,
    ) {
        // The dialog's window, as the app lays it: the panel centred over the dimmed page.
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)), contentAlignment = Alignment.Center) {
            FreeUpReviewPanel(
                sections = sections,
                onFree = {},
                onDismiss = {},
                confirming = confirming,
                startPicks = picks,
                lowStorage = if (lowStorage) LowStorageOffer(64 * mb, on = false) {} else null,
                modifier = Modifier.width(min(maxWidth - 32.dp, 640.dp)).heightIn(max = maxHeight - if (maxHeight < 480.dp) 16.dp else 48.dp),
            )
        }
    }

    @Composable
    private fun Overview(nothing: Boolean = false, result: String? = null) {
        Column(Modifier.padding(16.dp).widthIn(max = 560.dp)) {
            StorageCard(
                figures = StorageFigures(
                    downloadsBytes = if (nothing) 0 else figures.downloads, downloadCount = 5,
                    cachedBytes = if (nothing) 0 else figures.recent, cachedCount = 2,
                    picturesBytes = if (nothing) 0 else figures.pictures, drives = emptyList(),
                    unfinishedBytes = if (nothing) 0 else figures.unfinished, unfinishedCount = 2,
                    diskTotalBytes = figures.deviceTotal, diskFreeBytes = figures.deviceFree,
                ),
                nothingToFree = nothing,
                result = result,
            ) {}
        }
    }

    private fun render(width: Int, height: Int, dark: Boolean, content: @Composable () -> Unit): ByteArray =
        ImageComposeScene(width, height, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }.use { scene ->
            scene.render(0)
            scene.render(500_000_000)
            scene.render(1_500_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = File("../build/device-test/review").absoluteFile.apply { mkdirs() }
        out.resolve(name).writeBytes(png)
    }
}

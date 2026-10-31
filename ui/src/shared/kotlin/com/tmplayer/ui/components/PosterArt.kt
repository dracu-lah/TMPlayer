package com.tmplayer.ui.components

import com.tmplayer.ui.theme.tileFootScrim
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.Thumbnails
import com.tmplayer.ui.online.MetaPicture
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Scrims
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The picture on a film's or a show's 2:3 poster card, by the best thing there is:
 *
 * 1. the online poster, when posters and overviews are on and the providers know the title (one
 *    already on this device spares the Telegram thumbnail's download);
 * 2. a portrait Telegram thumbnail, which fills the card as it is;
 * 3. a landscape thumbnail, cropped to fill the card same as a portrait one. No name is set on
 *    it: every card that wears this art writes the name under it, and the same words on the
 *    picture as well read twice (user, 2026-10-21);
 * 4. the file's [GeneratedCover], when there is no picture at all. That one keeps the name the
 *    caller's [cover] carries, since the cover is the picture there: a gradient with nothing on it
 *    reads as a card that failed to load. A caller that writes the name beside a small poster
 *    hands a cover with a blank title.
 *
 * The caller clips and sizes it; [posterUrl] is the online poster's address, or null.
 */
@Composable
fun PosterArt(
    miniThumbnail: ByteArray?,
    thumbnailFileId: Int,
    cover: Cover,
    posterUrl: String?,
    modifier: Modifier = Modifier,
    compactCover: Boolean = false,
    /** False when the caller writes the name beside or under the poster: see [MediaArt]. */
    showCoverTitle: Boolean = true,
    /**
     * True while the lookup that may give [posterUrl] is still on its way. A television waits for
     * it before fetching the thumbnail, which a poster would only hide.
     */
    posterPending: Boolean = false,
) {
    // The inline preview first, off the composition thread, then the thumbnail when it arrives.
    // Decoding: nothing drawn yet, so the colour cover is not flashed for a frame under a preview
    // about to land. [NoPreview] once there is none to decode.
    var full by remember(thumbnailFileId) { mutableStateOf(Thumbnails.peekFull(thumbnailFileId)) }
    var mini by remember {
        mutableStateOf<Any?>(if (miniThumbnail == null || miniThumbnail.isEmpty()) NoPreview else null)
    }
    // The bytes [mini] was decoded from. TDLib hands an equal but fresh array with every update of
    // the message, and the effect restarts on the array itself: the same preview is not decoded and
    // hashed again for it, and none is decoded while the sharp thumbnail covers it.
    val miniSource = remember { PreviewSource() }
    LaunchedEffect(miniThumbnail) {
        val bytes = miniThumbnail
        if (bytes == null || bytes.isEmpty()) {
            if (mini == null) mini = NoPreview
            return@LaunchedEffect
        }
        val decoded = mini != null && mini !== NoPreview
        if (decoded && miniSource.bytes?.contentEquals(bytes) == true) return@LaunchedEffect
        if (full != null) return@LaunchedEffect
        val picture = withContext(Dispatchers.Default) { Thumbnails.mini(bytes) }
        miniSource.bytes = bytes
        mini = picture ?: NoPreview
    }
    // On a television the poster alone is fetched whenever there is one: the thumbnail's download
    // and decode under it cost a stick as much again for a picture nobody sees. The preview holds
    // the card meanwhile, and the thumbnail is fetched only when the poster cannot be had.
    val tv = Motion.television
    var posterFailed by remember(posterUrl) { mutableStateOf(false) }
    LaunchedEffect(thumbnailFileId, posterUrl, posterPending, posterFailed) {
        if (full != null) return@LaunchedEffect
        // The provider's poster comes first: when it is on this device already, it is what the
        // card shows, and the video's own thumbnail is not fetched from Telegram at all.
        val online = com.tmplayer.online.OnlineMetadata.current
        if (tv && online != null && !posterFailed && (posterUrl != null || posterPending)) return@LaunchedEffect
        if (posterUrl != null && online != null && withContext(Dispatchers.IO) { online.hasImage(posterUrl) }) return@LaunchedEffect
        full = Thumbnails.full(thumbnailFileId)
    }
    val picture: Any? = full ?: mini
    // On a television, once the online poster is fully drawn it covers the card edge to edge: the
    // surface and the preview under it are then left out, rather than drawn (and kept decoded)
    // for nothing on every frame. Known at once for a poster already in memory.
    var covered by remember(posterUrl) {
        mutableStateOf(tv && posterUrl != null && Thumbnails.peekSharp(posterUrl, POSTER_WIDTH, POSTER_WIDTH) != null)
    }
    Box(if (covered) modifier else modifier.background(Tone.surface)) {
        // Each better picture fades in over the one before ([FadeOver]), never through the surface.
        if (picture != null && !covered) FadeOver(picture, Modifier.fillMaxSize()) { shown ->
            when {
                shown !is ImageBitmap -> GeneratedCover(cover, Modifier.fillMaxSize(), compact = compactCover, showTitle = showCoverTitle)
                shown.height >= shown.width -> Image(
                    shown,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = Thumbnails.drawFilter,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> WidePicture(shown)
            }
        }
        MetaPicture(
            posterUrl,
            Modifier.fillMaxSize(),
            maxWidth = POSTER_WIDTH,
            onShown = if (tv) ({ covered = true }) else null,
            onFailed = if (tv) ({ posterFailed = true }) else null,
        )
    }
}

/** A landscape picture cropped to fill a tall card, same as a portrait one. */
@Composable
private fun WidePicture(picture: ImageBitmap) {
    Box(Modifier.fillMaxSize()) {
        Image(
            picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = Thumbnails.drawFilter,
            modifier = Modifier.fillMaxSize(),
        )
        // The foot darkened as on every tile, for the progress line and a badge there.
        Box(Modifier.fillMaxSize().tileFootScrim())
    }
}

/** The online poster's decode width: a card is at most about 180 dp wide. */
private const val POSTER_WIDTH = 342

/** No inline preview to show: the generated cover stands in. */
private object NoPreview

/** The inline preview's bytes last decoded, held outside snapshot state: only compared, never drawn. */
private class PreviewSource {
    var bytes: ByteArray? = null
}

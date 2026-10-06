package com.tmplayer.ui.online

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Thumbnails
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult
import com.tmplayer.online.MetaWords
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Posters and overviews in Compose, shared by the phone, the television and the desktop. Every
 * piece here draws nothing at all while lookups are off, before an answer arrives, or when nothing
 * matched, so whatever it stands in front of (the video's own thumbnail, the caption) is what
 * shows in all of those cases.
 */

/**
 * True where a video tile may trade its thumbnail for the show's or film's picture: Home's rows.
 * A chat's own grid keeps the frames, since a season of episodes all wearing one backdrop could
 * not be told apart.
 */
val LocalOnlineArt = compositionLocalOf { false }

/**
 * What the providers say about the video named [fileName], or null. [showOnly] asks for the show
 * alone, which is what a tile needs, and shares one lookup between all of a show's episodes.
 */
@Composable
fun rememberMeta(fileName: String, caption: String?, showOnly: Boolean = false): MetaInfo? {
    val online = OnlineMetadata.current ?: return null
    val settings by online.settings.collectAsState()
    if (!settings.enabled) return null
    val language by Translator.active.collectAsState()
    val query = remember(fileName, caption, showOnly) {
        MetaQuery.of(fileName, caption)?.let { if (showOnly) it.showOnly() else it }
    } ?: return null
    val known = remember(query, language.tag, settings) { (online.peek(query) as? MetaResult.Found)?.info }
    val found by produceState(known, query, language.tag, settings.ownKey) {
        if (value == null) value = (withContext(Dispatchers.IO) { online.lookup(query) } as? MetaResult.Found)?.info
    }
    return found
}

/** [rememberMeta] for a Telegram video: its file name, else its title, and its caption. */
@Composable
fun rememberMeta(item: MediaItem, showOnly: Boolean = false): MetaInfo? =
    rememberMeta(item.fileName.ifBlank { item.title }, item.caption, showOnly)

/** The picture at [url], once it is on the disk and decoded; nothing until then. */
@Composable
fun MetaPicture(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    maxWidth: Int = 480,
    contentDescription: String? = null,
) {
    if (url == null) return
    val online = OnlineMetadata.current ?: return
    val bitmap: ImageBitmap? by produceState(Thumbnails.peekOnline(url, maxWidth), url, maxWidth) {
        if (value == null) value = Thumbnails.online(url, maxWidth) { online.image(url) }
    }
    bitmap?.let {
        Image(it, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale, alignment = alignment)
    }
}

/**
 * A video tile's picture: [content] (the thumbnail, as a rule) with the show's or film's wide
 * picture over it once it arrives, where [LocalOnlineArt] allows it. Never the poster: every tile
 * is 16:9, and the poster belongs to the detail panel.
 */
@Composable
fun OnlineArt(item: MediaItem, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!LocalOnlineArt.current) {
        Box(modifier) { content() }
        return
    }
    val meta = rememberMeta(item, showOnly = true)
    Box(modifier) {
        content()
        // The wide picture only. A 2:3 poster cropped into a 16:9 tile is a band across its middle,
        // so without a wide one the tile keeps the video's own frame.
        MetaPicture(meta?.wideUrl, Modifier.fillMaxSize())
    }
}

/** "From TMDB" (or TVmaze, with its licence) under the words it credits. */
@Composable
fun MetaCredit(info: MetaInfo, modifier: Modifier = Modifier) {
    Text(
        MetaWords.credit(info.provider),
        style = MaterialTheme.typography.labelSmall,
        color = Tone.muted,
        modifier = modifier,
    )
}

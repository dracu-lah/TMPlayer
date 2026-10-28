package com.tmplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.MediaName
import com.tmplayer.data.Thumbnails
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.Cover
import com.tmplayer.ui.components.CoverArt
import com.tmplayer.ui.components.GeneratedCover
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.online.TitleLogo
import com.tmplayer.ui.online.TitleLogoImage
import com.tmplayer.ui.online.rememberCachedTitleLogo
import com.tmplayer.ui.online.MetaScoresRow
import com.tmplayer.online.MetaExtras
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The short pre-roll shown while a video opens, on the phone, the television and the desktop.
 *
 * A calm wait rather than a status report: the show's or film's own backdrop from the online
 * metadata, full bleed and drifting very slowly, its title logo (only one already decoded on this
 * device, never waited for) or its clean name, one line of facts under it (year, genres, length,
 * or the episode), the score sites' marks and scores when they are kept here, the overview when
 * there is one, and one thin progress line with one small status line under it.
 * The raw file name, the size, "Loading..." and the bare speed are gone from the main view; the
 * speed comes back, small and muted, only when the wait is slow (the caller decides when).
 *
 * Every size is taken from the screen rather than fixed, so a phone held either way, a 1080p or
 * 4K television and a desktop window from small to maximised all come out composed. The only
 * colours are the theme's: surface under the scrims, on surface and on surface variant for the
 * words, the theme's primary for the line.
 */

/** The words the loader shows. [line] is "S01E04 · The Nexus Event" or "2021 · Drama, Crime · 2h 12m". */
@Immutable
data class LoaderWords(
    val title: String,
    val line: String = "",
    val overview: String = "",
)

/**
 * The words and the two pictures, as [rememberLoaderContent] resolves them. [cover] is the
 * video's [GeneratedCover], the backdrop of last resort: drawn under [backdrop] while it is still
 * on its way, and alone for a video with no picture at all, so the wait never sits on a plain
 * surface. Null leaves the theme's tonal ground instead.
 */
@Immutable
data class LoaderContent(
    val words: LoaderWords,
    val backdrop: ImageBitmap? = null,
    val poster: ImageBitmap? = null,
    val cover: Cover? = null,
    /** The match, for the provider of its scores. */
    val meta: MetaInfo? = null,
    /** The match's facts and scores as kept on this device; null when none are. */
    val extras: MetaExtras? = null,
    /** The title logo, only one already decoded on this device; the name in type without. */
    val logo: TitleLogo? = null,
)

/** A wide TMDB picture at a size fit for [screenWidthPx]: w780 up to a phone or a 1080p stick, w1280 past that. */
fun backdropUrlFor(url: String, screenWidthPx: Int): String =
    if (screenWidthPx > 1400 && url.contains("image.tmdb.org") && url.contains("/w780/")) {
        url.replace("/w780/", "/w1280/")
    } else {
        url
    }

/**
 * What the loader shows for the video named [fileName]: the providers' title, line, overview and
 * pictures where online metadata is on and matched, else the name parsed from the file and
 * [thumbnail] (the video's own frame, which the caller passes only when it is a real frame, never
 * a channel logo). Text is there in the first frame; the pictures arrive when they are decoded.
 *
 * [screenWidthPx] sizes the backdrop decode: never larger than the screen, so a 1 GB television
 * holds one modest bitmap and not a 1280 wide one it would only scale down.
 */
@Composable
fun rememberLoaderContent(
    fileName: String,
    caption: String?,
    fallbackTitle: String,
    durationSec: Int,
    thumbnail: ImageBitmap?,
    screenWidthPx: Int,
    chatId: Long = 0L,
): LoaderContent {
    val s = LocalStrings.current
    val parsed = remember(fileName, caption) { MediaName.parse(fileName.ifBlank { fallbackTitle }, caption) }
    val meta = rememberMeta(fileName.ifBlank { fallbackTitle }, caption)
    val extras = rememberStoredExtras(meta)
    val words = remember(meta, extras, parsed, durationSec, fallbackTitle, s) {
        loaderWords(meta, parsed.title, parsed.episodeCode, parsed.year, s.formatter.duration(durationSec.toLong()), fallbackTitle, extras?.genres.orEmpty())
    }
    val backdropUrl = (meta?.backdropUrl ?: meta?.episode?.stillUrl)?.let { backdropUrlFor(it, screenWidthPx) }
    val backdrop = rememberOnlinePicture(backdropUrl, screenWidthPx.coerceIn(320, 1280))
    val poster = rememberOnlinePicture(meta?.posterUrl, POSTER_DECODE_WIDTH)
    val cover = remember(chatId, fileName, caption, fallbackTitle) {
        CoverArt.of(chatId, fileName.ifBlank { fallbackTitle }, caption, fallbackTitle = fallbackTitle)
    }
    val logo = rememberCachedTitleLogo(meta)
    return LoaderContent(words, backdrop ?: thumbnail, poster, cover, meta, extras, logo)
}

/**
 * [meta]'s facts and scores from memory, else from the disk once read, never the network: the
 * loader is gone before a lookup would answer. What a detail page or a card has kept is here.
 */
@Composable
private fun rememberStoredExtras(meta: MetaInfo?): MetaExtras? {
    meta ?: return null
    val online = OnlineMetadata.current ?: return null
    val settings by online.settings.collectAsState()
    if (!settings.enabled) return null
    val stored by produceState(online.peekExtras(meta), meta) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { online.storedExtras(meta) }.getOrNull() }
    }
    return stored
}

/** The loader's words from a match, or from the parsed name when there is none. Pure, for the tests. */
fun loaderWords(
    meta: MetaInfo?,
    parsedTitle: String,
    episodeCode: String?,
    year: Int?,
    duration: String,
    fallbackTitle: String,
    genres: List<String> = emptyList(),
): LoaderWords {
    val title = meta?.title?.takeIf { it.isNotBlank() }
        ?: parsedTitle.takeIf { it.isNotBlank() }
        ?: fallbackTitle
    val episodeName = meta?.episode?.name?.takeIf { it.isNotBlank() }
    val line = if (episodeCode != null) {
        listOfNotNull(episodeCode, episodeName).joinToString(DOT)
    } else {
        listOfNotNull(
            (meta?.year ?: year)?.toString(),
            genres.take(LINE_GENRES).joinToString(", ").ifBlank { null },
            duration.takeIf { it.isNotBlank() },
        ).joinToString(DOT)
    }
    val overview = meta?.episode?.overview?.takeIf { it.isNotBlank() } ?: meta?.overview.orEmpty()
    return LoaderWords(title, line, overview.trim())
}

@Composable
private fun rememberOnlinePicture(url: String?, maxWidth: Int): ImageBitmap? {
    if (url == null) return null
    val online = OnlineMetadata.current ?: return null
    val settings by online.settings.collectAsState()
    if (!settings.enabled) return null
    val bitmap by produceState(Thumbnails.peekOnline(url, maxWidth), url, maxWidth) {
        if (value == null) value = Thumbnails.online(url, maxWidth) { online.image(url) }
    }
    return bitmap
}

/**
 * The loader itself.
 *
 * @param progress how far the wait has come, 0 to 1, drawn as one thin determinate line.
 * @param status the one small status line: "Resuming from 13:54", "Starting", or what is wrong.
 * @param note the slow wait's speed ("Caching · 160 KB/s"), muted beside [status]; null for none.
 * @param tv a television: overscan safe margins and the television's larger floor for text.
 * @param motion false where the system says no animation: the backdrop stays still.
 * @param insets the system bars and cutout, which pad the words and never the picture.
 */
@Composable
fun PlayerLoader(
    content: LoaderContent,
    progress: Float,
    status: String,
    note: String? = null,
    tv: Boolean = false,
    motion: Boolean = true,
    insets: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxSize().background(scheme.surface)) {
        val w = maxWidth
        val h = maxHeight
        val portrait = h > w * 1.15f
        val direction = LocalLayoutDirection.current
        val side = when {
            tv -> 48.dp
            w >= 1200.dp -> 32.dp
            w >= 720.dp -> 24.dp
            else -> 16.dp
        }
        val vertical = if (tv) 27.dp else side
        val start = side + insets.calculateStartPadding(direction)
        val end = side + insets.calculateEndPadding(direction)
        val bottom = vertical + insets.calculateBottomPadding()

        // Type from the screen: the short side for a landscape screen, the width for an upright one.
        val titleSp = if (portrait) {
            (w.value * 0.068f).coerceIn(22f, 34f)
        } else {
            (h.value * 0.068f).coerceIn(if (tv) 32f else 24f, 72f)
        }
        val bodySp = (titleSp * 0.47f).coerceIn(if (tv) 16f else 14f, 24f)
        val lineSp = (bodySp * 1.12f).coerceAtMost(26f)
        val labelSp = (bodySp * 0.9f).coerceAtLeast(if (tv) 14f else 12f)

        val art = content.backdrop
        val cover = content.cover
        val pictured = art != null || cover != null
        if (!pictured) {
            TonalBackdrop()
        } else if (portrait) {
            Box(Modifier.fillMaxWidth().height(h * ART_SHARE)) {
                Picture(art, cover, motion)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to scheme.surface.copy(alpha = 0.45f),
                            0.25f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to scheme.surface,
                        ),
                    ),
                )
            }
        } else {
            Picture(art, cover, motion)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        // Eased over many stops so the fade has no edge to see.
                        0f to scheme.surface.copy(alpha = 0.94f),
                        0.2f to scheme.surface.copy(alpha = 0.9f),
                        0.35f to scheme.surface.copy(alpha = 0.78f),
                        0.5f to scheme.surface.copy(alpha = 0.58f),
                        0.62f to scheme.surface.copy(alpha = 0.36f),
                        0.74f to scheme.surface.copy(alpha = 0.17f),
                        0.86f to scheme.surface.copy(alpha = 0.05f),
                        1f to Color.Transparent,
                    ),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1f to scheme.surface.copy(alpha = 0.96f),
                    ),
                ),
            )
        }

        // A short dark band under the status bar, so its white icons read over a bright sky
        // whether the bars are showing or not; the picture below it is untouched.
        if (pictured) {
            val band = maxOf(insets.calculateTopPadding(), 24.dp) + 56.dp
            Box(
                Modifier.fillMaxWidth().height(band).background(
                    Brush.verticalGradient(
                        0f to scheme.surface.copy(alpha = 0.7f),
                        0.5f to scheme.surface.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    ),
                ),
            )
        }

        val words = content.words
        // One rhythm for the words, from the body size: a small step inside a group (title to
        // line to scores), a larger one between groups (the overview, then the progress).
        val step = (bodySp * 0.5f).dp
        val gap = (bodySp * 1.1f).dp
        val head: @Composable (Dp, Int) -> Unit = { logoWidth, titleLines ->
            // Read out as the name whether the logo or the type is drawn.
            Box(Modifier.clearAndSetSemantics { heading(); contentDescription = words.title }) {
                val logo = content.logo
                if (logo != null) {
                    TitleLogoImage(logo, maxWidth = logoWidth, maxHeight = (titleSp * LOGO_LINES).dp)
                } else {
                    Title(words.title, titleSp, maxLines = titleLines)
                }
            }
            // Two lines where an upright phone is too narrow for year, genres and length in one.
            Line(words.line, lineSp, Modifier.padding(top = step), maxLines = if (portrait) 2 else 1)
            content.extras?.let { extras ->
                MetaScoresRow(
                    extras,
                    Modifier.padding(top = step),
                    maxLines = 1,
                    limit = SCORES_SHOWN,
                    provider = content.meta?.provider ?: com.tmplayer.online.MetaProvider.Tmdb,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = labelSp.sp, lineHeight = (labelSp * 1.4f).sp),
                )
            }
        }
        if (portrait) {
            // Laid out from the bottom up: the words take the height they need and the backdrop
            // keeps everything above them, so a short overview leaves more picture, not a gap.
            val posterWidth = (w * 0.3f).coerceAtMost(180.dp)
            val posterHeight = posterWidth * 1.5f
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = start, end = end, bottom = bottom),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    content.poster?.let { poster ->
                        Poster(poster, Modifier.width(posterWidth).height(posterHeight))
                        Spacer(Modifier.width(16.dp))
                    }
                    Column(Modifier.weight(1f)) { head(w * 0.6f, 3) }
                }
                Overview(words.overview, bodySp, maxLines = 4, Modifier.padding(top = gap))
                Progress(progress, status, note, labelSp, Modifier.fillMaxWidth().padding(top = gap * 1.4f))
            }
        } else {
            // A measure of about 64 characters for the overview, and never more than 60 per cent
            // of the screen, so the backdrop keeps the rest.
            val measure = minOf(w - start - end, (bodySp * 32f).dp, w * 0.6f)
            val posterHeight = (h * 0.34f).coerceAtMost(300.dp)
            val posterWidth = posterHeight / 1.5f
            // The poster and the words share one foot, the overview's last line, and the progress
            // runs under both from the poster's edge, so the block reads as one piece.
            Column(Modifier.align(Alignment.BottomStart).padding(start = start, end = end, bottom = bottom)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    content.poster?.let { poster ->
                        Poster(poster, Modifier.width(posterWidth).height(posterHeight))
                        Spacer(Modifier.width(side))
                    }
                    Column(Modifier.widthIn(max = measure)) {
                        head(measure * 0.8f, 2)
                        Overview(words.overview, bodySp, maxLines = 3, Modifier.padding(top = gap))
                    }
                }
                val track = (if (content.poster != null) posterWidth + side + measure else measure).coerceAtMost(w - start - end)
                Progress(progress, status, note, labelSp, Modifier.width(track).padding(top = gap * 1.4f))
            }
        }
    }
}

/** No picture to show: a calm surface with a faint lift of the theme's colour, never a blurred logo. */
@Composable
private fun TonalBackdrop() {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(
                0f to scheme.surfaceContainerHigh,
                0.55f to scheme.surface,
                1f to scheme.surfaceContainerLowest,
            ),
        ),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                listOf(scheme.primary.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(0f, 0f),
                radius = 1400f,
            ),
        ),
    )
}

/**
 * The picture behind the words: the [cover] as the ground, still, and the [art] fading in over
 * it when there is one, so a backdrop that arrives late replaces the cover rather than the bare
 * surface.
 */
@Composable
private fun Picture(art: ImageBitmap?, cover: Cover?, motion: Boolean) {
    cover?.let { GeneratedCover(it, Modifier.fillMaxSize(), showTitle = false) }
    art?.let { Backdrop(it, motion) }
}

/** The backdrop: faded in when it arrives, then a slow push in (1.00 to 1.06 over 12 s) unless motion is off. */
@Composable
private fun Backdrop(bitmap: ImageBitmap, motion: Boolean) {
    val alpha = remember(bitmap) { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(bitmap) { alpha.animateTo(1f, tween(FADE_MS)) }
    LaunchedEffect(motion) {
        if (motion) scale.animateTo(DRIFT_SCALE, tween(DRIFT_MS, easing = LinearEasing)) else scale.snapTo(1f)
    }
    Image(
        bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            this.alpha = alpha.value
            scaleX = scale.value
            scaleY = scale.value
            transformOrigin = TransformOrigin(0.65f, 0.4f)
        },
    )
}

@Composable
private fun Poster(bitmap: ImageBitmap, modifier: Modifier) {
    val alpha = remember(bitmap) { Animatable(0f) }
    LaunchedEffect(bitmap) { alpha.animateTo(1f, tween(FADE_MS)) }
    Image(
        bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer { this.alpha = alpha.value }.clip(MaterialTheme.shapes.medium),
    )
}

@Composable
private fun Title(text: String, sizeSp: Float, maxLines: Int) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontSize = sizeSp.sp,
            lineHeight = (sizeSp * 1.18f).sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Line(text: String, sizeSp: Float, modifier: Modifier = Modifier, maxLines: Int = 1) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.35f).sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun Overview(text: String, sizeSp: Float, maxLines: Int, modifier: Modifier = Modifier) {
    AnimatedVisibility(text.isNotBlank(), modifier, enter = fadeIn(tween(FADE_MS))) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.45f).sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One thin determinate line, and the one small status line under it. */
@Composable
private fun Progress(progress: Float, status: String, note: String?, labelSp: Float, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400))
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(scheme.onSurface.copy(alpha = 0.16f), cornerRadius = r)
            if (shown > 0f) drawRoundRect(scheme.primary, size = Size(size.width * shown, size.height), cornerRadius = r)
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = MaterialTheme.typography.labelLarge.copy(fontSize = labelSp.sp, lineHeight = (labelSp * 1.4f).sp)
            Text(status, style = style, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!note.isNullOrBlank()) {
                Spacer(Modifier.size(width = 12.dp, height = 1.dp))
                Text(
                    note,
                    style = style.copy(fontWeight = FontWeight.Normal),
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val DOT = "  ·  "
private const val LINE_GENRES = 2
private const val SCORES_SHOWN = 3

/** A title logo is at most this many title lines tall. */
private const val LOGO_LINES = 2.4f
private const val ART_SHARE = 0.72f
private const val FADE_MS = 600
private const val DRIFT_MS = 12_000
private const val DRIFT_SCALE = 1.06f
private const val POSTER_DECODE_WIDTH = 342

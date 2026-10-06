package com.tmplayer.ui.online

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.i18n.Messages
import com.tmplayer.i18n.Translator
import com.tmplayer.online.KnownMedia
import com.tmplayer.online.KnownTitle
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
import com.tmplayer.online.MetaPerson
import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The detail page's extras in Compose, shared by the phone, the television and the desktop: the
 * facts line, the cast, the trailer and "More like this". Like the rest of this package, each
 * piece draws nothing while lookups are off, before the answer arrives, or when the provider had
 * nothing, so the page is the page it was without them.
 *
 * Memory on a 1 GB television: the cast is ten faces at most, fetched at TMDB's w185 and decoded
 * at the size they are drawn; none is fetched until its row is on screen, and the lazy row only
 * composes the faces in view. "More like this" is worked out only once its place is on screen.
 */

/** The extras for [info], once asked for; null while off, before they arrive, or with no match. */
@Composable
fun rememberMetaExtras(info: MetaInfo?): MetaExtras? {
    info ?: return null
    val online = OnlineMetadata.current ?: return null
    val settings by online.settings.collectAsState()
    if (!settings.enabled) return null
    val language by Translator.active.collectAsState()
    val known = remember(info.provider, info.kind, info.id, language.tag) { online.peekExtras(info) }
    val extras by produceState(known, info.provider, info.kind, info.id, language.tag) {
        if (value == null) value = withContext(Dispatchers.IO) { online.extras(info) }
    }
    return extras
}

/** "Drama, Crime  ·  56 min  ·  5 seasons  ·  TV-MA  ·  ★ 9.0", or null with nothing to say. */
fun metaFactsLine(extras: MetaExtras, s: Messages): String? = listOfNotNull(
    extras.genres.take(3).joinToString(", ").ifBlank { null },
    extras.runtimeMin?.let { s.formatter.duration(it * 60L) },
    extras.seasons?.let { s.metadataSeasons(it) },
    extras.certification,
    extras.rating?.let { "★ " + s.formatter.decimal(it, 1) },
).joinToString("  ·  ").ifBlank { null }

/** "Directed by …" for a film, "Created by …" for a show, or null. */
fun metaPeopleLine(extras: MetaExtras, s: Messages): String? = when {
    extras.directors.isNotEmpty() -> s.metadataDirectedBy(extras.directors.joinToString(", "))
    extras.creators.isNotEmpty() -> s.metadataCreatedBy(extras.creators.joinToString(", "))
    else -> null
}

/** The facts line and the director or creators under it. */
@Composable
fun MetaFactsLines(extras: MetaExtras?, modifier: Modifier = Modifier, maxLines: Int = 2) {
    extras ?: return
    val s = LocalStrings.current
    val facts = metaFactsLine(extras, s)
    val people = metaPeopleLine(extras, s)
    if (facts == null && people == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        facts?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.text,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    contentDescription = it.replace("★ ", "") + (extras.rating?.let { r -> ". " + s.metadataRating(s.formatter.decimal(r, 1)) } ?: "")
                },
            )
        }
        people?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis) }
    }
}

// ---- the trailer ------------------------------------------------------------------------------

/**
 * Opens trailers: in the app's own player where the platform has one ([playing], drawn by
 * [TrailerHost]), else in the YouTube app or the browser. Remembers the one nothing on this device
 * could open, so its QR code can be shown in its place (a television without YouTube).
 */
@Stable
class TrailerLauncher internal constructor(
    private val opener: (MetaTrailer) -> Boolean,
    private val inApp: Boolean,
) {
    var unopened by mutableStateOf<MetaTrailer?>(null)
        private set

    /** The trailer playing inside the app, if any. */
    var playing by mutableStateOf<MetaTrailer?>(null)
        private set

    fun open(trailer: MetaTrailer) {
        if (inApp) {
            unopened = null
            playing = trailer
        } else {
            openElsewhere(trailer)
        }
    }

    /** YouTube's own app or the browser, for a trailer that will not play embedded. */
    fun openElsewhere(trailer: MetaTrailer) {
        playing = null
        unopened = if (opener(trailer)) null else trailer
    }

    fun close() {
        playing = null
    }
}

@Composable
fun rememberTrailerLauncher(): TrailerLauncher {
    val opener = rememberTrailerOpener()
    val inApp = rememberInAppTrailers()
    return remember(opener, inApp) { TrailerLauncher(opener, inApp) }
}

/** The in-app trailer player, while [launcher] has one playing. Place it once beside the trailer button. */
@Composable
fun TrailerHost(launcher: TrailerLauncher) {
    val trailer = launcher.playing ?: return
    TrailerPlayer(trailer, onClose = launcher::close, onOpenElsewhere = { launcher.openElsewhere(trailer) })
}

/** The trailer's YouTube link as a QR code on a white plate, with a sentence saying what it is for. */
@Composable
fun TrailerQr(trailer: MetaTrailer, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, trailer.key) {
        value = withContext(Dispatchers.Default) { QrCode.render(trailer.watchUrl, QR_PIXELS) }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(QR_SIZE).clip(RoundedCornerShape(Corner.Medium)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let { Image(it, contentDescription = s.metadataTrailerQr, modifier = Modifier.fillMaxSize().padding(8.dp)) }
        }
        Text(s.metadataTrailerScan, style = MaterialTheme.typography.bodyMedium, color = Tone.text, modifier = Modifier.weight(1f))
    }
}

/** A film reel, Material's "movie" glyph, for the trailer's button. Not in Material's core icon set. */
val TrailerIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Movie", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = PathParser().parsePathString(
                "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
}

/** "Watch trailer" as an outlined pill, for the series page's row beside the play button. */
@Composable
fun TrailerPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Row(
        modifier
            .height(if (tv) 48.dp else 40.dp)
            .clip(CircleShape)
            .background(if (focused) Tone.focusFill else Color.Transparent)
            .border(1.dp, if (focused) Color.Transparent else Tone.muted.copy(alpha = 0.6f), CircleShape)
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ink = if (focused) Tone.onFocusFill else Tone.text
        Icon(TrailerIcon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(s.metadataTrailer, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

// ---- the cast ---------------------------------------------------------------------------------

/** True once any of this has been on screen, and from then on: the gate for fetching what it shows. */
private fun Modifier.onceOnScreen(seen: Boolean, onSeen: () -> Unit): Modifier =
    if (seen) this else onGloballyPositioned { if (!it.boundsInWindow().isEmpty) onSeen() }

/** A heading, then the cast as a row of faces with their names and parts. Nothing without a cast. */
@Composable
fun MetaCastRow(cast: List<MetaPerson>, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    if (cast.isEmpty()) return
    val s = LocalStrings.current
    val tv = !isTouch()
    var seen by remember { mutableStateOf(false) }
    Column(modifier.onceOnScreen(seen) { seen = true }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.metadataCast, style = MaterialTheme.typography.titleSmall, color = Tone.text, modifier = Modifier.padding(contentPadding.startOnly()))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp),
            // Room for a focused face to grow without its edge being cut off.
            contentPadding = PaddingValues(
                start = contentPadding.startOnly().calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + if (tv) 6.dp else 0.dp,
                end = 6.dp,
                top = if (tv) 6.dp else 0.dp,
                bottom = if (tv) 6.dp else 0.dp,
            ),
        ) {
            items(cast) { person -> PersonCard(person, loadPhoto = seen) }
        }
    }
}

@Composable
private fun PersonCard(person: MetaPerson, loadPhoto: Boolean) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val face = if (tv) FACE_TV else FACE
    var enlarged by remember { mutableStateOf(false) }
    if (enlarged) PersonEnlarged(person) { enlarged = false }
    Column(
        Modifier
            .width(face + 16.dp)
            // A face is small in a row; a press shows it large, with the name and the part.
            .clickable(
                interactionSource = interactions,
                indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                onClick = { enlarged = true },
            )
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(face)
                .clip(CircleShape)
                .background(Tone.surfaceHigh)
                .border(if (focused) Focus.Edge else 0.dp, if (focused) Tone.accent else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(person.name), style = MaterialTheme.typography.titleMedium, color = Tone.muted)
            if (loadPhoto) MetaPicture(person.photoUrl, Modifier.fillMaxSize(), maxWidth = FACE_PIXELS)
        }
        Text(
            person.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) Tone.accent else Tone.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (person.role.isNotBlank()) {
            Text(
                person.role,
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One of the cast, as large as the screen allows: the photo in its own proportions, the name and
 * the part under it, Close at the top end. Back, a tap outside or Close puts it away.
 */
@Composable
private fun PersonEnlarged(person: MetaPerson, onClose: () -> Unit) {
    val tv = !isTouch()
    val close = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            val photoHeight = (maxHeight * 0.7f).coerceAtMost(maxWidth * 1.2f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .height(photoHeight)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(Corner.Large))
                        .background(Tone.surfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initials(person.name), style = MaterialTheme.typography.displayMedium, color = Tone.muted)
                    MetaPicture(person.photoUrl, Modifier.fillMaxSize(), maxWidth = ENLARGED_PIXELS)
                }
                Text(person.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                if (person.role.isNotBlank()) {
                    Text(person.role, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
            }
            val interactions = remember { MutableInteractionSource() }
            val focused by interactions.collectIsFocusedAsState()
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(if (tv) 32.dp else 16.dp)
                    .size(if (tv) 48.dp else 40.dp)
                    .clip(CircleShape)
                    .background(if (focused) Tone.focusFill else Color.White.copy(alpha = 0.15f))
                    .focusRequester(close)
                    .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.Close,
                    contentDescription = LocalStrings.current.commonClose,
                    tint = if (focused) Tone.onFocusFill else Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (tv) LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
    }
}

private fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.let { listOfNotNull(it.firstOrNull(), it.drop(1).lastOrNull()) }
        .joinToString("") { it.take(1).uppercase() }

// ---- more like this ---------------------------------------------------------------------------

/**
 * What the provider recommends for [self], narrowed to the videos the viewer has in their chats,
 * as a row of posters; [onOpen] opens one's detail. Nothing when none of them is here, or when
 * there is nowhere to open them.
 */
@Composable
fun MoreLikeThisRow(
    self: MetaInfo,
    extras: MetaExtras,
    onOpen: ((MediaItem) -> Unit)?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (onOpen == null || extras.similar.isEmpty()) return
    val online = OnlineMetadata.current ?: return
    val s = LocalStrings.current
    val tv = !isTouch()
    var seen by remember { mutableStateOf(false) }
    val titles by produceState(emptyList<KnownTitle>(), self.id, extras, seen) {
        if (seen) value = withContext(Dispatchers.IO) { online.moreLikeThis(self, extras, KnownMedia.all()) }
    }
    if (titles.isEmpty()) {
        // A place holder one pixel tall, so being scrolled to is what starts the work.
        if (!seen) Spacer(modifier.fillMaxWidth().height(1.dp).onceOnScreen(seen) { seen = true })
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.metadataMoreLikeThis, style = MaterialTheme.typography.titleSmall, color = Tone.text, modifier = Modifier.padding(contentPadding.startOnly()))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp),
            contentPadding = PaddingValues(
                start = contentPadding.startOnly().calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + if (tv) 6.dp else 0.dp,
                end = 6.dp,
                top = if (tv) 6.dp else 0.dp,
                bottom = if (tv) 6.dp else 0.dp,
            ),
        ) {
            items(titles, key = { it.item.id }) { known -> KnownPoster(known, onClick = { onOpen(known.item) }) }
        }
    }
}

@Composable
private fun KnownPoster(known: KnownTitle, onClick: () -> Unit) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val width = if (tv) POSTER_TV else POSTER
    val shape = RoundedCornerShape(Corner.Small)
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(Tone.surfaceHigh)
                .border(if (focused) Focus.Edge else 0.dp, if (focused) Tone.accent else Color.Transparent, shape)
                .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick),
        ) {
            MediaArt(known.item.miniThumbnail, known.item.thumbnailFileId, Modifier.fillMaxSize()) {}
            MetaPicture(known.info.posterUrl, Modifier.fillMaxSize(), maxWidth = 240)
        }
        Text(
            known.info.title + (known.info.year?.takeIf { known.info.kind == MetaKind.Film }?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) Tone.accent else Tone.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun PaddingValues.startOnly(): PaddingValues =
    PaddingValues(start = calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))

private val FACE: Dp = 64.dp
private val FACE_TV: Dp = 72.dp

/** A face is drawn at most ~72 dp, so about 160 px decoded is plenty on any screen here. */
private const val FACE_PIXELS = 160

/** The enlarged photo: sharp at its size on a 1080p screen. */
private const val ENLARGED_PIXELS = 780

private val POSTER: Dp = 92.dp
private val POSTER_TV: Dp = 100.dp
private val QR_SIZE: Dp = 132.dp
private const val QR_PIXELS = 360

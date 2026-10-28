package com.tmplayer.ui.online

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.NameInitial
import com.tmplayer.i18n.Messages
import com.tmplayer.i18n.Translator
import com.tmplayer.online.KnownMedia
import com.tmplayer.online.KnownTitle
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
import com.tmplayer.online.MetaPerson
import com.tmplayer.online.MetaProvider
import com.tmplayer.online.withProviderScore
import androidx.compose.ui.text.font.FontWeight
import com.tmplayer.online.MetaScore
import com.tmplayer.online.ScoreSource
import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
fun rememberMetaExtras(info: MetaInfo?): MetaExtras? = rememberMetaExtrasState(info, infoSettled = true).value

/**
 * [rememberMetaExtras], with whether the answer is in ([MetaState.settled]). Without [info] they
 * are settled once [infoSettled] is (no match, so no extras to come). An answer kept from before
 * the scores were asked for is the value at once and not settled until the scores have been asked.
 */
@Composable
fun rememberMetaExtrasState(info: MetaInfo?, infoSettled: Boolean): MetaState<MetaExtras> {
    info ?: return MetaState(null, infoSettled)
    val online = OnlineMetadata.current ?: return MetaState(null, true)
    val settings by online.settings.collectAsState()
    if (!settings.enabled) return MetaState(null, true)
    val language by Translator.active.collectAsState()
    val known = remember(info.provider, info.kind, info.id, language.tag) { online.peekExtras(info) }
    val done = known?.scoresAsked == true
    val extras by produceState(MetaState(known, done), info.provider, info.kind, info.id, language.tag) {
        value = MetaState(known, done)
        // An answer kept from before the scores were asked for is shown at once, then refreshed with them.
        if (!done) value = MetaState(withContext(Dispatchers.IO) { runCatching { online.extras(info) }.getOrNull() } ?: known, true)
    }
    return extras
}

/**
 * "Drama, Crime  ·  56 min  ·  5 seasons  ·  TV-MA", or null with nothing to say. The scores are
 * not in it: [MetaScoresRow] draws them under it, each with its site's mark. A page whose own
 * facts line already says the length or the number of seasons leaves them out here
 * ([runtime], [seasons] false), so neither is said twice.
 */
fun metaFactsLine(extras: MetaExtras, s: Messages, runtime: Boolean = true, seasons: Boolean = true): String? = listOfNotNull(
    extras.genres.take(3).joinToString(", ").ifBlank { null },
    extras.runtimeMin?.takeIf { runtime }?.let { s.formatter.duration(it * 60L) },
    extras.seasons?.takeIf { seasons }?.let { s.metadataSeasons(it) },
    extras.certification,
).joinToString("  ·  ").ifBlank { null }

/** "Directed by …" for a film, "Created by …" for a show, or null. */
fun metaPeopleLine(extras: MetaExtras, s: Messages): String? = when {
    extras.directors.isNotEmpty() -> s.metadataDirectedBy(extras.directors.joinToString(", "))
    extras.creators.isNotEmpty() -> s.metadataCreatedBy(extras.creators.joinToString(", "))
    else -> null
}

/**
 * The facts line, the scores under it, and the director or creators. [runtime] false leaves the
 * length out, for a page that has already said the file's own.
 */
@Composable
fun MetaFactsLines(extras: MetaExtras?, modifier: Modifier = Modifier, maxLines: Int = 2, runtime: Boolean = true) {
    extras ?: return
    val s = LocalStrings.current
    val facts = metaFactsLine(extras, s, runtime = runtime)
    val people = metaPeopleLine(extras, s)
    val scored = extras.scores.isNotEmpty() || extras.rating != null
    if (facts == null && people == null && !scored) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        facts?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis) }
        if (scored) MetaScoresRow(extras, maxLines = maxLines)
        people?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * Each site's score with that site's own mark: IMDb's badge then "7.8", Rotten Tomatoes' tomato
 * (or splat, under 60) then "92%", Metacritic's roundel then "78" on its band's colour, and so on,
 * wrapping to another line when narrow, no more than [maxLines]. [limit] keeps only the first few
 * (the Home carousel's one line), [centered] centres them under centred text. An answer kept from
 * before the scores were asked for has only the provider's rating: it is shown as [provider]'s own
 * score, with that site's mark, like any other.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaScoresRow(
    extras: MetaExtras,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    limit: Int = Int.MAX_VALUE,
    centered: Boolean = false,
    provider: MetaProvider = MetaProvider.Tmdb,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val scores = remember(extras, provider) { extras.withProviderScore(provider).scores }
    if (scores.isEmpty()) return
    FlowRow(
        modifier,
        horizontalArrangement = if (centered) Arrangement.spacedBy(SCORE_GAP, Alignment.CenterHorizontally) else Arrangement.spacedBy(SCORE_GAP),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        maxLines = maxLines,
    ) {
        for (score in scores.take(limit)) ScoreItem(score, style)
    }
}

/**
 * A card's one score ([MetaScore.cardPick]) with its site's mark, in a small dark pill for the
 * corner of a poster or a tile: only from what is already on this device
 * ([OnlineMetadata.cachedCardScore]), never a lookup per card, and nothing at all, no placeholder,
 * when none is kept. [meta] is the card's match, or null for none.
 */
@Composable
fun CardScore(meta: MetaInfo?, modifier: Modifier = Modifier, compact: Boolean = false) {
    meta ?: return
    val online = OnlineMetadata.current ?: return
    val settings by com.tmplayer.ui.components.rememberMetaSettings(online)
    if (!settings.enabled) return
    val known = remember(meta) { online.peekCardScore(meta) }
    val score by produceState(known?.score, meta) {
        if (known == null) value = withContext(Dispatchers.IO) { runCatching { online.cachedCardScore(meta) }.getOrNull() }
    }
    val shown = score ?: return
    val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
    Box(
        modifier
            // A rounded fill, not a clip layer per card, as the tiles' other badges.
            .background(com.tmplayer.ui.components.TileBadgeFill, CircleShape)
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 3.dp),
    ) {
        ScoreItem(shown, style, ink = Color.White, logoHeight = if (compact) 11.dp else 12.dp, gap = 4.dp)
    }
}

@Composable
private fun ScoreItem(
    score: MetaScore,
    style: androidx.compose.ui.text.TextStyle,
    ink: Color = Tone.text,
    logoHeight: Dp = LOGO_HEIGHT,
    gap: Dp = 6.dp,
) {
    val s = LocalStrings.current
    val site = scoreSiteName(score.source)
    val whole = score.value.roundToInt()
    val percent = score.source == ScoreSource.RottenTomatoes || score.source == ScoreSource.AniList
    val value = when {
        // percent() rounds down, so half a point more keeps 29 from coming out as 28.
        percent -> s.formatter.percent((whole + 0.5) / 100.0)
        score.outOfHundred -> s.formatter.number(whole)
        else -> s.formatter.decimal(score.value, 1)
    }
    val spoken = when {
        percent -> s.metadataScorePerCent(site = site, score = s.formatter.number(whole))
        score.outOfHundred -> s.metadataScoreOutOfHundred(site = site, score = value)
        else -> s.metadataScoreOutOfTen(site = site, score = value)
    }
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val logo = scoreLogo(score)
        if (logo != null) {
            Image(
                logo,
                contentDescription = null,
                modifier = Modifier.height(if (score.source == ScoreSource.Tmdb) logoHeight * TMDB_SHARE else logoHeight)
                    .aspectRatio(logo.viewportWidth / logo.viewportHeight),
            )
        } else {
            // TVmaze: no vector mark to hand, so its name in its place.
            Text(site, style = style, color = if (ink == Tone.text) Tone.muted else ink.copy(alpha = 0.8f), maxLines = 1)
        }
        if (score.source == ScoreSource.Metacritic) {
            // Metacritic's own way: the number on its band's colour, green, yellow or red.
            Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(metacriticBand(score.value)).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(value, style = style, color = Color(0xFF131313), maxLines = 1)
            }
        } else {
            Text(value, style = style, color = ink, maxLines = 1)
        }
    }
}

/** Brand names stay as the sites write them, in every language. */
private fun scoreSiteName(source: ScoreSource): String = when (source) {
    ScoreSource.Imdb -> "IMDb"
    ScoreSource.RottenTomatoes -> "Rotten Tomatoes"
    ScoreSource.Metacritic -> "Metacritic"
    ScoreSource.Tmdb -> "TMDB"
    ScoreSource.AniList -> "AniList"
    ScoreSource.TvMaze -> "TVmaze"
}

private fun scoreLogo(score: MetaScore): ImageVector? = when (score.source) {
    ScoreSource.Imdb -> ScoreLogos.Imdb
    ScoreSource.RottenTomatoes -> if (score.fresh) ScoreLogos.TomatoFresh else ScoreLogos.TomatoRotten
    ScoreSource.Metacritic -> ScoreLogos.Metacritic
    ScoreSource.Tmdb -> com.tmplayer.ui.about.TmdbLogo
    ScoreSource.AniList -> ScoreLogos.AniList
    ScoreSource.TvMaze -> null
}

/** Metacritic's bands: 61 and over favourable, 40 to 60 mixed, under 40 unfavourable. */
private fun metacriticBand(value: Double): Color = when {
    value >= 61 -> Color(0xFF00CE7A)
    value >= 40 -> Color(0xFFFFBD3F)
    else -> Color(0xFFFF6874)
}

private val LOGO_HEIGHT: Dp = 16.dp

/** TMDB's mark is a long wordmark; at this share of the others' height its letters stand as tall as the text's. */
private const val TMDB_SHARE = 11f / 16f
private val SCORE_GAP: Dp = 14.dp

// ---- the trailer ------------------------------------------------------------------------------

/**
 * Opens trailers. On a television the YouTube app comes first where there is one ([appFirst]):
 * it is made for the remote and plays every trailer, where YouTube's embedded player in a WebView
 * is at its weakest on a TV (no remote keys, codecs the chip decodes badly, trailers not allowed
 * embedded). Otherwise, and on a phone, the app's own player where the platform has one
 * ([playing], drawn by [TrailerHost]), else the YouTube app or the browser. Remembers the one
 * nothing on this device could open, so its QR code can be shown in its place.
 */
@Stable
class TrailerLauncher internal constructor(
    private val opener: (MetaTrailer) -> Boolean,
    private val inApp: Boolean,
    private val appFirst: ((MetaTrailer) -> Boolean)? = null,
) {
    var unopened by mutableStateOf<MetaTrailer?>(null)
        private set

    /** The trailer playing inside the app, if any. */
    var playing by mutableStateOf<MetaTrailer?>(null)
        private set

    fun open(trailer: MetaTrailer) {
        unopened = null
        if (appFirst?.invoke(trailer) == true) return
        if (inApp) {
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

    /** Closes the in-app player. */
    fun close() {
        playing = null
    }
}

@Composable
fun rememberTrailerLauncher(): TrailerLauncher {
    val opener = rememberTrailerOpener()
    val inApp = rememberInAppTrailers()
    val appFirst = rememberTrailerAppFirst()
    return remember(opener, inApp, appFirst) { TrailerLauncher(opener, inApp, appFirst) }
}

/** The in-app trailer player, while [launcher] has one playing. Place it once beside the trailer button. */
@Composable
fun TrailerHost(launcher: TrailerLauncher) {
    val trailer = launcher.playing ?: return
    TrailerPlayer(
        trailer,
        onClose = launcher::close,
        onOpenElsewhere = { launcher.openElsewhere(trailer) },
        fallback = { TrailerQr(trailer, text = LocalStrings.current.metadataTrailerScanPhone) },
    )
}

/** The trailer's YouTube link as a QR code on a white plate, with a sentence saying what it is for. */
@Composable
fun TrailerQr(trailer: MetaTrailer, modifier: Modifier = Modifier, text: String? = null) {
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
        Text(text ?: s.metadataTrailerScan, style = MaterialTheme.typography.bodyMedium, color = Tone.text, modifier = Modifier.weight(1f))
    }
}

/** The trailer's button glyph. */
val TrailerIcon: ImageVector get() = TmIcons.Trailer

/** "Watch trailer" as an outlined pill, for the series page's row beside the play button. */
@Composable
fun TrailerPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
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

/**
 * The cast row's place while the extras are on their way: its heading and a row of soft faces with
 * a line of name under each, about as tall as the row it stands for, so the cast comes in where
 * its place was rather than pushing what is under it down.
 */
@Composable
fun MetaCastPlaceholder(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val face = if (tv) FACE_TV else FACE
    val shimmer = com.tmplayer.ui.components.rememberShimmer()
    Column(modifier.semantics { contentDescription = s.metadataCast }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.metadataCast, style = MaterialTheme.typography.titleSmall, color = Tone.text)
        Row(
            Modifier.padding(start = if (tv) 6.dp else 0.dp, top = if (tv) 6.dp else 0.dp, bottom = if (tv) 6.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp),
        ) {
            repeat(CAST_PLACEHOLDERS) {
                Column(Modifier.width(face + 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.size(face), CircleShape)
                    com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.width(face * 0.8f).height(10.dp))
                    com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.width(face * 0.55f).height(10.dp))
                }
            }
        }
    }
}

/** How many soft faces stand in for the cast. */
private const val CAST_PLACEHOLDERS = 5

@Composable
private fun PersonCard(person: MetaPerson, loadPhoto: Boolean) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
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
            val focused by interactions.collectIsFocusVisibleAsState()
            com.tmplayer.ui.components.IconTip(
                LocalStrings.current.commonClose,
                Modifier.align(Alignment.TopEnd).padding(if (tv) 32.dp else 16.dp),
                side = com.tmplayer.ui.components.TipSide.Below,
            ) {
                Box(
                    Modifier
                        .size(if (tv) 48.dp else 40.dp)
                        .clip(CircleShape)
                        .background(if (focused) Tone.focusFill else Color.White.copy(alpha = 0.15f))
                        .focusRequester(close)
                        .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        TmIcons.Close,
                        contentDescription = LocalStrings.current.commonClose,
                        tint = if (focused) Tone.onFocusFill else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        if (tv) LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
    }
}

private fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.let { listOfNotNull(it.firstOrNull(), it.drop(1).lastOrNull()) }
        .mapNotNull(NameInitial::of).joinToString("")

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
    // Back from a video opened here: the row is wanted at once, to put the focus back on its tile.
    val back = com.tmplayer.ui.browse.LocalDetailReturn.current
    var seen by remember { mutableStateOf(back != null) }
    val titles by produceState(emptyList<KnownTitle>(), self.id, extras, seen) {
        if (seen) value = withContext(Dispatchers.IO) { online.moreLikeThis(self, extras, KnownMedia.all()) }
    }
    if (titles.isEmpty()) {
        // A place holder one pixel tall, so being scrolled to is what starts the work.
        if (!seen) Spacer(modifier.fillMaxWidth().height(1.dp).onceOnScreen(seen) { seen = true })
        return
    }
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val returnFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val returnView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val returnId = back?.toId?.takeIf { id -> titles.any { it.item.id == id } }
    if (back != null && returnId != null) {
        // The tile Back came from: scrolled to in the row, then given the remote's focus (which
        // brings the row into view on the page), or on a touch screen just brought into view.
        LaunchedEffect(back, returnId) {
            if (back.done) return@LaunchedEffect
            rowState.scrollToItem(titles.indexOfFirst { it.item.id == returnId })
            repeat(RETURN_FOCUS_TRIES) {
                androidx.compose.runtime.withFrameNanos { }
                if (back.done) return@LaunchedEffect
                val placed = if (tv) runCatching { returnFocus.requestFocus() }.isSuccess else true
                if (placed && back.claim()) {
                    runCatching { returnView.bringIntoView() }
                    return@LaunchedEffect
                }
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.metadataMoreLikeThis, style = MaterialTheme.typography.titleSmall, color = Tone.text, modifier = Modifier.padding(contentPadding.startOnly()))
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp),
            contentPadding = PaddingValues(
                start = contentPadding.startOnly().calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + if (tv) 6.dp else 0.dp,
                end = 6.dp,
                top = if (tv) 6.dp else 0.dp,
                bottom = if (tv) 6.dp else 0.dp,
            ),
        ) {
            items(titles, key = { it.item.id }) { known ->
                KnownPoster(
                    known,
                    onClick = { onOpen(known.item) },
                    modifier = if (known.item.id == returnId) {
                        Modifier.focusRequester(returnFocus).bringIntoViewRequester(returnView)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun KnownPoster(known: KnownTitle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val width = if (tv) POSTER_TV else POSTER
    val shape = RoundedCornerShape(Corner.Medium)
    Column(Modifier.width(width).then(modifier), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

private val FACE: Dp = 72.dp
private val FACE_TV: Dp = 88.dp

/** A face is drawn at most 88 dp, so about 160 px decoded is plenty for a face that small. */
private const val FACE_PIXELS = 160

/** The enlarged photo: sharp at its size on a 1080p screen. */
private const val ENLARGED_PIXELS = 780

/** Frames the tile Back came from is asked for the focus over, once its row is drawn. */
private const val RETURN_FOCUS_TRIES = 10

private val POSTER: Dp = 112.dp
private val POSTER_TV: Dp = 140.dp
private val QR_SIZE: Dp = 132.dp
private const val QR_PIXELS = 360

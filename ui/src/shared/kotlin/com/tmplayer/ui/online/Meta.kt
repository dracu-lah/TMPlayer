package com.tmplayer.ui.online

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.tmplayer.online.MetaImageSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Thumbnails
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetaEpisode
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
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
    val found = rememberLookup(fileName, caption, showOnly).value ?: return null
    // Only an answer the name backs with a year or an episode code: with no file to measure,
    // the runtime cannot vouch for it here (see [rememberMatched]).
    val name = remember(fileName, caption) { com.tmplayer.data.MediaName.parse(fileName, caption) }
    return found.takeIf { com.tmplayer.online.MetaConfidence.basis(name, it) != null }
}

/**
 * What the providers say about [item], with what backs it ([com.tmplayer.online.MatchBasis]), or
 * null: lookups off, nothing found, a match nothing but the title backs ([OnlineMetadata.matched]),
 * or one the viewer turned down ("Wrong match"). A title the viewer gave the video is what is
 * looked up.
 */
@Composable
fun rememberMatched(item: MediaItem, showOnly: Boolean = false): com.tmplayer.online.MatchedMeta? =
    rememberMatchedState(item, showOnly).value

/**
 * [rememberMatched], with whether its answer is in: a title page holds its places for what is
 * still on its way ([MetaState.settled] false) and leaves out what is known not to come.
 */
@Composable
fun rememberMatchedState(item: MediaItem, showOnly: Boolean = false): MetaState<com.tmplayer.online.MatchedMeta> {
    // This video's own correction only: a rename of another video does not recompose this tile.
    val correction = com.tmplayer.ui.components.rememberCorrection(item.id)
    val renamed = correction?.title
    val lookup = rememberLookup(
        renamed ?: item.nameForParsing,
        if (renamed != null) null else item.caption,
        showOnly,
        // The item's own question, read once per video and mostly already read off the main
        // thread by Home's rows: asked afresh per tile as a TV row scrolled, it held the main thread.
        known = if (renamed == null) item.metaQuery else null,
    )
    val found = lookup.value
    val name = remember(item, renamed) { com.tmplayer.data.SeriesShelf.episodeOf(item, onlyThis(item, correction)) }
    // A verdict on the runtime reached before counts too, so a tile seen before is matched on its
    // first frame rather than drawing its own thumbnail and fading the poster in again.
    // Worked out once per answer and name, not on every recomposition of the tile: both checks
    // compare titles and build a key, which a TV row paid again for each tile on each pass.
    val online = OnlineMetadata.current
    val quick = remember(found, name, item.durationSec, online) { found?.let { info -> online?.peekMatched(item, name, info) } }
    // Whether the runtime still has to be asked about before there is a verdict either way.
    val asks = remember(quick, found, name, item.durationSec) {
        quick == null && found != null && com.tmplayer.online.MetaConfidence.needsRuntime(name, found, item.durationSec)
    }
    val matched by produceState(Verdict(quick, found, !asks), found, name, item.durationSec) {
        // produceState keeps its value across new keys: an answer that arrived after the first
        // frame (always so on the web) would otherwise never be taken.
        value = Verdict(quick, found, !asks)
        if (asks) {
            value = Verdict(withContext(Dispatchers.IO) { OnlineMetadata.current?.matched(item, name, found!!) }, found, true)
        }
    }
    // A verdict for an earlier answer than the one in hand is not one yet.
    val current = matched.takeIf { it.of == found }
    val settled = lookup.settled && current?.done == true
    if (correction?.wrongMatch == true) return MetaState(null, lookup.settled)
    return MetaState(current?.matched?.takeIf { it.info == found }, settled)
}

/** [correction] as the one entry of a corrections map, for what reads [item]'s alone. */
private fun onlyThis(item: MediaItem, correction: com.tmplayer.data.VideoCorrection?): Map<String, com.tmplayer.data.VideoCorrection> =
    if (correction == null) emptyMap() else mapOf(item.id to correction)

/** [rememberMatchedState]'s verdict, the answer it is for, and whether it is final. */
private class Verdict(val matched: com.tmplayer.online.MatchedMeta?, val of: MetaInfo?, val done: Boolean)

/**
 * What a lookup has given so far, and whether that is all: [settled] is false while more may still
 * come (the answer is on its way), true once it is in, nothing included (lookups off, no match).
 */
@androidx.compose.runtime.Immutable
class MetaState<out T>(val value: T?, val settled: Boolean) {
    /** Still on its way: nothing yet, and more may come. */
    val pending: Boolean get() = value == null && !settled
}

/** The provider's answer for a name, before anything checks that it is the right one. */
@Composable
private fun rememberLookup(fileName: String, caption: String?, showOnly: Boolean, known: MetaQuery? = null): MetaState<MetaInfo> {
    val online = OnlineMetadata.current ?: return MetaState(null, true)
    // The screen's own collectors where it provides them ([com.tmplayer.ui.components.LocalTileShared]).
    val settings by com.tmplayer.ui.components.rememberMetaSettings(online)
    if (!settings.enabled) return MetaState(null, true)
    val language by com.tmplayer.ui.components.rememberLanguage()
    val query = remember(fileName, caption, showOnly) {
        (known ?: MetaQuery.of(fileName, caption))?.let { if (showOnly) it.showOnly() else it }
    } ?: return MetaState(null, true)
    // Any answer in memory, "nothing matched" included, is a settled one.
    val peeked = remember(query, language.tag, settings) { online.peek(query) }
    val known = (peeked as? MetaResult.Found)?.info
    val found by produceState(MetaState(known, peeked != null), query, language.tag, settings.ownKey) {
        value = MetaState(known, peeked != null)
        if (known == null) {
            val answer = withContext(Dispatchers.IO) { runCatching { online.lookup(query) }.getOrNull() }
            value = MetaState((answer as? MetaResult.Found)?.info, true)
        }
    }
    return found
}

/**
 * [rememberMeta] for a Telegram video, with whether its answer is in; see [rememberMatchedState].
 */
@Composable
fun rememberMetaState(item: MediaItem, showOnly: Boolean = false): MetaState<MetaInfo> {
    val matched = rememberMatchedState(item, showOnly)
    return MetaState(matched.value?.info, matched.settled)
}

/**
 * Whether [meta]'s title logo is known and decoded (or known not to exist), so a title page's
 * first frame can draw it in place rather than the name first. Fetches and decodes it as
 * [TitleArt] would ([prefetchTitleLogo]); true at once while lookups are off, without a match, or
 * once [metaSettled] says no match is coming.
 */
@Composable
fun rememberTitleLogoReady(meta: MetaInfo?, metaSettled: Boolean): Boolean {
    val online = OnlineMetadata.current ?: return true
    val enabled = online.settings.collectAsState().value.enabled
    if (!enabled) return true
    if (meta == null) return metaSettled
    val known = remember(meta) { logoInMemory(online, meta) }
    val ready by produceState(known, meta) {
        if (value) return@produceState
        runCatching { prefetchTitleLogo(meta) }
        value = true
    }
    return ready
}

/** Whether [meta]'s logo answer is in memory and, if it has one, decoded with its tone known. */
private fun logoInMemory(online: OnlineMetadata, meta: MetaInfo): Boolean {
    val answer = online.peekLogo(meta) ?: return false
    val url = answer.url ?: return true
    return Thumbnails.peekOnline(url, LOGO_PIXELS) != null && LogoTone.known(url) != null
}

/**
 * [rememberMeta] for a Telegram video: its file name (or the title the viewer gave it), and its
 * caption, gated as [rememberMatched] gates it.
 */
@Composable
fun rememberMeta(item: MediaItem, showOnly: Boolean = false): MetaInfo? =
    rememberMatched(item, showOnly)?.info

/**
 * The provider's episode list for [season] of the show [meta], for the names in a series list:
 * null while lookups are off, for a film, before the answer arrives, or when nobody could be
 * asked. Shares [OnlineMetadata.season]'s memory and cache with the detail page's episode guide.
 */
@Composable
fun rememberSeasonEpisodes(meta: MetaInfo?, season: Int): List<MetaEpisode>? {
    val online = OnlineMetadata.current ?: return null
    if (meta == null || meta.kind != MetaKind.Show) return null
    val listed by produceState(online.peekSeason(meta, season), meta, season) {
        if (value == null) value = withContext(Dispatchers.IO) { online.season(meta, season) }
    }
    return listed
}

/**
 * The picture at [url], once it is on the disk and decoded; nothing until then.
 *
 * At the size it is drawn: its width in pixels on the screen (the interface size and the screen's
 * density both in it) picks the provider's copy where the platform can use a larger one
 * ([Thumbnails.sharp], the desktop), so a poster in a wide window is not a small copy stretched.
 * A copy already decoded stays up while a larger one is fetched, so a resize never blanks it.
 */
@Composable
fun MetaPicture(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    maxWidth: Int = 480,
    contentDescription: String? = null,
    /** Told once the picture is fully drawn, so a caller may stop drawing what stands under it. */
    onShown: (() -> Unit)? = null,
    /** Told when the picture could not be had, so a caller holding something back for it may show that. */
    onFailed: (() -> Unit)? = null,
) {
    if (url == null) return
    val online = OnlineMetadata.current ?: return
    var drawn by remember { mutableIntStateOf(0) }
    // Android samples every picture to [maxWidth] whatever it is drawn at, so only the desktop measures.
    val wanted = if (com.tmplayer.data.decodesFullSize) maxOf(maxWidth, drawn) else maxWidth
    var bitmap by remember(url) { mutableStateOf(Thumbnails.peekSharp(url, wanted, maxWidth)) }
    // TMDB's small copy, blurred, while the sharp one comes: the picture is there in one short
    // round trip and comes into focus rather than popping in late (user, 2026-10-21).
    // Not on a television, where the second download and decode per poster held back the rest.
    val tinyUrl = remember(url) { if (com.tmplayer.ui.theme.Motion.television) null else MetaImageSize.tiny(url) }
    var tiny by remember(url) { mutableStateOf(if (bitmap == null && tinyUrl != null) Thumbnails.peekOnline(tinyUrl, TINY_PICTURE_WIDTH) else null) }
    // Already decoded when the tile was composed: shown sharp at once, no fade.
    val reveal = remember(url) { Animatable(if (bitmap != null) 1f else 0f) }
    // No fades on a television, where speed comes before looks.
    val still = com.tmplayer.ui.theme.Motion.television || com.tmplayer.ui.theme.rememberReducedMotion() || com.tmplayer.ui.theme.Motion.reduced
    LaunchedEffect(url, wanted) {
        val have = bitmap
        // Against the width it would be decoded at: on a television (or under Lighter pictures)
        // that is three fifths of [wanted], so comparing with [wanted] itself sent every cached
        // poster back through the loader each time its tile was composed.
        if (have != null && have.width >= Thumbnails.lighter(wanted)) return@LaunchedEffect
        val started = System.nanoTime()
        coroutineScope {
            val early = if (have == null && tiny == null && tinyUrl != null) {
                launch {
                    // Not when the sharp copy is on the disk: it decodes before the small one could land.
                    val onDisk = withContext(Dispatchers.IO) {
                        online.hasImage(url) || online.hasImage(MetaImageSize.atLeast(url, Thumbnails.lighter(wanted)))
                    }
                    if (onDisk) return@launch
                    Thumbnails.online(tinyUrl, TINY_PICTURE_WIDTH) { online.image(tinyUrl) }?.let {
                        if (bitmap == null) tiny = it
                        PicturePerf.note("poster small", started)
                    }
                }
            } else {
                null
            }
            val sharp = Thumbnails.sharp(url, wanted, maxWidth) { online.image(it) }
            sharp?.let {
                early?.cancel()
                bitmap = it
                if (have == null) PicturePerf.note(if (tiny != null) "poster sharp after small" else "poster sharp", started)
            }
            if (sharp == null && bitmap == null) onFailed?.invoke()
        }
    }
    // The small copy fades in over what stands under it (the video's own picture) rather than
    // popping in; one already there when the tile was composed is drawn at once.
    val tinyIn = remember(url) { Animatable(if (tiny != null) 1f else 0f) }
    LaunchedEffect(tiny != null) {
        if (tiny == null || tinyIn.value >= 1f) return@LaunchedEffect
        if (still) tinyIn.snapTo(1f) else tinyIn.animateTo(1f, androidx.compose.animation.core.tween(TINY_FADE_MS))
    }
    val shown = bitmap
    LaunchedEffect(shown != null) {
        if (shown == null || reveal.value >= 1f) return@LaunchedEffect
        if (still) reveal.snapTo(1f) else reveal.animateTo(1f, androidx.compose.animation.core.tween(REVEAL_MS))
    }
    // The animations are read where they are drawn, so a fade redraws the poster rather than
    // composing it again every frame; only its end is read here, once.
    val revealed by remember(url) { derivedStateOf { reveal.value >= 1f } }
    if (onShown != null) {
        LaunchedEffect(url, revealed, shown != null) { if (revealed && shown != null) onShown() }
    }
    Box(modifier.onSizeChanged { drawn = it.width }) {
        val small = tiny
        if (small != null && !revealed) {
            Image(
                small,
                contentDescription = null,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = tinyIn.value }.blur(TINY_BLUR),
                contentScale = contentScale,
                alignment = alignment,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
            )
        }
        if (shown != null) {
            // The sharp copy fades in over the blurred small one, which reads as coming into
            // focus. An eased blur on the sharp copy as well made a new blur per poster per frame.
            Image(
                shown,
                contentDescription = contentDescription,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = reveal.value },
                contentScale = contentScale,
                alignment = alignment,
                filterQuality = Thumbnails.drawFilter,
            )
        }
    }
}

/** The blur on a small copy standing in for the sharp one. Android draws it from API 31; earlier the stretched copy is soft on its own. */
private val TINY_BLUR = 12.dp

/** How wide TMDB's small copies are decoded: `w92` whole, a `w300` still halved. */
private const val TINY_PICTURE_WIDTH = 160

/** How long the small copy takes to fade in over the video's own picture. */
private const val TINY_FADE_MS = 220

/** How long the sharp copy takes to come into focus over the small one. */
private const val REVEAL_MS = 380

/** The first few pictures' timings, for a device pass to read in the log; past that they would only be noise. */
private object PicturePerf {
    private val left = java.util.concurrent.atomic.AtomicInteger(16)

    fun note(what: String, since: Long) {
        if (left.getAndDecrement() > 0) com.tmplayer.data.PerfLog.mark(what, since)
    }
}

/**
 * A video tile's picture: the provider's wide picture for it once it arrives, over [content] (the
 * video's own thumbnail, or its generated cover), which stands until then and wherever the
 * provider has none. Never the poster: every tile is 16:9, and a 2:3 poster cropped into one is a
 * band across its middle.
 *
 * Which picture depends on where the tile is. On Home ([LocalOnlineArt]) it is the show's or the
 * film's backdrop. In a chat's own grid a film wears its backdrop and an episode its own still from
 * the season's list ([rememberSeasonEpisodes], one call a season, kept with the answers), so a
 * season of episodes can still be told apart; an episode the provider has no still for keeps its
 * frame rather than the show's one backdrop on every tile.
 */
@Composable
fun OnlineArt(item: MediaItem, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val home = LocalOnlineArt.current
    val meta = rememberMeta(item, showOnly = true)
    // The episode's number only matters off Home, where an episode wears its own still; Home's
    // tiles skip the parse (and the extra correction reader) altogether. [home] is fixed for a
    // screen, so the branch never changes under a tile.
    val name = if (home) null else {
        val correction = com.tmplayer.ui.components.rememberCorrection(item.id)
        remember(item, correction) { com.tmplayer.data.SeriesShelf.episodeOf(item, onlyThis(item, correction)) }
    }
    val number = name?.episode
    val season = name?.season ?: 1
    val listed = if (!home && number != null) rememberSeasonEpisodes(meta, season) else null
    val url = when {
        meta == null -> null
        home -> meta.wideUrl
        meta.kind == MetaKind.Show -> number?.let { com.tmplayer.online.EpisodeNames.listed(season, it, listed)?.stillUrl }
        else -> meta.backdropUrl
    }
    // On a television the video's own picture is dropped once the online one is fully drawn over
    // it: an opaque picture covers it, and every tile drawing (and keeping decoded) both was the
    // cost of two pictures for the look of one. Already known when the online one is in memory.
    val tv = com.tmplayer.ui.theme.Motion.television
    var covered by remember(url) {
        mutableStateOf(tv && url != null && Thumbnails.peekSharp(url, ONLINE_ART_WIDTH, ONLINE_ART_WIDTH) != null)
    }
    Box(modifier) {
        if (!covered) content()
        MetaPicture(url, Modifier.fillMaxSize(), maxWidth = ONLINE_ART_WIDTH, onShown = if (tv) ({ covered = true }) else null)
    }
}

/** The decode width of a tile's online picture: [MetaPicture]'s own default. */
private const val ONLINE_ART_WIDTH = 480

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

/**
 * A film's or show's name for the Home carousel: its title logo from TMDB where it has one (the
 * name drawn as a picture on a clear ground, [OnlineMetadata.logo]), else [text], the name set in
 * type. The logo is what is waited for: while it may still come the place is held, the name laid
 * out but not drawn, so nothing jumps, and the logo fades in there. The name is shown only once it
 * is clear there is no logo (lookups off, TMDB has none, it could not be had) or after
 * [LOGO_WAIT_MS]; a logo arriving later still takes over from it. A logo already known and
 * decoded (the carousel fetches its pages' logos ahead, [PrefetchTitleLogos]) is there on the
 * first frame. The picture is never the only way to the name: the carousel page carries the title
 * as its description, so a screen reader still says it.
 *
 * @param maxWidth the widest the logo may be; it keeps its own shape inside [maxWidth] by
 *   [maxHeight], set at the start (or the centre, by [alignment]) of that box.
 * @param settled true once it is known whether [meta] is all there will be: with no match then,
 *   the name is set at once rather than its place held for a logo that cannot come.
 * @param waitMs how long the name's place is held for a logo still on its way. A title page sets
 *   0: its title area is already as tall as the logo, so the name stands there at once and the
 *   logo fades in over it in the same place.
 */
@Composable
fun TitleArt(
    meta: MetaInfo?,
    maxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomStart,
    settled: Boolean = false,
    waitMs: Long = LOGO_WAIT_MS,
    text: @Composable () -> Unit,
) {
    val online = OnlineMetadata.current
    val enabled = online?.settings?.collectAsState()?.value?.enabled == true
    var answer by remember(meta, enabled) {
        mutableStateOf(if (meta != null && enabled) online?.peekLogo(meta) else null)
    }
    LaunchedEffect(meta, enabled) {
        if (answer != null || meta == null || online == null || !enabled) return@LaunchedEffect
        answer = com.tmplayer.online.LogoAnswer(withContext(Dispatchers.IO) { runCatching { online.logo(meta) }.getOrNull() })
    }
    val url = answer?.url
    var logo by remember(url) { mutableStateOf(url?.let { Thumbnails.peekOnline(it, LOGO_PIXELS) }) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (url == null || logo != null || online == null) return@LaunchedEffect
        val decoded = Thumbnails.online(url, LOGO_PIXELS, alpha = true) { online.image(url) }
        if (decoded == null) failed = true else logo = decoded
    }
    val shown = logo
    // Logos are drawn for dark grounds, white or near white as a rule, and a white one on a light
    // theme's page would vanish. Such a logo is set in the page's ink there instead, its shape
    // kept; a coloured one (gold, red) reads on either ground and is drawn as it is. This replaced
    // a dark glow behind every logo, which read as a smudge on the light page. The verdict is
    // kept per logo, so a page seen before has it on the first frame.
    var bright by remember(url, shown) { mutableStateOf(url?.let { LogoTone.known(it) }) }
    LaunchedEffect(url, shown) {
        if (url == null || shown == null || bright != null) return@LaunchedEffect
        bright = withContext(Dispatchers.Default) { LogoTone.of(url, shown) }
    }
    // How long the place is held for a logo still on its way, from when the page is first drawn.
    var waited by remember { mutableStateOf(waitMs <= 0L) }
    LaunchedEffect(Unit) {
        if (waited) return@LaunchedEffect
        kotlinx.coroutines.delay(waitMs)
        waited = true
    }
    val ink = Tone.text
    val light = Tone.background.luminance() > 0.5f
    val tint = if (light && bright == true) androidx.compose.ui.graphics.ColorFilter.tint(ink) else null
    val noLogo = online == null || !enabled || failed || (answer != null && url == null) || (meta == null && settled)
    val step: Any = when {
        // On a light page a logo waits the few milliseconds for its verdict, so a white one is
        // never drawn white first.
        shown != null && (!light || bright != null) -> shown
        shown == null && (noLogo || waited) -> TitleStep.Name
        else -> TitleStep.Held
    }
    androidx.compose.animation.Crossfade(targetState = step, animationSpec = com.tmplayer.ui.theme.Motion.standard(), modifier = modifier, label = "titleArt") { picture ->
        when (picture) {
            is ImageBitmap -> {
                val ratio = picture.width.toFloat() / picture.height.coerceAtLeast(1)
                Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
                    Box(Modifier.sizeIn(maxWidth = maxWidth, maxHeight = maxHeight), contentAlignment = alignment) {
                        Image(
                            picture,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            alignment = alignment,
                            filterQuality = Thumbnails.drawFilter,
                            colorFilter = tint,
                            modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = maxWidth / maxHeight > ratio),
                        )
                    }
                }
            }
            TitleStep.Name -> text()
            // The name laid out and not drawn: the place it would take, and nothing a screen reader reads twice.
            else -> Box(Modifier.alpha(0f).clearAndSetSemantics {}) { text() }
        }
    }
}

/** What [TitleArt] shows while it has no logo: the name, or the name's place held for one. */
private enum class TitleStep { Name, Held }

/**
 * Fetches and decodes the title logos of [items] (each a video and whether its show is wanted),
 * one after another from [from] on, so turning to a page shows its logo at once instead of
 * starting the lookup, the download and the decode then; and the next [BACKDROPS_AHEAD] pages'
 * pictures, at the size the carousel draws them ([com.tmplayer.ui.components.prefetchBackdrop]),
 * again whenever the page turns. The carousel calls it with its pages, on every device.
 */
@Composable
fun PrefetchTitleLogos(items: List<Pair<MediaItem, Boolean>>, from: Int = 0) {
    PrefetchBackdrops(items, from)
    val online = OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    if (!settings.enabled || items.isEmpty()) return
    val start by androidx.compose.runtime.rememberUpdatedState(from)
    LaunchedEffect(items) {
        val first = start.coerceIn(0, items.size - 1)
        for (i in items.indices) {
            val (item, show) = items[(first + i) % items.size]
            prefetchTitleLogo(item, show)
        }
    }
}

/**
 * The next [BACKDROPS_AHEAD] carousel pages after [current]'s pictures decoded ahead: their
 * matches looked up as the page would ([metaFor]), then the picture its backdrop draws first.
 * Asked again when the page turns; a turn cancels what was still on its way for the last one.
 */
@Composable
private fun PrefetchBackdrops(items: List<Pair<MediaItem, Boolean>>, current: Int) {
    if (items.size < 2) return
    LaunchedEffect(items, current) {
        // One on a television, whose picture cache is small enough that two wide pictures ahead
        // pushed the cards' thumbnails out of it.
        val lean = com.tmplayer.data.Thumbnails.light || com.tmplayer.ui.theme.Motion.television
        val ahead = minOf(if (lean) 1 else BACKDROPS_AHEAD, items.size - 1)
        for (step in 1..ahead) {
            val (item, show) = items[(current.coerceAtLeast(0) + step) % items.size]
            val info = metaFor(item, show)
            com.tmplayer.ui.components.prefetchBackdrop(info?.wideUrl, item.thumbnailFileId)
        }
    }
}

/** Carousel pages past the one shown whose pictures are decoded ahead; one under Lighter pictures and on a TV. */
private const val BACKDROPS_AHEAD = 2

/**
 * [item]'s match as a tile or a carousel page would find it (the viewer's own name for it first),
 * from memory, the disk or the providers; null when lookups are off or nothing matched.
 */
private suspend fun metaFor(item: MediaItem, showOnly: Boolean): MetaInfo? {
    val online = OnlineMetadata.current ?: return null
    if (!online.settings.value.enabled) return null
    val renamed = com.tmplayer.data.VideoCorrections.state.value[item.id]?.title
    val query = MetaQuery.of(renamed ?: item.nameForParsing, if (renamed != null) null else item.caption)
        ?.let { if (showOnly) it.showOnly() else it } ?: return null
    return (online.peek(query) as? MetaResult.Found)?.info
        ?: (withContext(Dispatchers.IO) { runCatching { online.lookup(query) }.getOrNull() } as? MetaResult.Found)?.info
}

/**
 * [item]'s title logo, looked up, fetched and decoded as [TitleArt] would, so its first frame
 * has it: see [PrefetchTitleLogos]. Nothing when lookups are off or the title has none.
 */
suspend fun prefetchTitleLogo(item: MediaItem, showOnly: Boolean) {
    val info = metaFor(item, showOnly) ?: return
    prefetchTitleLogo(info)
}

/**
 * The keys of [candidates] that make complete Home carousel pages ([HomeFeatured.completeKeys]),
 * worked out once for a fill, the same on every device. A page is complete when its match is the
 * one the page itself will show ([rememberMatched]: the viewer's own name for the video first, a
 * "Wrong match" refused, the provider's runtime asked where the name alone does not vouch for it)
 * and that match has a wide picture and an overview ([HomeFeatured.isComplete]). A "no match"
 * answer drops the page; lookups off, or no metadata in this build, leave none. The lookups run in
 * [scope] off the main thread, at most [HomeFeatured.FILL_WAIT_MS] in all, which also covers the
 * first page's title logo, so the carousel opens on the logo where TMDB has one.
 */
suspend fun completeFeatured(
    candidates: List<com.tmplayer.data.FeaturedPick>,
    scope: kotlinx.coroutines.CoroutineScope,
    limit: Int = com.tmplayer.data.HomeFeatured.LIMIT,
): Set<String> {
    val online = OnlineMetadata.current ?: return emptySet()
    if (!online.settings.value.enabled || candidates.isEmpty()) return emptySet()
    val began = kotlin.time.TimeSource.Monotonic.markNow()
    val corrections = com.tmplayer.data.VideoCorrections.state.value
    val targets = candidates.associate { pick -> pick.key to featuredTarget(pick, corrections[pick.item.id]) }
    fun known(pick: com.tmplayer.data.FeaturedPick): Boolean? {
        val target = targets[pick.key] ?: return false
        if (target.refused) return false
        val query = target.query ?: return false
        return when (val answer = online.cachedAnswer(query)) {
            null, MetaResult.Unavailable -> null
            MetaResult.Off, MetaResult.NoMatch -> false
            is MetaResult.Found -> {
                val info = answer.info
                when {
                    !com.tmplayer.data.HomeFeatured.isComplete(info) -> false
                    online.peekMatched(target.item, target.name, info) != null -> true
                    // The runtime still to be asked about: a lookup decides.
                    com.tmplayer.online.MetaConfidence.needsRuntime(target.name, info, target.item.durationSec) -> null
                    else -> false
                }
            }
        }
    }
    suspend fun ask(pick: com.tmplayer.data.FeaturedPick): Boolean? {
        val target = targets[pick.key] ?: return false
        val query = target.query ?: return false
        val info = when (val answer = online.lookup(query)) {
            is MetaResult.Found -> answer.info
            MetaResult.Unavailable -> return null
            MetaResult.Off, MetaResult.NoMatch -> return false
        }
        if (!com.tmplayer.data.HomeFeatured.isComplete(info)) return false
        return online.matched(target.item, target.name, info) != null
    }
    val keys = withContext(Dispatchers.IO) {
        com.tmplayer.data.HomeFeatured.completeKeys(candidates, ::known, ::ask, scope, Dispatchers.IO, limit = limit)
    }
    // The first page's title logo within what is left of the wait: the page opens on it rather
    // than on its name with the logo fading in a moment later.
    val first = candidates.firstOrNull { it.key in keys }?.let { targets[it.key]?.query }
    val left = com.tmplayer.data.HomeFeatured.FILL_WAIT_MS - began.elapsedNow().inWholeMilliseconds
    if (first != null && left > 0) {
        kotlinx.coroutines.withTimeoutOrNull(left) {
            try {
                withContext(Dispatchers.IO) { online.cachedMatch(first) }?.let { prefetchTitleLogo(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // No logo ahead: the page sets its name, or fetches the logo itself.
            }
        }
    }
    return keys
}

/** A carousel candidate as its page will look it up: the question, the name that checks the answer, and a refused match. */
private class FeaturedTarget(val item: MediaItem, val query: MetaQuery?, val name: com.tmplayer.data.ParsedName, val refused: Boolean)

private fun featuredTarget(pick: com.tmplayer.data.FeaturedPick, correction: com.tmplayer.data.VideoCorrection?): FeaturedTarget {
    val item = pick.item
    val renamed = correction?.title
    val query = (if (renamed == null) item.metaQuery else MetaQuery.of(renamed, null))?.let { if (pick.isShow) it.showOnly() else it }
    val name = com.tmplayer.data.SeriesShelf.episodeOf(item, onlyThis(item, correction))
    return FeaturedTarget(item, query, name, correction?.wrongMatch == true)
}

/** [info]'s title logo fetched and decoded ahead, with its light page verdict: see [prefetchTitleLogo]. */
suspend fun prefetchTitleLogo(info: MetaInfo) {
    val online = OnlineMetadata.current ?: return
    val known = online.peekLogo(info)
    val url = if (known != null) known.url else withContext(Dispatchers.IO) { runCatching { online.logo(info) }.getOrNull() }
    url ?: return
    val picture = Thumbnails.peekOnline(url, LOGO_PIXELS)
        ?: Thumbnails.online(url, LOGO_PIXELS, alpha = true) { online.image(url) }
        ?: return
    if (LogoTone.known(url) == null) withContext(Dispatchers.Default) { LogoTone.of(url, picture) }
}

/** Whether each title logo seen this run is white or near it ([isBrightLogo]), by its address. */
private object LogoTone {
    private val verdicts = com.tmplayer.data.SizedLru<String, Boolean>(LOGO_TONES) { 1 }

    fun known(url: String): Boolean? = verdicts.get(url)

    fun of(url: String, picture: ImageBitmap): Boolean? =
        verdicts.get(url) ?: runCatching { isBrightLogo(picture) }.getOrNull()?.also { verdicts.put(url, it) }
}

/** Logos whose tone [LogoTone] keeps: a byte each, many carousels' worth. */
private const val LOGO_TONES = 512

/**
 * How long a carousel page holds the name's place for its logo before setting the name in type:
 * a logo known on this device is decoded well within it, one fetched in the time a page is read.
 */
private const val LOGO_WAIT_MS = 1_500L

/**
 * Whether a title logo is white or near white: the mean luminance of its solid pixels, sampled,
 * above [BRIGHT_LOGO]. A logo with no solid pixels at all is not.
 */
private fun isBrightLogo(picture: ImageBitmap): Boolean {
    val pixels = IntArray(picture.width * picture.height)
    picture.readPixels(pixels)
    var sum = 0.0
    var weight = 0.0
    var i = 0
    while (i < pixels.size) {
        val c = pixels[i]
        val a = (c ushr 24 and 0xFF) / 255.0
        if (a > 0.5) {
            val r = (c shr 16 and 0xFF) / 255.0
            val g = (c shr 8 and 0xFF) / 255.0
            val b = (c and 0xFF) / 255.0
            sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) * a
            weight += a
        }
        i += LOGO_SAMPLE_STEP
    }
    return weight > 0.0 && sum / weight > BRIGHT_LOGO
}

/** Above this mean luminance a logo is white or near it, and vanishes on a light page. */
private const val BRIGHT_LOGO = 0.72

/** Every this many pixels is read: plenty for an average, and a few milliseconds at most. */
private const val LOGO_SAMPLE_STEP = 7

/** The width a title logo is decoded at: about 300 dp at a television's density. */
private const val LOGO_PIXELS = 500

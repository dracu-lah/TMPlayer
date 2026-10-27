package com.tmplayer.desktop.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.tmplayer.data.StoryboardFiles
import com.tmplayer.data.StoryboardMap
import com.tmplayer.data.StoryboardMaps
import com.tmplayer.data.StoryboardTile
import com.tmplayer.data.Thumbnails
import com.tmplayer.platform.Logger
import com.tmplayer.ui.components.SkeletonBox
import com.tmplayer.ui.components.rememberShimmer
import com.tmplayer.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Telegram's storyboard for the video playing (CP80), for the timebar's hover: the sprite is
 * fetched through TDLib and decoded once, and each tile is drawn straight out of it.
 *
 * The player warms it as the video opens ([warm]), in the player's own lifetime. It used to be
 * fetched only from inside the hover, whose request was cancelled each time the pointer crossed a
 * second of the video, and a cancelled thumbnail fetch withdraws TDLib's download: the sprite
 * rarely landed at all, and the hover showed only the time.
 */
class DesktopStoryboard(
    private val files: StoryboardFiles,
    private val durationMs: () -> Long,
    /** Puts a file of TDLib's on the disk and says where; the thumbnails' fetch unless a test hands another. */
    private val fetch: suspend (Int) -> String? = { Thumbnails.file(it) },
) {

    private val gate = Mutex()
    private val loaded = MutableStateFlow<Pair<ImageBitmap, StoryboardMap>?>(null)
    private var failed = false

    /** Whether the sprite and map are in, for the hover to ask again the moment they land. */
    val ready: StateFlow<Pair<ImageBitmap, StoryboardMap>?> get() = loaded

    /**
     * Fetches the sprite and the map, trying a few times while TDLib cannot say yet (offline, a
     * slow line, a grid map waiting for the length), then stops by itself.
     */
    suspend fun warm() {
        repeat(WARM_TRIES) { attempt ->
            if (load() != null || failed) return
            delay(WARM_RETRY_MS * (attempt + 1))
        }
    }

    /** The sprite and the tile for [positionMs], or null while it cannot be had. */
    suspend fun tileAt(positionMs: Long): Pair<ImageBitmap, StoryboardTile>? {
        // Never waits on a fetch already under way: the hover asks again when [ready] changes.
        val (image, tiles) = loaded.value ?: (if (gate.isLocked) null else load()) ?: return null
        val tile = tiles.tileAt(positionMs) ?: return null
        if (tile.width <= 0 || tile.height <= 0) return null
        if (tile.x + tile.width > image.width || tile.y + tile.height > image.height) return null
        return image to tile
    }

    private suspend fun load(): Pair<ImageBitmap, StoryboardMap>? = gate.withLock {
        loaded.value?.let { return it }
        if (failed) return null
        // Not fetched this time (offline, slow): asked again later.
        val imagePath = fetch(files.imageFileId) ?: return null
        val mapPath = fetch(files.mapFileId)
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(imagePath).readBytes()
                val image = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                val text = mapPath?.let { runCatching { File(it).readText() }.getOrNull() }.orEmpty()
                image to StoryboardMaps.parse(text, files.tileWidth, files.tileHeight, image.width, image.height, durationMs())
            }.onFailure { Logger.w(TAG, "Storyboard could not be read", it) }.getOrNull()
        }
        if (decoded == null || decoded.second.isEmpty) {
            // A map that needs the video's length (the grid) cannot be read before mpv has it: that
            // is asked again, not given up on for the whole video.
            if (durationMs() > 0 || decoded == null) failed = true
            return null
        }
        loaded.value = decoded
        decoded
    }

    private companion object {
        const val TAG = "Storyboard"
        const val WARM_TRIES = 4
        const val WARM_RETRY_MS = 3_000L
    }
}

/** The storyboard of the video playing, for [SeekPreview]; null where it has none. */
internal val LocalStoryboard = staticCompositionLocalOf<DesktopStoryboard?> { null }

/** The video's own picture, for the hover's card when no frame for the moment can be had. */
internal class SeekCover(val thumbnailFileId: Int, val mini: ByteArray?)

internal val LocalSeekCover = staticCompositionLocalOf<SeekCover?> { null }

/** One picture for the hover: [image], and the part of it to draw where it is a storyboard's tile. */
internal class SeekShot(val image: ImageBitmap, val tile: StoryboardTile? = null) {
    /** Width over height of what is drawn, for the letterbox inside the 16:9 card. */
    val aspect: Float
        get() = if (tile != null) tile.width / tile.height.coerceAtLeast(1).toFloat() else image.width / image.height.coerceAtLeast(1).toFloat()
}

/**
 * What the hover's card shows for the moment under the pointer: [shot] (the last picture had,
 * which stays up while the next is fetched), whether it is the picture for this moment
 * ([fresh]), and whether every source has had its chance and none answered ([gaveUp]).
 */
internal class SeekShotState(val shot: SeekShot?, val fresh: Boolean, val gaveUp: Boolean)

/**
 * The picture for [positionMs]: the storyboard's tile first, else the video's own frame from the
 * engine's second decoder. Asked again when the pointer crosses a second of the video, or a source
 * lands something. After [GIVE_UP_MS] without an answer for the moment the card stops shimmering
 * and shows its fallback; a picture that lands later still replaces it.
 */
@Composable
internal fun rememberSeekShot(positionMs: Long): SeekShotState {
    val board = LocalStoryboard.current
    val frames = LocalFrameSource.current
    val boardReady = board?.ready?.collectAsState()?.value != null
    if (frames != null) {
        // The frame decoder's one worker, for as long as the hover is up: leaving the bar stops it.
        LaunchedEffect(frames) { frames.run() }
    }
    var shot by remember { mutableStateOf<SeekShot?>(null) }
    var freshKey by remember { mutableStateOf(-1L) }
    var gaveUpKey by remember { mutableStateOf(-1L) }
    val key = positionMs / TILE_STEP_MS
    LaunchedEffect(key, board, frames, boardReady) {
        val timer = launch {
            delay(GIVE_UP_MS)
            gaveUpKey = key
        }
        val tile = board?.tileAt(positionMs)
        if (tile != null) {
            shot = SeekShot(tile.first, tile.second)
            freshKey = key
            timer.cancel()
            return@LaunchedEffect
        }
        if (frames == null) {
            // Nothing else to ask: the storyboard is still coming, or has no tile here.
            if (board == null || boardReady) gaveUpKey = key
            return@LaunchedEffect
        }
        val at = frames.timeFor(positionMs)
        frames.cached(at)?.let {
            shot = SeekShot(it)
            freshKey = key
            timer.cancel()
            return@LaunchedEffect
        }
        // A short rest before asking, so a sweep across the bar does not queue a decode per second.
        delay(FRAME_REST_MS)
        frames.want(at)
        frames.landed.first { frames.cached(at) != null || frames.declined(at) }
        val image = frames.cached(at)
        if (image != null) {
            shot = SeekShot(image)
            freshKey = key
            timer.cancel()
        } else if (board == null || boardReady) {
            gaveUpKey = key
            timer.cancel()
        }
    }
    return SeekShotState(shot, fresh = freshKey == key && shot != null, gaveUp = gaveUpKey == key && freshKey != key)
}

/** Draws [shot] letterboxed inside the card, the part a storyboard's tile names where it is one. */
@Composable
private fun SeekShotPicture(shot: SeekShot, modifier: Modifier = Modifier) {
    val tile = shot.tile
    Canvas(modifier.fillMaxSize()) {
        val boxAspect = size.width / size.height.coerceAtLeast(1f)
        val aspect = shot.aspect.coerceIn(0.2f, 5f)
        val w: Float
        val h: Float
        if (aspect >= boxAspect) {
            w = size.width
            h = size.width / aspect
        } else {
            h = size.height
            w = size.height * aspect
        }
        val dst = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt())
        val dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
        if (tile != null) {
            drawImage(shot.image, srcOffset = IntOffset(tile.x, tile.y), srcSize = IntSize(tile.width, tile.height), dstOffset = dst, dstSize = dstSize)
        } else {
            drawImage(shot.image, dstOffset = dst, dstSize = dstSize)
        }
    }
}

/**
 * The card's fallback when no frame for the moment can be had: the video's own thumbnail dimmed
 * (or a plain dark field where it has none), with the time large over it.
 */
@Composable
private fun SeekFallback(time: String) {
    val cover = LocalSeekCover.current
    val picture by produceState<ImageBitmap?>(null, cover) {
        if (cover == null) return@produceState
        value = Thumbnails.peekFull(cover.thumbnailFileId)
            ?: runCatching { Thumbnails.mini(cover.mini) }.getOrNull()
        runCatching { Thumbnails.full(cover.thumbnailFileId) }.getOrNull()?.let { value = it }
    }
    Box(Modifier.fillMaxSize().background(FallbackGround), contentAlignment = Alignment.Center) {
        picture?.let { image ->
            Canvas(Modifier.fillMaxSize().alpha(0.4f)) {
                // Cropped to fill: a dimmed backdrop, not the picture itself.
                val scale = maxOf(size.width / image.width.coerceAtLeast(1), size.height / image.height.coerceAtLeast(1))
                val w = image.width * scale
                val h = image.height * scale
                drawImage(
                    image,
                    dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
                    dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                )
            }
        }
        Text(time, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * What hovering or dragging the timebar shows over it: a 16:9 card at a standard size, the
 * skeleton's shimmer until the picture for the moment arrives (Telegram's storyboard, else the
 * video's own frame), the picture fading in, and the time in a chip at its foot. Where no picture
 * answers in time the card shows the video's thumbnail dimmed with the time large; with scrub
 * pictures off it is the time chip alone.
 *
 * Kept whole inside the bar's two ends, centred on the pointer where there is room, and lifted
 * clear of the times above the bar. Placed from the bar's top left (in a right to left layout
 * too: the bar runs left to right), and measured on its own, so it takes no room from the bar.
 */
@Composable
internal fun SeekPreview(
    time: String,
    positionMs: Long,
    pointerX: Float,
    barWidth: Float,
    modifier: Modifier = Modifier,
) {
    val pictures = LocalStoryboard.current != null || LocalFrameSource.current != null
    val density = LocalDensity.current
    val lift = with(density) { PREVIEW_LIFT.roundToPx() }
    Box(
        modifier.layout { measurable, _ ->
            val placeable = measurable.measure(Constraints())
            layout(0, 0) {
                val x = (pointerX - placeable.width / 2f).roundToInt().coerceIn(0, (barWidth.roundToInt() - placeable.width).coerceAtLeast(0))
                placeable.place(x, -(placeable.height + lift))
            }
        },
    ) {
        if (!pictures) {
            Chip(time)
        } else {
            val width = min(CARD_WIDTH, with(density) { barWidth.toDp() }.coerceAtLeast(CARD_MIN_WIDTH))
            SeekCard(time, positionMs, Modifier.requiredSize(width, width * 9f / 16f))
        }
    }
}

@Composable
private fun SeekCard(time: String, positionMs: Long, modifier: Modifier) {
    val state = rememberSeekShot(positionMs)
    val shape = RoundedCornerShape(8.dp)
    val shot = state.shot
    val picture = shot?.takeIf { state.fresh || !state.gaveUp }
    val showPicture = picture != null
    val fade = remember { Animatable(0f) }
    LaunchedEffect(showPicture) {
        if (!showPicture) fade.snapTo(0f)
        else if (Motion.reduced) fade.snapTo(1f)
        else fade.animateTo(1f, tween(Motion.Fast))
    }
    Box(
        modifier
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.28f), shape),
    ) {
        when {
            picture != null -> SeekShotPicture(picture, Modifier.alpha(fade.value))
            state.gaveUp -> SeekFallback(time)
            else -> SkeletonBox(rememberShimmer(), Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp))
        }
        if (!(state.gaveUp && !showPicture)) {
            Chip(time, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        }
    }
}

/** How far above the timebar's top the card's bottom edge sits: clear of the times and a loop chip. */
private val PREVIEW_LIFT = 34.dp

/** The card's standard size: 240 by 135, 16:9, narrowed only where the bar itself is narrower. */
internal val CARD_WIDTH = 240.dp
private val CARD_MIN_WIDTH = 120.dp

private val FallbackGround = Color(0xFF1C1F24)

/**
 * The hover's frames for one video from the engine's second decoder, kept in a small cache.
 *
 * One decode at a time, the latest asked for next: the hover used to start a decode per ten
 * seconds of the video and cancel it the moment the pointer moved on, so a sweep across the bar
 * never finished one. A decode under way now runs to its end (its frame is kept), and only the
 * newest wish waits behind it.
 */
internal class FrameSource(
    private val durationMs: () -> Long = { 0L },
    private val fetch: suspend (Long) -> ImageBitmap?,
) {
    private val cache = com.tmplayer.platform.LruMap<Long, ImageBitmap>(FRAME_CACHE)
    private val misses = com.tmplayer.platform.LruMap<Long, Long>(FRAME_CACHE)
    private val wanted = MutableStateFlow<Long?>(null)

    /** Counts up each time a decode ends, picture or none, for the hover to look again. */
    val landed = MutableStateFlow(0L)

    /** The moment the frame for [positionMs] is decoded at: a slot's middle, slots finer on a short video. */
    fun timeFor(positionMs: Long): Long {
        val step = stepMs()
        return (positionMs / step) * step + step / 2
    }

    private fun stepMs(): Long = (durationMs() / SLOTS).coerceIn(MIN_STEP_MS, MAX_STEP_MS)

    fun cached(at: Long): ImageBitmap? = cache[at]

    /** Whether the frame for [at] could not be had a moment ago (not on the disk yet, a failed decode). */
    fun declined(at: Long): Boolean {
        val time = misses[at] ?: return false
        return System.currentTimeMillis() - time < MISS_MEMORY_MS
    }

    fun want(at: Long) {
        if (cached(at) != null || declined(at)) {
            landed.value = landed.value + 1
            return
        }
        wanted.value = at
    }

    /** The one worker: decodes the newest wish, then the next, until the hover leaves. */
    suspend fun run() {
        wanted.collect { at ->
            if (at == null || cached(at) != null) return@collect
            val image = try {
                fetch(at)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG, "Frame grab at $at ms failed", e)
                null
            }
            if (image != null) cache[at] = image else misses[at] = System.currentTimeMillis()
            if (wanted.value == at) wanted.value = null
            landed.value = landed.value + 1
        }
    }

    private companion object {
        const val TAG = "SeekFrames"
        const val SLOTS = 120L
        const val MIN_STEP_MS = 1_000L
        const val MAX_STEP_MS = 10_000L
        const val MISS_MEMORY_MS = 4_000L
    }
}

internal val LocalFrameSource = staticCompositionLocalOf<FrameSource?> { null }

private const val FRAME_REST_MS = 120L
private const val FRAME_CACHE = 32

/** No answer from any source for this long and the card shows its fallback instead of shimmering. */
private const val GIVE_UP_MS = 1_500L

/** Pointer moves inside one step of the video ask for no new tile. */
private const val TILE_STEP_MS = 1_000L

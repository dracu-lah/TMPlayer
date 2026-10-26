package com.tmplayer.desktop.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tmplayer.data.StoryboardFiles
import com.tmplayer.data.StoryboardMap
import com.tmplayer.data.StoryboardMaps
import com.tmplayer.data.StoryboardTile
import com.tmplayer.data.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Telegram's storyboard for the video playing (CP80), for the timebar's hover: the sprite is
 * fetched through TDLib and decoded once, the first time the pointer rests on the bar, and each
 * tile is drawn straight out of it. The desktop had no scrub pictures before; a storyboard is a
 * few dozen kilobytes, so there is no need for the video's own bytes or a second decoder.
 */
class DesktopStoryboard(
    private val files: StoryboardFiles,
    private val durationMs: () -> Long,
    /** Puts a file of TDLib's on the disk and says where; the thumbnails' fetch unless a test hands another. */
    private val fetch: suspend (Int) -> String? = { Thumbnails.file(it) },
) {

    private val gate = Mutex()
    private var sprite: ImageBitmap? = null
    private var map: StoryboardMap? = null
    private var failed = false

    /** The sprite and the tile for [positionMs], or null while it cannot be had. */
    suspend fun tileAt(positionMs: Long): Pair<ImageBitmap, StoryboardTile>? {
        val (image, tiles) = load() ?: return null
        val tile = tiles.tileAt(positionMs) ?: return null
        if (tile.x + tile.width > image.width || tile.y + tile.height > image.height) return null
        return image to tile
    }

    private suspend fun load(): Pair<ImageBitmap, StoryboardMap>? = gate.withLock {
        val ready = sprite
        val tiles = map
        if (ready != null && tiles != null) return ready to tiles
        if (failed) return null
        // Not fetched this time (offline, slow): asked again on the next hover.
        val imagePath = fetch(files.imageFileId) ?: return null
        val mapPath = fetch(files.mapFileId)
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(imagePath).readBytes()
                val image = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                val text = mapPath?.let { runCatching { File(it).readText() }.getOrNull() }.orEmpty()
                image to StoryboardMaps.parse(text, files.tileWidth, files.tileHeight, image.width, image.height, durationMs())
            }.getOrNull()
        }
        if (loaded == null || loaded.second.isEmpty) {
            // A map that needs the video's length (the grid) cannot be read before mpv has it: that
            // is asked again on the next hover, not given up on for the whole video.
            if (durationMs() > 0 || loaded == null) failed = true
            return null
        }
        sprite = loaded.first
        map = loaded.second
        loaded
    }
}

/** The storyboard of the video playing, for [SeekPreview]; null where it has none. */
internal val LocalStoryboard = staticCompositionLocalOf<DesktopStoryboard?> { null }

/** One picture for the hover: [image], and the part of it to draw where it is a storyboard's tile. */
internal class SeekShot(val image: ImageBitmap, val tile: StoryboardTile? = null) {
    /** Width over height of what is drawn, so the card keeps the video's own shape. */
    val aspect: Float
        get() = if (tile != null) tile.width / tile.height.coerceAtLeast(1).toFloat() else image.width / image.height.coerceAtLeast(1).toFloat()
}

/**
 * The storyboard's tile for [positionMs], or null while the storyboard is still coming (or has
 * none for that moment). Asked again only when the tile under the pointer changes; the last
 * picture stays up meanwhile.
 */
@Composable
internal fun rememberStoryboardShot(board: DesktopStoryboard, positionMs: Long): SeekShot? {
    val shown by produceState<SeekShot?>(null, board, positionMs / TILE_STEP_MS) {
        value = board.tileAt(positionMs)?.let { (image, tile) -> SeekShot(image, tile) } ?: value
    }
    return shown
}

/**
 * The video's own frame at [positionMs] from [frames] (the engine's second decoder,
 * [PlaybackEngine.previewFrame]), where there is no storyboard. Asked once per ten seconds of the
 * video, after the pointer has rested a moment, and kept; the last picture stays up while the
 * next is decoded, and none shows where that part of the video is not on the disk yet.
 */
@Composable
internal fun rememberFrameShot(frames: FrameSource, positionMs: Long): SeekShot? {
    val slot = positionMs / FRAME_STEP_MS
    val shown by produceState<SeekShot?>(frames.cached(slot)?.let { SeekShot(it) }, frames, slot) {
        frames.cached(slot)?.let { value = SeekShot(it); return@produceState }
        kotlinx.coroutines.delay(FRAME_REST_MS)
        frames.frameAt(slot)?.let { value = SeekShot(it) }
    }
    return shown
}

/** The picture of a [SeekShot] in a frame like the chips', [TIP_WIDTH] wide. */
@Composable
internal fun SeekShotPicture(shot: SeekShot, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val tile = shot.tile
    // Asked for the room it needs, whatever the bar around it allows: the timebar is 28 dp tall,
    // and a picture cut to that was a thin strip (HX3).
    Canvas(
        modifier
            .requiredSize(TIP_WIDTH, TIP_WIDTH / shot.aspect.coerceAtLeast(0.1f))
            .clip(shape)
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), shape),
    ) {
        if (tile != null) {
            drawImage(
                shot.image,
                srcOffset = IntOffset(tile.x, tile.y),
                srcSize = IntSize(tile.width, tile.height),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
        } else {
            drawImage(shot.image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
    }
}

/**
 * What hovering or dragging the timebar shows over it: the picture for the moment where there is
 * one, and its time in a chip under it, or the chip alone where there is none (no storyboard and
 * no frames, the scrub pictures setting off, a part not fetched yet). Kept whole inside the bar's
 * two ends, centred on the pointer where there is room, and lifted clear of the times above the
 * bar so the chip never lands on them. Placed from the top left of the bar, and measured on its
 * own, so it takes no room from the bar.
 */
@Composable
internal fun SeekPreview(
    time: String,
    positionMs: Long,
    pointerX: Float,
    barWidth: Float,
    modifier: Modifier = Modifier,
) {
    val board = LocalStoryboard.current
    val frames = LocalFrameSource.current
    val shot = when {
        board != null -> rememberStoryboardShot(board, positionMs)
        frames != null -> rememberFrameShot(frames, positionMs)
        else -> null
    }
    val lift = with(androidx.compose.ui.platform.LocalDensity.current) { PREVIEW_LIFT.roundToPx() }
    Column(
        modifier.layout { measurable, _ ->
            val placeable = measurable.measure(Constraints())
            layout(0, 0) {
                val x = (pointerX - placeable.width / 2f).roundToInt().coerceIn(0, (barWidth.roundToInt() - placeable.width).coerceAtLeast(0))
                placeable.place(x, -(placeable.height + lift))
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (shot != null) {
            SeekShotPicture(shot)
            Spacer(Modifier.height(6.dp))
        }
        Chip(time)
    }
}

/** How far above the timebar's top the card's bottom edge sits: clear of the times and a loop chip. */
private val PREVIEW_LIFT = 34.dp

/** The hover's frames for one video, by ten second slot, kept in a small cache. */
internal class FrameSource(private val fetch: suspend (Long) -> ImageBitmap?) {
    private val cache = com.tmplayer.platform.LruMap<Long, ImageBitmap>(FRAME_CACHE)
    private val gate = Mutex()

    fun cached(slot: Long): ImageBitmap? = cache[slot]

    suspend fun frameAt(slot: Long): ImageBitmap? = gate.withLock {
        cached(slot) ?: fetch(slot * FRAME_STEP_MS + FRAME_STEP_MS / 2)?.also { cache[slot] = it }
    }
}

internal val LocalFrameSource = staticCompositionLocalOf<FrameSource?> { null }

private const val FRAME_STEP_MS = 10_000L
private const val FRAME_REST_MS = 120L
private const val FRAME_CACHE = 48

/** How wide the hover's picture is drawn. */
internal val TIP_WIDTH = 160.dp

/** Pointer moves inside one step of the video ask for no new tile. */
private const val TILE_STEP_MS = 1_000L

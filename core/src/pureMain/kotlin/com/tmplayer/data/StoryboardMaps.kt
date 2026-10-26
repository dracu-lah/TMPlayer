package com.tmplayer.data


/**
 * Telegram's seek thumbnails for one video (`MessageVideo.storyboards`, CP80): a JPEG of small
 * frames laid out in a grid, and a map file saying which tile is which moment. Both are small
 * (tens of kilobytes), so a scrub preview needs neither the video's bytes on the disk nor a
 * decoder beside the one playing it: a 1 GB stick and the desktop's timebar can have them.
 */
@androidx.compose.runtime.Immutable
data class StoryboardFiles(
    val imageFileId: Int,
    val mapFileId: Int,
    val tileWidth: Int,
    val tileHeight: Int,
)

/** One tile of the sprite: the moment it starts at and where it is in the picture, in pixels. */
data class StoryboardTile(val atMs: Long, val x: Int, val y: Int, val width: Int, val height: Int)

/** The tiles of one sprite in time order, and the one to show for a moment. */
class StoryboardMap(val tiles: List<StoryboardTile>) {

    /** The last tile that starts at or before [positionMs]; the first one before it. */
    fun tileAt(positionMs: Long): StoryboardTile? {
        if (tiles.isEmpty()) return null
        var lo = 0
        var hi = tiles.size - 1
        if (positionMs <= tiles[0].atMs) return tiles[0]
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tiles[mid].atMs <= positionMs) lo = mid else hi = mid - 1
        }
        return tiles[lo]
    }

    val isEmpty: Boolean get() = tiles.isEmpty()
}

/**
 * Reads a storyboard's map file. Telegram does not document the format, so this reads every
 * shape a sprite map is written in and falls back on the grid itself:
 *
 * - WebVTT, a cue a tile: `00:00:05.000 --> 00:00:10.000` then `sprite.jpg#xywh=160,0,160,90`;
 * - a line a tile of numbers: the moment in seconds (or milliseconds), then x and y, and the
 *   tile's width and height where they differ from the stated ones;
 * - `key=value` lines naming the frame count, the step between frames and the columns;
 * - nothing usable: the picture read as a grid of [tileWidth] by [tileHeight] tiles, row by row,
 *   spread evenly over the video's length.
 */
object StoryboardMaps {

    /** A scrub preview's width on the phone and the desktop; the tiles closest under it are used. */
    const val PREFERRED_TILE_WIDTH = 320

    /** More tiles than any sheet holds: a three hour video at one a second. */
    const val MAX_TILES = 10_800

    fun parse(
        text: String,
        tileWidth: Int,
        tileHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        durationMs: Long,
    ): StoryboardMap {
        val clean = text.removePrefix("﻿")
        val fromVtt = webVtt(clean)
        if (fromVtt.isNotEmpty()) return StoryboardMap(fromVtt.sortedBy { it.atMs })
        val fromLines = numberLines(clean, tileWidth, tileHeight, durationMs)
        if (fromLines.isNotEmpty()) return StoryboardMap(fromLines.sortedBy { it.atMs })
        return StoryboardMap(grid(keys(clean), tileWidth, tileHeight, imageWidth, imageHeight, durationMs))
    }

    private val CUE = Regex("""^\s*([\d:.]+)\s*-->\s*([\d:.]+)""")
    private val XYWH = Regex("""#xywh=(\d+),(\d+),(\d+),(\d+)""")

    private fun webVtt(text: String): List<StoryboardTile> {
        if (!text.trimStart().startsWith("WEBVTT") && !text.contains("-->")) return emptyList()
        val out = ArrayList<StoryboardTile>()
        var at: Long? = null
        for (line in text.lineSequence()) {
            val cue = CUE.find(line)
            if (cue != null) {
                at = clock(cue.groupValues[1])
                continue
            }
            val box = XYWH.find(line)
            if (box != null && at != null) {
                val (x, y, w, h) = box.destructured.toList().map { it.toIntOrNull() }
                // A number too long for an Int is a broken line, skipped (HX10).
                if (x != null && y != null && w != null && h != null) out += StoryboardTile(at, x, y, w, h)
                at = null
            }
        }
        return out
    }

    /** `01:02:03.500`, `02:03.5` or `3.5`, in milliseconds. */
    private fun clock(value: String): Long? {
        val parts = value.split(':')
        var seconds = 0.0
        for (part in parts) seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        return (seconds * 1000).toLong()
    }

    private val SEPARATORS = Regex("""[\s,;]+""")

    private fun numberLines(text: String, tileWidth: Int, tileHeight: Int, durationMs: Long): List<StoryboardTile> {
        val rows = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && '=' !in it && !it.startsWith("#") }
            .map { line -> line.split(SEPARATORS).map { it.toDoubleOrNull() } }
            .filter { numbers -> numbers.size >= 3 && numbers.all { it != null } }
            .map { numbers -> numbers.map { it!! } }
            .toList()
        if (rows.size < 2) return emptyList()
        // Seconds unless the moments run far past the video's length, which only milliseconds do.
        val maxAt = rows.maxOf { it[0] }
        val millis = durationMs > 0 && maxAt > durationMs / 1000.0 * 4 && rows.all { it[0] == kotlin.math.floor(it[0]) }
        return rows.map { n ->
            StoryboardTile(
                atMs = if (millis) n[0].toLong() else (n[0] * 1000).toLong(),
                x = n[1].toInt(),
                y = n[2].toInt(),
                width = n.getOrNull(3)?.toInt()?.takeIf { it > 0 } ?: tileWidth,
                height = n.getOrNull(4)?.toInt()?.takeIf { it > 0 } ?: tileHeight,
            )
        }
    }

    private fun keys(text: String): Map<String, String> = text.lineSequence()
        .flatMap { it.split(SEPARATORS).asSequence() }
        .mapNotNull { token ->
            val i = token.indexOf('=')
            if (i <= 0) null else token.substring(0, i).trim().lowercase() to token.substring(i + 1).trim()
        }
        .toMap()

    private fun grid(
        keys: Map<String, String>,
        tileWidth: Int,
        tileHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        durationMs: Long,
    ): List<StoryboardTile> {
        if (tileWidth <= 0 || tileHeight <= 0 || imageWidth < tileWidth || imageHeight < tileHeight) return emptyList()
        val columns = (keys["columns"] ?: keys["cols"])?.toIntOrNull()?.takeIf { it > 0 } ?: (imageWidth / tileWidth)
        val rows = imageHeight / tileHeight
        // In Long and capped: a map that claims a million columns is not a reason to overflow,
        // throw in coerceIn or fill the memory with tiles (HX10).
        val fits = (columns.toLong() * rows).coerceAtMost(MAX_TILES.toLong()).toInt()
        if (fits < 1) return emptyList()
        val count = (keys["frame_count"] ?: keys["frames"] ?: keys["count"])?.toIntOrNull()
            ?.coerceIn(1, fits) ?: fits
        val stepMs = (keys["frame_duration"] ?: keys["interval"] ?: keys["step"])?.toDoubleOrNull()
            ?.let { if (it < 100) (it * 1000).toLong() else it.toLong() }
            ?: if (durationMs > 0) durationMs / count else return emptyList()
        return (0 until count).map { i ->
            StoryboardTile(i * stepMs, (i % columns) * tileWidth, (i / columns) * tileHeight, tileWidth, tileHeight)
        }
    }
}

/** The pixel size of a JPEG, read from its frame header without decoding it; null for anything else. */
object JpegSize {
    fun read(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            // Every start of frame but the three that are not (DHT, JPG, DAC).
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return if (width > 0 && height > 0) width to height else null
            }
            if (length < 2) return null
            i += 2 + length
        }
        return null
    }
}

package com.tmplayer.ios

import com.tmplayer.ios.media3.SourceStream
import com.tmplayer.ios.media3.SourceStreams
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/** Where mpv's `tmsource://` protocol (MediaSourceProtocol.swift) opens the player's own reading of a video. */
object MediaSourceStreams {
    /** The stream at [key], or null when it is gone or will not open. Blocks. */
    fun open(key: String): MediaSourceStream? = SourceStreams.open(key)?.let(::MediaSourceStream)
}

/** A [SourceStream] for Swift: the bytes copied straight into the engine's buffer. */
class MediaSourceStream internal constructor(private val stream: SourceStream) {
    private val buffer = ByteArray(BUFFER_BYTES)

    val size: Long get() = stream.size

    /** Up to [count] bytes at [position] into [into]: how many, 0 at the end, -1 on a failure. */
    @OptIn(ExperimentalForeignApi::class)
    fun read(position: Long, into: CPointer<ByteVar>?, count: Long): Long {
        if (into == null || count <= 0) return 0
        val read = stream.read(position, buffer, minOf(count, buffer.size.toLong()).toInt())
        if (read > 0) buffer.usePinned { memcpy(into, it.addressOf(0), read.convert()) }
        return read.toLong()
    }

    fun cancel() = stream.cancel()

    fun close() = stream.close()

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
    }
}

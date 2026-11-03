package com.tmplayer.cast.dlna

import com.tmplayer.cast.CastResource
import com.tmplayer.cast.CastServer
import com.tmplayer.cast.DlnaServing
import com.tmplayer.cast.ReceiverMedia
import com.tmplayer.cast.ReceiverState
import com.tmplayer.cast.WebVtt
import com.tmplayer.platform.Logger
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** One video to send to a renderer. */
class DlnaVideo(
    val title: String,
    /** [CastResource.Ranged] for the file's own bytes, [CastResource.Stream] for a rewrap made as it is sent. */
    val resource: CastResource,
    val mime: String,
    val durationMs: Long,
    val sizeBytes: Long? = null,
    /** SubRip text for the renderer to show beside the video, or null. */
    val subtitleSrt: String? = null,
    val profile: String? = null,
)

/** Why a cast ended by itself. */
enum class DlnaClosed { Stopped, Lost, Idle }

/**
 * One DLNA cast session: the cast server bound to this device's LAN address and open to the
 * renderer's address alone, the renderer client, and what joins them.
 *
 * It serves only this session's video and subtitle, under the server's token path. The port is
 * closed by [close] (Stop, app exit), when the renderer is lost, and when the cast has been
 * paused with no request for [idleLimitMs] (30 minutes), after which [onClosed] hears why.
 *
 * A [CastResource.Stream] cannot be sought by ranges, so a seek reloads it from the new start
 * (`?start=<ms>` in the address, which the stream's writer reads) and the positions the renderer
 * reports, which count from that start, are shifted back onto the video's timeline.
 */
class DlnaCast(
    val device: DlnaDevice,
    bind: InetAddress,
    soap: SoapTransport = SoapClient(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val idleLimitMs: Long = 30 * 60_000L,
    /** False in tests, which call [DlnaRenderer.pollOnce] themselves. */
    private val autoPoll: Boolean = true,
    private val onClosed: (DlnaClosed) -> Unit = {},
) : Closeable {

    val server = CastServer(bind, allowed = { it == device.address }, clock = clock)
    val renderer = DlnaRenderer(device, soap, clock)

    private val closed = AtomicBoolean(false)
    private var video: DlnaVideo? = null
    private var offsetMs = 0L
    private var onMedia: (ReceiverMedia) -> Unit = {}

    val isOpen: Boolean get() = !closed.get()

    /**
     * Serves [video] and loads it on the renderer at [startMs]. A refusal comes back as
     * [DlnaLoad.Refused] with the server still up, so the caller can try its next route with
     * another call. [onMedia] hears each poll, positions on the video's own timeline.
     */
    fun open(video: DlnaVideo, startMs: Long, onMedia: (ReceiverMedia) -> Unit): DlnaLoad {
        if (closed.get()) return DlnaLoad.Failed("The cast is closed")
        this.video = video
        this.onMedia = onMedia
        offsetMs = 0
        server.start()
        return send(video, startMs)
    }

    private fun send(video: DlnaVideo, startMs: Long): DlnaLoad {
        val ranged = video.resource is CastResource.Ranged
        val features = DlnaFeatures(video.profile, rangeSeek = ranged, converted = !ranged)
        val subtitleName = video.subtitleSrt?.let { server.publish(SUBTITLE_NAME, CastResource.Text(it, WebVtt.SRT_MIME), DlnaServing(DlnaFeatures(rangeSeek = false).field(), interactive = true)); SUBTITLE_NAME }
        val name = fileName(video.mime)
        server.publish(name, video.resource, DlnaServing(features.field(), captionName = subtitleName))
        val streamStart = if (ranged) 0L else startMs
        offsetMs = streamStart
        val url = server.urlFor(name).let { if (ranged) it else "$it?start=$streamStart" }
        val didl = Didl.item(
            video.title, url, video.mime, features,
            subtitleUrl = subtitleName?.let { server.urlFor(it) },
            durationMs = video.durationMs.takeIf { it > 0 }, sizeBytes = video.sizeBytes.takeIf { ranged },
        )
        val result = renderer.load(url, didl, startMs = if (ranged) startMs else 0, durationMs = (video.durationMs - streamStart).coerceAtLeast(0))
        if (result == DlnaLoad.Loaded) {
            if (autoPoll) renderer.startPolling(onMedia = { shifted(it) }, onLost = { end(DlnaClosed.Lost) })
            else renderer.listen(onMedia = { shifted(it) }, onLost = { end(DlnaClosed.Lost) })
        }
        return result
    }

    private fun shifted(m: ReceiverMedia) {
        if (closed.get()) return
        val shifted = if (offsetMs == 0L) m else m.copy(positionMs = m.positionMs + offsetMs)
        if (m.state == ReceiverState.Paused && server.idleMs() >= idleLimitMs) {
            Logger.i(TAG, "Paused and untouched for ${server.idleMs() / 1000} s, closing")
            end(DlnaClosed.Idle)
            return
        }
        onMedia(shifted)
    }

    fun play(): DlnaLoad = renderer.play()
    fun pause(): Boolean = renderer.pause()

    /** Whether the bar may move: a stream can always be reloaded from a start, a file needs the renderer's Seek. */
    val canSeek: Boolean get() = video?.resource !is CastResource.Ranged || renderer.canSeek

    fun seek(ms: Long): SeekOutcome {
        val current = video ?: return SeekOutcome.Failed
        if (current.resource is CastResource.Ranged) return renderer.seek(ms)
        return when (send(current, ms)) {
            DlnaLoad.Loaded -> SeekOutcome.Done
            is DlnaLoad.Refused -> SeekOutcome.Refused
            is DlnaLoad.Failed -> SeekOutcome.Failed
        }
    }

    /** Stops the renderer and closes everything. */
    fun stop() {
        if (closed.get()) return
        renderer.stop()
        end(DlnaClosed.Stopped)
    }

    private fun end(reason: DlnaClosed) {
        if (!closed.compareAndSet(false, true)) return
        renderer.close()
        server.stop()
        runCatching { onClosed(reason) }.onFailure { Logger.w(TAG, "Listener failed", it) }
    }

    /** Closes the server and polling without telling the renderer (the app is going away). */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            renderer.close()
            server.stop()
        }
    }

    private fun fileName(mime: String): String = when (mime.lowercase()) {
        "video/x-matroska" -> "video.mkv"
        "video/webm" -> "video.webm"
        "video/mpeg", "video/mp2t" -> "video.ts"
        else -> "video.mp4"
    }

    private companion object {
        const val TAG = "DlnaCast"
        const val SUBTITLE_NAME = "subtitle.srt"
    }
}

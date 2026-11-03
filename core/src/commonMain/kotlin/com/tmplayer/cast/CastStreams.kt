package com.tmplayer.cast

import com.tmplayer.platform.Logger
import java.io.IOException

/**
 * Puts one video on the [CastServer] the way its [CastRoute] says, for either platform's sender:
 * the file's own bytes with ranges, Matroska as HLS (the picture, each soundtrack and each text
 * subtitle track a rendition), or the one rewrapped stream made as it is sent, with its sound
 * converted where the route asks for that. Subtitle files go up beside it as WebVTT, and into the
 * HLS playlists as renditions of their own.
 *
 * Every read of the video goes through a [RecoveringCastSource], so a short Telegram stall under
 * a segment is ridden out here instead of failing the TV's request.
 */
class CastStreams(
    private val server: CastServer,
    /** How the receiver's reads ride out a Telegram stall; null reads the source as it is. */
    private val recovery: CastRecovery? = CastRecovery(),
    private val converterFactory: (() -> AudioConverter)? = null,
    /** The DLNA headers a resource goes out with (see [com.tmplayer.cast.dlna.dlnaServing]); null for a Cast receiver. */
    private val serving: ((CastResource) -> DlnaServing?)? = null,
) {

    /** What the receiver is told to load, and how the sender reads its clock. */
    class Published(
        val url: String,
        val mime: String,
        /** Where in the video the stream's zero is: the receiver's time plus this is the video's. */
        val offsetMs: Long,
        /** The receiver can seek in it itself; false for a rewrap, where a seek is a new stream. */
        val seekable: Boolean,
        val route: CastRoute,
        /** An HLS cast's soundtracks and subtitles, in the master's order; empty for anything else. */
        val renditions: List<HlsRendition> = emptyList(),
        /** The whole length, as the playlist tells the receiver; 0 when not known here. */
        val durationMs: Long = 0,
    ) {
        val hls: Boolean get() = route is CastRoute.Hls
    }

    private var counter = 0
    private var lastStream: String? = null
    private val remuxers = HashMap<Pair<Int?, Boolean>, MkvRemuxer>()
    private var remuxersFor: CastSource? = null
    private val files = ArrayList<HlsSubtitleFile>()

    /** Publishes [source] by [route] from [startMs] on. [name] gives the receiver an extension to see. */
    fun publish(given: CastSource, probe: MediaProbe, route: CastRoute, startMs: Long, name: String): Published {
        val source = recovering(given)
        val n = ++counter
        return when (route) {
            is CastRoute.Direct -> {
                val ext = when (route.mime) {
                    "video/webm" -> "webm"
                    "video/x-matroska" -> "mkv"
                    else -> "mp4"
                }
                val resource = CastResource.Ranged(source, route.mime)
                val url = server.publish("video.$ext", resource, serving?.invoke(resource))
                Published(url, route.mime, 0, true, route)
            }
            is CastRoute.Hls -> {
                val hls = MkvHls.prepare(source, probe, route.audio, route.audioTrack, files.toList(), converterFactory, subtitles = CAST_SUBTITLES)
                // Made now, off the receiver's clock: the soundtracks' entries need their first frames.
                val renditions = hls.renditions
                val folder = "hls$n"
                lastStream?.let(server::unpublish)
                lastStream = folder
                server.publish(folder, CastResource.Tree(hls::resolve))
                Published(server.urlFor(folder) + "/master.m3u8", MkvHls.CONTENT_TYPE, 0, true, route, renditions, hls.durationMs)
            }
            is CastRoute.Remux, is CastRoute.ConvertAudio -> {
                val audio = when (route) {
                    is CastRoute.Remux -> route.audioTrack
                    is CastRoute.ConvertAudio -> route.audioTrack
                    else -> null
                }
                val converter = if (route is CastRoute.ConvertAudio) converterFactory ?: throw IOException("No audio converter") else null
                // One per video and soundtrack, so a seek does not read the cues again.
                if (remuxersFor !== source) {
                    remuxers.clear()
                    remuxersFor = source
                }
                val remuxer = remuxers.getOrPut(audio to (converter != null)) { MkvRemuxer(source, probe, audio, converter) }
                val offset = remuxer.actualStartMs(startMs)
                val resource = CastResource.Stream("video/mp4") { query, out ->
                    val from = query["t"]?.toLongOrNull() ?: 0L
                    try {
                        remuxer.write(from, out)
                    } catch (e: IOException) {
                        // The receiver hanging up mid stream is how a seek or a stop ends one.
                        Logger.i(TAG, "Rewrap ended: ${e.message}")
                    }
                }
                // A new name per stream, so a receiver never mixes the bytes of two starts.
                // The last one is taken down, its open read closed, so Telegram stops fetching there.
                val file = "stream$n.mp4"
                lastStream?.let(server::unpublish)
                lastStream = file
                server.publish(file, resource, serving?.invoke(resource))
                Published(server.urlFor(file) + "?t=$startMs", "video/mp4", offset, false, route)
            }
            is CastRoute.TranscodeVideo -> throw IOException("Converting the picture is not available here")
        }.also { Logger.i(TAG, "Published ${route::class.simpleName} for $name at ${it.url}") }
    }

    private var wrapped: RecoveringCastSource? = null

    /**
     * [given] with its reads riding out a Telegram stall, one wrapper per video, so the
     * remuxers kept for a video are found again on its next publish.
     */
    private fun recovering(given: CastSource): CastSource {
        val r = recovery ?: return given
        if (given is RecoveringCastSource) return given
        wrapped?.takeIf { it.inner === given }?.let { return it }
        return RecoveringCastSource(given, r).also { wrapped = it }
    }

    /**
     * A subtitle file as a WebVTT side track; its URL. It is kept as cues too, so the next HLS
     * publish carries it as a subtitle rendition (side tracks are for the file played as it is).
     */
    fun subtitle(id: Int, bytes: ByteArray, extension: String, name: String = "Subtitles", language: String? = null): String {
        val cues = runCatching { WebVtt.cues(bytes, extension) }.getOrDefault(emptyList())
        files.removeAll { it.id == id.toLong() }
        files += HlsSubtitleFile(id.toLong(), name, language, cues)
        return server.publish("subs$id.vtt", CastResource.Text(WebVtt.convert(bytes, extension), WebVtt.MIME))
    }

    /** The subtitle files as they stand, for a sender deciding what to ask for. */
    val subtitleFiles: List<HlsSubtitleFile> get() = files.toList()

    private companion object {
        const val TAG = "CastStreams"
    }
}

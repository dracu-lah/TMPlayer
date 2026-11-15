package com.tmplayer.player

import com.tmplayer.data.CrashContext

/**
 * Tells [CrashContext] what is playing (S2): the engine, the decoder's name, the codec family and
 * the picture's size, so a crash report says "hevc, uhd, on c2.mtk.hevc.decoder, in media3" and
 * nothing a person could be named by. Never a title, a chat or a file name: this class is only
 * handed the engine, the decoder's own name, a MIME type and two numbers.
 *
 * The player reports each piece as it learns it (the engine at start, the decoder when it is
 * made, the format when it changes) and [ended] when the playback is let go of or the activity
 * goes, which clears it so a crash afterwards is not about that video. The sink is a parameter so
 * a test can watch what is said; [CrashContext] is the real one.
 */
internal class CrashPlayingNote(private val sink: Sink = Sink.Real) {

    interface Sink {
        fun playing(engine: String, decoder: String, codec: String, width: Int, height: Int)
        fun stopped()

        object Real : Sink {
            override fun playing(engine: String, decoder: String, codec: String, width: Int, height: Int) =
                CrashContext.playing(engine, decoder, codec, width, height)

            override fun stopped() = CrashContext.stopped()
        }
    }

    private var engine = ""
    private var decoder = ""
    private var codec = ""
    private var width = 0
    private var height = 0

    /** A playback begins on [engine] (a [CrashContext.ENGINES] name); what the last one knew is forgotten. */
    fun began(engine: String) {
        decoder = ""
        codec = ""
        width = 0
        height = 0
        this.engine = engine
        say()
    }

    /** The video decoder was made. A name that is not the decoder's own is filtered when the tag is built. */
    fun decoder(name: String?) {
        decoder = name.orEmpty()
        say()
    }

    /** The picture's format changed: its MIME type and size (0 when not known). */
    fun format(mimeType: String?, width: Int, height: Int) {
        codec = codecOf(mimeType)
        this.width = width.coerceAtLeast(0)
        this.height = height.coerceAtLeast(0)
        say()
    }

    /** The playback is over, let go of, or the activity is gone. */
    fun ended() {
        if (engine.isEmpty()) return
        engine = ""
        decoder = ""
        codec = ""
        width = 0
        height = 0
        sink.stopped()
    }

    // Nothing is said before [began] or after [ended]: a late event from a player on its way out.
    private fun say() {
        if (engine.isEmpty()) return
        sink.playing(engine, decoder, codec, width, height)
    }

    companion object {
        /** The codec family for a MIME type (`video/hevc` is `hevc`); empty when it is not one the report names. */
        fun codecOf(mimeType: String?): String = when (mimeType?.lowercase()?.trim()) {
            "video/avc" -> "avc"
            "video/hevc" -> "hevc"
            "video/av01" -> "av1"
            "video/x-vnd.on2.vp9" -> "vp9"
            "video/x-vnd.on2.vp8" -> "vp8"
            "video/mpeg2" -> "mpeg2"
            "video/mp4v-es" -> "mpeg4"
            "video/dolby-vision" -> "dolby"
            "video/x-ms-wmv", "video/wmv3" -> "wmv"
            else -> ffmpegDecoderOf(mimeType)
        }

        /**
         * The decoder an FFmpeg-named MIME type stands for (`video/x-ffmpeg-rv40` is `rv40`, see
         * `FfmpegLibrary.ffmpegMimeType`), so a crash in one of the extension's old-codec decoders
         * names the codec; empty for anything else, or a name no FFmpeg decoder has.
         */
        private fun ffmpegDecoderOf(mimeType: String?): String {
            val mime = mimeType?.lowercase()?.trim() ?: return ""
            val name = when {
                mime.startsWith("video/x-ffmpeg-") -> mime.removePrefix("video/x-ffmpeg-")
                mime.startsWith("audio/x-ffmpeg-") -> mime.removePrefix("audio/x-ffmpeg-")
                else -> return ""
            }
            val plain = name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
            return if (plain && name.length in 1..MAX_DECODER_NAME) name else ""
        }

        private const val MAX_DECODER_NAME = 24
    }
}

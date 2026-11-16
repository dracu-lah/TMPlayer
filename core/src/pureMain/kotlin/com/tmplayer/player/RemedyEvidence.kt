package com.tmplayer.player

/**
 * What the player or a detector saw, as [RemedyLadder] reads it (RL1, research section 4.4).
 * [hard] evidence lets the ladder hop by itself; soft evidence only offers a change.
 */
enum class RemedyEvidence(val hard: Boolean) {
    /** A thrown decoder init or decoding error (`DECODER_INIT_FAILED`, `DECODING_FAILED`, `RESOURCES_RECLAIMED`). */
    DecoderError(true),

    /** No decoder on the device for the format (`FORMAT_UNSUPPORTED`, `FORMAT_EXCEEDS_CAPABILITIES`, a video track nothing takes). */
    NoDecoder(true),

    /** The sound plays and no picture came ([FirstFrameWatch]). */
    NoFirstFrame(true),

    /** The picture stopped while the sound goes on ([FrozenOutputWatch]). */
    Frozen(true),

    /** Green, blocky or garbage frames (the green frame sentinel). */
    WrongPicture(true),

    /** The viewer pressed "Picture looks wrong": hard evidence for the class. */
    UserReport(true),

    /**
     * The viewer pressed "Report stutter": hard evidence for the class (research 4.2), answered by
     * the stutter steps (async queueing, tunneling, software, the other engine), once.
     */
    UserStutter(true),

    /** The audio output or decoder failed (`AUDIO_TRACK_INIT_FAILED`): the audio steps, never the picture's. */
    AudioFailed(true),

    /** A stutter verdict ([StutterVerdict]) on the decoder or render path. Soft: offered, not taken. */
    Stutter(false),

    /** Software decoding cannot keep up ([com.tmplayer.data.DecodeJudge]). Soft: a lighter copy is offered. */
    SoftwareTooSlow(false),

    /** The bytes are not arriving (a stall, `NETWORK_BOUND`): another decoder would wait just the same. */
    Source(false),
    ;

    /**
     * Said by the viewer with a button. It is allowed to act even with "Never switch by itself"
     * (the press is the consent), and when nothing is left to try it ends in a plain word, never
     * the status sheet: the video plays, the viewer only said it could be better.
     */
    val fromViewer: Boolean get() = this == UserReport || this == UserStutter
}

/** The ladder's rungs (research 4.1). One remedy changes one thing, so a result can be attributed. */
enum class Rung {
    /** Same chip, other path: a reload, another decoder name, async queueing or tunneling flipped. */
    R1,

    /** The app's software decoder (NextLib FFmpeg). */
    R2,

    /** The other engine, as [EngineSelector.afterFailure] allows it. */
    R3,

    /** Ask the person: the status sheet, a lighter copy. */
    R4,

    /** Audio: the app's software audio decoder (PCM out). */
    A1,

    /** Audio: passthrough off, downmixed. */
    A2,
}

/**
 * One change the ladder can make, with the short [code] [com.tmplayer.data.RemedyMemory] stores it as.
 * The decoder level ([Rung.R1], [Rung.R2]), the audio level ([Rung.A1], [Rung.A2]) and the engine
 * level ([Rung.R3]) each get at most one automatic hop per video.
 */
enum class Remedy(val code: String, val rung: Rung) {
    /** Re-prepare at the same position on the same path (frees a stalled decoder). */
    Reload("rl", Rung.R1),

    /** Leave the failing decoder name out and take the next listed one (R1c). */
    ExcludeDecoder("ex", Rung.R1),

    /** Turn MediaCodec asynchronous queueing on (R1a; off by default below API 31). */
    FlipAsync("as", Rung.R1),

    /** Turn tunneled playback off (R1b). */
    FlipTunneling("tu", Rung.R1),

    /** The app's software video decoder (R2). */
    Software("sw", Rung.R2),

    /** The app's software audio decoder (A1). */
    AudioSoftware("au", Rung.A1),

    /** Audio passthrough off (A2). */
    PassthroughOff("pt", Rung.A2),

    /** The other engine (R3). */
    OtherEngine("en", Rung.R3),

    /** A lighter Telegram copy of the video, only ever offered (R4). */
    LighterCopy("lc", Rung.R4),
    ;

    /** Which budget the remedy spends: one hop per level per video. */
    internal val level: Int
        get() = when (rung) {
            Rung.R1, Rung.R2 -> 0
            Rung.A1, Rung.A2 -> 1
            Rung.R3 -> 2
            Rung.R4 -> 3
        }

    companion object {
        /** The remedy stored as [code], or null for anything else. */
        fun ofCode(code: String?): Remedy? = entries.firstOrNull { it.code == code }
    }
}

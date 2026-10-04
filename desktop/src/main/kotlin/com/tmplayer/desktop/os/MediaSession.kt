package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import java.awt.Frame

/**
 * What the OS's media controls (keyboard media keys, the GNOME and KDE media widgets, headset
 * buttons) ask the player to do. Every method has a default that ignores the request, so a player
 * implements only what it supports.
 *
 * These arrive on the OS integration's own thread (on Linux, a dbus-java worker; on Windows, a
 * thread SMTC calls in on), never on the UI thread; hop over before touching Compose state.
 */
interface MediaSessionCallbacks {
    fun onPlay() {}
    fun onPause() {}
    fun onPlayPause() {}
    fun onStop() {}
    fun onNext() {}
    fun onPrevious() {}

    /** Relative seek; negative goes back. */
    fun onSeekBy(offsetMs: Long) {}

    /** Absolute seek, already checked to lie within the current video. */
    fun onSeekTo(positionMs: Long) {}

    /** The media widget's "show the app" action: bring the window to the front. */
    fun onRaise() {}
}

/**
 * The now playing entry the OS shows and routes media keys to.
 *
 * The player calls [update] whenever something visible changes (a new video, play or pause, a
 * seek) and may also call it on a timer; repeating the same values sends nothing. A jump in
 * position that steady playback cannot explain is reported as a seek. [clear] says nothing is
 * playing (the player closed but the app stays open), [release] removes the entry for good.
 *
 * Linux has MPRIS 2 ([MprisMediaSession]), Windows has SMTC ([SmtcMediaSession]). macOS (Now
 * Playing) is not built, and [create] returns [NoMediaSession] there.
 */
interface MediaSession {

    fun update(
        title: String,
        durationMs: Long,
        positionMs: Long,
        playing: Boolean,
        artUrl: String? = null,
        canGoNext: Boolean = false,
        canGoPrevious: Boolean = false,
    )

    fun clear()

    fun release()

    companion object {
        /**
         * The session for this OS. [window] is the app's frame, which Windows ties SMTC to; it
         * must already have its native window. Anything that goes wrong gives [NoMediaSession]:
         * the player never depends on the OS taking part.
         */
        fun create(callbacks: MediaSessionCallbacks, window: Frame? = null): MediaSession = when {
            OsInfo.isLinux -> runCatching { MprisMediaSession.start(callbacks) }
                .onFailure { Logger.w("MediaSession", "MPRIS unavailable: ${it.message}") }
                .getOrDefault(NoMediaSession)
            OsInfo.isWindows && window != null -> runCatching { SmtcMediaSession.start(window, callbacks) }
                .onFailure { Logger.w("MediaSession", "SMTC unavailable: ${it.message}") }
                .getOrNull() ?: NoMediaSession
            else -> NoMediaSession
        }
    }
}

/** macOS, Linux without a session bus, and Windows when SMTC cannot be reached. */
object NoMediaSession : MediaSession {
    override fun update(
        title: String,
        durationMs: Long,
        positionMs: Long,
        playing: Boolean,
        artUrl: String?,
        canGoNext: Boolean,
        canGoPrevious: Boolean,
    ) = Unit

    override fun clear() = Unit
    override fun release() = Unit
}

/**
 * One snapshot of what is playing. [trackNumber] changes whenever the video does, which is what
 * MPRIS's track id is built from. [atNanos] is the monotonic time [positionMs] was read at, so the
 * position can be carried forward between updates while playing.
 */
data class NowPlaying(
    val trackNumber: Long,
    val title: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val artUrl: String?,
    val canGoNext: Boolean,
    val canGoPrevious: Boolean,
    val atNanos: Long,
) {
    /** Where playback is at [nowNanos], assuming normal speed while playing. */
    fun positionAt(nowNanos: Long): Long {
        if (!playing) return positionMs
        val moved = positionMs + (nowNanos - atNanos) / 1_000_000
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }

    /**
     * True when [next], the same video, sits somewhere steady playback from here would not have
     * taken it: the viewer (or the player) seeked.
     */
    fun seekedTo(next: NowPlaying): Boolean {
        if (next.trackNumber != trackNumber) return false
        val expected = positionAt(next.atNanos)
        return kotlin.math.abs(next.positionMs - expected) > SEEK_TOLERANCE_MS
    }

    companion object {
        /** Larger than one update tick's drift, smaller than the shortest seek step (5 s). */
        const val SEEK_TOLERANCE_MS = 1_500L
    }
}

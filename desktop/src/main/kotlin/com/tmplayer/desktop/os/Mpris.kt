package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.annotations.PropertiesEmitsChangedSignal.EmitChangeSignal
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownInterface
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/**
 * MPRIS 2 names, and the mapping from [NowPlaying] to the values the spec wants. Kept apart from
 * the D-Bus plumbing so it can be tested without a bus.
 *
 * Values are plain Kotlin (String, Long, Double, Boolean, DBusPath, List, Map) until [variant]
 * wraps them, so two snapshots can be compared with `==` to decide what changed.
 */
object MprisMapping {
    const val BUS_NAME = "org.mpris.MediaPlayer2.tmplayer"
    const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
    const val ROOT = "org.mpris.MediaPlayer2"
    const val PLAYER = "org.mpris.MediaPlayer2.Player"
    const val IDENTITY = "TMPlayer"
    const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"

    fun trackPath(trackNumber: Long): DBusPath = DBusPath("/com/tmplayer/track/$trackNumber")

    fun playbackStatus(now: NowPlaying?): String = when {
        now == null -> "Stopped"
        now.playing -> "Playing"
        else -> "Paused"
    }

    /** `Metadata`: the track id always; title, length (microseconds) and art when known. */
    fun metadata(now: NowPlaying?): Map<String, Any> {
        if (now == null) return mapOf("mpris:trackid" to DBusPath(NO_TRACK))
        return buildMap {
            put("mpris:trackid", trackPath(now.trackNumber))
            put("xesam:title", now.title)
            if (now.durationMs > 0) put("mpris:length", now.durationMs * 1_000L)
            now.artUrl?.takeIf { it.isNotBlank() }?.let { put("mpris:artUrl", it) }
        }
    }

    /** `org.mpris.MediaPlayer2`, which never changes. */
    fun rootProperties(): Map<String, Any> = mapOf(
        "CanQuit" to false,
        "CanRaise" to true,
        "HasTrackList" to false,
        "Identity" to IDENTITY,
        "SupportedUriSchemes" to emptyList<String>(),
        "SupportedMimeTypes" to emptyList<String>(),
    )

    /**
     * `org.mpris.MediaPlayer2.Player` except `Position`, which the spec excludes from change
     * signals (clients extrapolate it from `Rate` and listen for `Seeked`).
     */
    fun playerProperties(now: NowPlaying?): Map<String, Any> = mapOf(
        "PlaybackStatus" to playbackStatus(now),
        "Rate" to 1.0,
        "MinimumRate" to 1.0,
        "MaximumRate" to 1.0,
        "Metadata" to metadata(now),
        "CanGoNext" to (now?.canGoNext ?: false),
        "CanGoPrevious" to (now?.canGoPrevious ?: false),
        "CanPlay" to (now != null),
        "CanPause" to (now != null),
        "CanSeek" to (now != null && now.durationMs > 0),
        "CanControl" to true,
    )

    /** `Position` in microseconds at [nowNanos]. */
    fun positionUs(now: NowPlaying?, nowNanos: Long): Long = (now?.positionAt(nowNanos) ?: 0L) * 1_000L

    /** The player properties whose value differs between [before] and [after]. */
    fun changed(before: NowPlaying?, after: NowPlaying?): Map<String, Any> {
        val old = playerProperties(before)
        return playerProperties(after).filter { (k, v) -> old[k] != v }
    }

    /** Wraps a plain value in the D-Bus variant the spec's type for it needs. */
    fun variant(value: Any): Variant<*> = when (value) {
        is Map<*, *> -> Variant(value.entries.associate { (k, v) -> k as String to variant(v!!) }, "a{sv}")
        is List<*> -> Variant(value.map { it as String }, "as")
        else -> Variant(value)
    }

    fun variants(values: Map<String, Any>): Map<String, Variant<*>> = values.mapValues { variant(it.value) }
}

/** Introspection type for `Metadata`. */
internal interface MprisMetadataType : TypeRef<Map<String, Variant<*>>>

/** Introspection type for the string list properties. */
internal interface MprisStringListType : TypeRef<List<String>>

@Suppress("FunctionName")
@DBusInterfaceName(MprisMapping.ROOT)
@DBusProperty(name = "CanQuit", type = Boolean::class, access = Access.READ, emitChangeSignal = EmitChangeSignal.CONST)
@DBusProperty(name = "CanRaise", type = Boolean::class, access = Access.READ, emitChangeSignal = EmitChangeSignal.CONST)
@DBusProperty(name = "HasTrackList", type = Boolean::class, access = Access.READ, emitChangeSignal = EmitChangeSignal.CONST)
@DBusProperty(name = "Identity", type = String::class, access = Access.READ, emitChangeSignal = EmitChangeSignal.CONST)
@DBusProperty(name = "SupportedUriSchemes", type = MprisStringListType::class, access = Access.READ, emitChangeSignal = EmitChangeSignal.CONST)
@DBusProperty(name = "SupportedMimeTypes", type = MprisStringListType::class, access = Access.READ, emitChangeSignal = EmitChangeSignal.CONST)
internal interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@Suppress("FunctionName")
@DBusInterfaceName(MprisMapping.PLAYER)
@DBusProperty(name = "PlaybackStatus", type = String::class, access = Access.READ)
@DBusProperty(name = "Rate", type = Double::class, access = Access.READ)
@DBusProperty(name = "MinimumRate", type = Double::class, access = Access.READ)
@DBusProperty(name = "MaximumRate", type = Double::class, access = Access.READ)
@DBusProperty(name = "Metadata", type = MprisMetadataType::class, access = Access.READ)
// The spec: Position never signals a change; clients extrapolate and listen for Seeked.
@DBusProperty(name = "Position", type = Long::class, access = Access.READ, emitChangeSignal = EmitChangeSignal.FALSE)
@DBusProperty(name = "CanGoNext", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPlay", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPause", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanSeek", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanControl", type = Boolean::class, access = Access.READ)
internal interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)

    /** Sent when the position jumps; the argument is the new position in microseconds. */
    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

/**
 * MPRIS 2 on the session bus as `org.mpris.MediaPlayer2.tmplayer` (or `...tmplayer.instance<pid>`
 * if that name is taken), object `/org/mpris/MediaPlayer2`, with the Root and Player interfaces and
 * `PropertiesChanged` and `Seeked` signals. Media keys reach it through GNOME's and KDE's media
 * key handling, `playerctl`, and anything else that speaks MPRIS.
 */
internal class MprisMediaSession private constructor(
    private val conn: DBusConnection,
    private val callbacks: MediaSessionCallbacks,
    private val clock: () -> Long,
) : MediaSession {

    private val lock = Any()
    private var now: NowPlaying? = null
    private var nextTrack = 1L

    @Volatile
    private var released = false

    /** The bus name actually owned; for logs and the live check. */
    var busName: String = MprisMapping.BUS_NAME
        private set

    private val exported = Exported()

    override fun update(
        title: String,
        durationMs: Long,
        positionMs: Long,
        playing: Boolean,
        artUrl: String?,
        canGoNext: Boolean,
        canGoPrevious: Boolean,
    ) {
        if (released) return
        val at = clock()
        val (changed, seeked) = synchronized(lock) {
            val before = now
            val track = if (before == null || before.title != title) nextTrack++ else before.trackNumber
            val after = NowPlaying(
                trackNumber = track,
                title = title,
                durationMs = durationMs.coerceAtLeast(0),
                positionMs = positionMs.coerceAtLeast(0),
                playing = playing,
                artUrl = artUrl,
                canGoNext = canGoNext,
                canGoPrevious = canGoPrevious,
                atNanos = at,
            )
            now = after
            MprisMapping.changed(before, after) to (before != null && before.seekedTo(after))
        }
        emit(changed)
        if (seeked) send { MprisPlayer.Seeked(MprisMapping.OBJECT_PATH, positionMs * 1_000L) }
    }

    override fun clear() {
        if (released) return
        val changed = synchronized(lock) {
            val before = now
            now = null
            MprisMapping.changed(before, null)
        }
        emit(changed)
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { conn.releaseBusName(busName) }
        runCatching { conn.unExportObject(MprisMapping.OBJECT_PATH) }
        runCatching { conn.close() }
    }

    private fun emit(changed: Map<String, Any>) {
        if (changed.isEmpty()) return
        send {
            Properties.PropertiesChanged(MprisMapping.OBJECT_PATH, MprisMapping.PLAYER, MprisMapping.variants(changed), emptyList())
        }
    }

    private fun send(signal: () -> DBusSignal) {
        if (released) return
        runCatching { conn.sendMessage(signal()) }.onFailure { Logger.w(TAG, "signal failed: ${it.message}") }
    }

    private fun snapshot(): NowPlaying? = synchronized(lock) { now }

    /** The object on the bus. Its methods run on dbus-java's worker threads. */
    private inner class Exported : MprisRoot, MprisPlayer, Properties {

        override fun getObjectPath(): String = MprisMapping.OBJECT_PATH

        override fun Raise() = callbacks.onRaise()
        override fun Quit() = Unit

        override fun Next() = callbacks.onNext()
        override fun Previous() = callbacks.onPrevious()
        override fun Pause() = callbacks.onPause()
        override fun PlayPause() = callbacks.onPlayPause()
        override fun Stop() = callbacks.onStop()
        override fun Play() = callbacks.onPlay()

        override fun Seek(offset: Long) {
            if (snapshot() != null) callbacks.onSeekBy(offset / 1_000L)
        }

        override fun SetPosition(trackId: DBusPath, position: Long) {
            val current = snapshot() ?: return
            // The spec: ignore a stale track id, and a position outside the track.
            if (trackId.path != MprisMapping.trackPath(current.trackNumber).path) return
            val ms = position / 1_000L
            if (ms < 0 || (current.durationMs > 0 && ms > current.durationMs)) return
            callbacks.onSeekTo(ms)
        }

        override fun OpenUri(uri: String) = Unit

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A {
            val all = GetAll(interfaceName)
            return (all[propertyName] ?: throw UnknownProperty("No property $propertyName on $interfaceName")) as A
        }

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
            throw PropertyReadOnly("$propertyName is read only")
        }

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            MprisMapping.ROOT -> MprisMapping.variants(MprisMapping.rootProperties())
            MprisMapping.PLAYER -> {
                val current = snapshot()
                MprisMapping.variants(MprisMapping.playerProperties(current)) +
                    ("Position" to Variant(MprisMapping.positionUs(current, clock())))
            }
            else -> throw UnknownInterface("No interface $interfaceName")
        }
    }

    companion object {
        private const val TAG = "Mpris"

        /** Connects to the session bus, exports the object and claims the name. Throws without a bus. */
        fun start(callbacks: MediaSessionCallbacks, clock: () -> Long = System::nanoTime): MprisMediaSession {
            val conn = DBusConnectionBuilder.forSessionBus().withShared(false).build()
            try {
                val session = MprisMediaSession(conn, callbacks, clock)
                conn.exportObject(MprisMapping.OBJECT_PATH, session.exported)
                session.busName = try {
                    conn.requestBusName(MprisMapping.BUS_NAME)
                    MprisMapping.BUS_NAME
                } catch (e: DBusException) {
                    // Another copy (a dev build next to the installed one) owns the plain name.
                    val alt = "${MprisMapping.BUS_NAME}.instance${OsInfo.pid}"
                    conn.requestBusName(alt)
                    alt
                }
                Logger.i(TAG, "registered as ${session.busName}")
                return session
            } catch (t: Throwable) {
                runCatching { conn.close() }
                throw t
            }
        }
    }
}

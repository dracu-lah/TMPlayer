package com.tmplayer.player

/**
 * Which player opens a video, from Settings, Playback, Default player (PR4): TMPlayer's own
 * ([TmPlayer], the default), a question each time ([Ask]), or one installed app ([App]).
 *
 * Stored as text: `tmplayer`, `ask` or `app:<package>/<class>`. The text comes from a settings
 * file or a backup written by another build or another phone, so [parse] trusts none of it: a
 * blank, unknown, truncated or absurdly long value is [TmPlayer], never an exception, and a
 * package that is not installed here is dealt with when it is used ([ExternalPlay.plan]), not
 * when it is read.
 */
sealed interface PlayerChoice {

    /** TMPlayer's own player, as always. */
    data object TmPlayer : PlayerChoice

    /** The chooser, every time. */
    data object Ask : PlayerChoice

    /** One app, by the component its `ACTION_VIEW` filter lives in: an explicit intent, never an implicit one. */
    data class App(val packageName: String, val className: String) : PlayerChoice

    /** As stored. */
    fun encode(): String = when (this) {
        TmPlayer -> TMPLAYER
        Ask -> ASK
        is App -> "$APP_PREFIX$packageName/$className"
    }

    companion object {
        const val TMPLAYER = "tmplayer"
        const val ASK = "ask"
        const val APP_PREFIX = "app:"
        private const val MAX_NAME = 255

        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val classPattern = Regex("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*")

        /** A Java package name, or false: what an explicit intent may be aimed at. */
        fun isPackage(name: String): Boolean = name.length <= MAX_NAME && packagePattern.matches(name)

        /** A fully qualified class name. */
        fun isClass(name: String): Boolean = name.length <= MAX_NAME && classPattern.matches(name)

        /** The stored choice; anything unknown, blank, malformed or missing means [TmPlayer]. */
        fun parse(stored: String?): PlayerChoice {
            val text = stored?.trim() ?: return TmPlayer
            if (text.length > 2 * MAX_NAME + APP_PREFIX.length + 1) return TmPlayer
            return when {
                text.equals(TMPLAYER, ignoreCase = true) -> TmPlayer
                text.equals(ASK, ignoreCase = true) -> Ask
                text.startsWith(APP_PREFIX) -> appOrNull(text.removePrefix(APP_PREFIX)) ?: TmPlayer
                else -> TmPlayer
            }
        }

        private fun appOrNull(body: String): App? {
            val slash = body.indexOf('/')
            if (slash <= 0 || slash == body.lastIndex) return null
            val pkg = body.substring(0, slash)
            val cls = body.substring(slash + 1)
            if (!isPackage(pkg) || !isClass(cls)) return null
            return App(pkg, cls)
        }
    }
}

/**
 * An installed app that takes `ACTION_VIEW` for any video type, as `queryIntentActivities` found it.
 *
 * [acceptsFile] is a `content://` handoff (a finished download through the file provider),
 * [acceptsStream] an `http://` address (a video that is still coming down, through
 * [com.tmplayer.cast.LoopbackStream]). Most players take both.
 */
data class ExternalApp(
    val packageName: String,
    val className: String,
    val label: String,
    val acceptsFile: Boolean = true,
    val acceptsStream: Boolean = true,
) {
    val choice: PlayerChoice.App get() = PlayerChoice.App(packageName, className)
}

/** The list the chooser and Settings show, and the rules for what a press on a video does. */
object ExternalPlay {

    /** VLC and MX Player lead the list (PR4), in this order. */
    val FIRST = listOf("org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro")

    /** A name for a package that is no longer installed (its label is gone with it): the well known ones, else the package. */
    fun nameOf(packageName: String): String = when (packageName) {
        "org.videolan.vlc" -> "VLC"
        "com.mxtech.videoplayer.ad" -> "MX Player"
        "com.mxtech.videoplayer.pro" -> "MX Player Pro"
        "is.xyz.mpv" -> "mpv"
        "com.brouken.player" -> "Just Player"
        "org.xbmc.kodi" -> "Kodi"
        else -> packageName
    }

    /** An external app that closes sooner than this after it opened, without playing, did not work. */
    const val EARLY_CLOSE_MS = 2_000L

    /** Bounds the list: a device with a hundred video apps has no use for a hundred lines. */
    const val MAX_APPS = 40

    /**
     * [found] ready to show: this app left out ([ownPackage] and its test builds), one line per
     * component, [FIRST] first and the rest by name, at most [MAX_APPS]. A blank label falls back
     * to the package name.
     */
    fun order(found: List<ExternalApp>, ownPackage: String): List<ExternalApp> {
        val own = ownPackage.takeIf { it.isNotBlank() }
        val clean = found
            .filter { it.packageName.isNotBlank() && it.className.isNotBlank() }
            .filterNot { own != null && (it.packageName == own || it.packageName.startsWith("$own.")) }
            .map { if (it.label.isBlank()) it.copy(label = it.packageName) else it }
        // One line per app: two components of the same app (VLC has several) merge into the
        // first, which keeps what either one accepts.
        val merged = LinkedHashMap<String, ExternalApp>()
        for (app in clean) {
            val have = merged[app.packageName]
            merged[app.packageName] = if (have == null) app else have.copy(
                acceptsFile = have.acceptsFile || app.acceptsFile,
                acceptsStream = have.acceptsStream || app.acceptsStream,
            )
        }
        return merged.values
            .sortedWith(
                compareBy<ExternalApp> { FIRST.indexOf(it.packageName).let { i -> if (i < 0) FIRST.size else i } }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName },
            )
            .take(MAX_APPS)
    }

    /**
     * The names to draw: the app's label, and for a label two apps share (two builds of one
     * player, a fork) the package after it, so the viewer can tell them apart.
     */
    fun displayNames(apps: List<ExternalApp>): List<String> {
        val count = apps.groupingBy { it.label.lowercase() }.eachCount()
        return apps.map { if ((count[it.label.lowercase()] ?: 0) > 1) "${it.label} (${it.packageName})" else it.label }
    }

    /** Why a press went to TMPlayer's own player. */
    enum class BuiltInWhy {
        /** The choice says so. */
        Chosen,

        /** The kids lock is on: only TMPlayer's own player, until the lock allows more. */
        KidsLock,

        /** A Cast session is running: TMPlayer's player owns it. */
        Casting,

        /** Ask, but no other player is installed: nothing to choose between. */
        NoApps,

        /** Another app cannot take this video (a video in several pieces that is not all here). */
        CannotHand,
    }

    sealed interface Plan {
        data class BuiltIn(val why: BuiltInWhy) : Plan

        /** Show the chooser over [apps]; [gone] is the default app that is no longer installed, if that is why. */
        data class Ask(val apps: List<ExternalApp>, val gone: PlayerChoice.App? = null) : Plan

        data class Launch(val app: ExternalApp) : Plan
    }

    /**
     * What a press on a video does. [installed] is what is on this device now (already through
     * [order]), so a choice restored from a backup, or an app removed since, is checked against
     * it here: a default that is not installed asks again rather than failing. [forceAsk] is the
     * viewer's own "Open in another app", which always shows the chooser. [needsStream] is a video
     * that is not all on disk, which only an app that takes an address can play; [cannotHand] a
     * video no other app could read at all.
     */
    fun plan(
        choice: PlayerChoice,
        installed: List<ExternalApp>,
        kidsLocked: Boolean,
        casting: Boolean,
        forceAsk: Boolean = false,
        needsStream: Boolean = false,
        cannotHand: Boolean = false,
    ): Plan {
        if (kidsLocked) return Plan.BuiltIn(BuiltInWhy.KidsLock)
        if (casting) return Plan.BuiltIn(BuiltInWhy.Casting)
        if (choice == PlayerChoice.TmPlayer && !forceAsk) return Plan.BuiltIn(BuiltInWhy.Chosen)
        if (cannotHand) return Plan.BuiltIn(BuiltInWhy.CannotHand)
        val usable = if (needsStream) installed.filter { it.acceptsStream } else installed
        if (usable.isEmpty()) return Plan.BuiltIn(BuiltInWhy.NoApps)
        if (choice is PlayerChoice.App && !forceAsk) {
            val app = usable.firstOrNull { it.packageName == choice.packageName && it.className == choice.className }
                // The same app after an update that renamed its activity: still the viewer's choice.
                ?: usable.firstOrNull { it.packageName == choice.packageName }
            return if (app != null) Plan.Launch(app) else Plan.Ask(usable, gone = choice)
        }
        return Plan.Ask(usable)
    }

    /**
     * Whether a return to TMPlayer [afterMs] after the handover means the app did not play: it
     * came back within [EARLY_CLOSE_MS] and reported no position. A clock that went backwards is not an early close.
     */
    fun closedTooFast(afterMs: Long, reportedPositionMs: Long?): Boolean =
        afterMs in 0 until EARLY_CLOSE_MS && (reportedPositionMs ?: 0L) <= 0L

    /**
     * Whether a stream handed to an app is let go: only after the app came back, and then only
     * when nothing reads it any more ([holdsWindow] false), so a video still playing in a floating
     * window is not cut off. Never for a stream that already closed itself.
     */
    fun streamShouldClose(returned: Boolean, running: Boolean, holdsWindow: Boolean): Boolean =
        running && returned && !holdsWindow

    /**
     * A position an app reported on return, from whichever extras it has: the first that is a
     * whole, non negative number of milliseconds inside the video (or inside two days when the
     * length is not known). Everything else is null, because the extras are another app's.
     */
    fun positionFrom(candidates: List<Long?>, durationMs: Long): Long? {
        val limit = if (durationMs > 0) durationMs else MAX_UNKNOWN_MS
        return candidates.firstOrNull { it != null && it in 1..limit }
    }

    private const val MAX_UNKNOWN_MS = 2L * 24 * 60 * 60 * 1000

    /** A stream to another app keeps what it reads on the disk, so room is asked for up to this much of the video. */
    const val STREAM_ROOM_CAP = 2L * 1024 * 1024 * 1024

    /** What a stream of [remainingBytes] asks the disk for: the rest of the video, at most [STREAM_ROOM_CAP]. */
    fun streamNeeds(remainingBytes: Long): Long = remainingBytes.coerceIn(0L, STREAM_ROOM_CAP)

    /** Whether [freeBytes] is enough to stream [remainingBytes] to another app; a negative free reading says no. */
    fun hasRoomToStream(remainingBytes: Long, freeBytes: Long): Boolean = freeBytes >= streamNeeds(remainingBytes)
}

/** One extra for an app's intent, typed, so the Android side only has to put it and a test can read it. */
sealed interface ExternalExtra {
    val key: String

    data class Text(override val key: String, val value: String) : ExternalExtra
    data class Whole(override val key: String, val value: Int) : ExternalExtra
    data class Long64(override val key: String, val value: Long) : ExternalExtra
    data class Flag(override val key: String, val value: Boolean) : ExternalExtra
}

/**
 * What each app can be told: the title, where to start and which subtitle to open with.
 *
 * Extra names are the ones each app documents; the ones marked unverified were read from the
 * app's public documentation or source and have not been tried on a real install (the plan
 * leaves that check to a device).
 */
object ExternalExtras {

    fun forApp(
        packageName: String,
        title: String,
        positionMs: Long,
        subtitleUri: String?,
    ): List<ExternalExtra> {
        val position = positionMs.coerceIn(0L, MAX_POSITION_MS)
        val cleanTitle = title.take(MAX_TITLE)
        val subtitle = subtitleUri?.takeIf { it.isNotBlank() && it.length <= MAX_URI }
        return buildList {
            if (cleanTitle.isNotBlank()) add(ExternalExtra.Text("title", cleanTitle))
            when {
                // VLC for Android: `title`, `position` (Long, ms), `from_start`, `subtitles_location`;
                // it answers with `extra_position` on org.videolan.vlc.player.result. Names UNVERIFIED on a real install.
                packageName == "org.videolan.vlc" -> {
                    if (position > 0) add(ExternalExtra.Long64("position", position))
                    if (subtitle != null) add(ExternalExtra.Text("subtitles_location", subtitle))
                }
                // MX Player (free and Pro): `title`, `position` (Int, ms), `return_result`, `subs`
                // (Uri array), `subs.enable`; it answers `position` and `end_by`. UNVERIFIED on a real install.
                packageName.startsWith("com.mxtech.videoplayer") -> {
                    if (position > 0) add(ExternalExtra.Whole("position", position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                    add(ExternalExtra.Flag("return_result", true))
                }
                // mpv-android and Just Player read `position` in milliseconds as an Int; UNVERIFIED.
                packageName == "is.xyz.mpv" || packageName == "com.brouken.player" ->
                    if (position > 0) add(ExternalExtra.Whole("position", position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                // Anyone else gets the title only: a guessed position extra is worse than none.
                else -> Unit
            }
        }
    }

    /** MX Player's `subs` extra is a Uri array, which a typed [ExternalExtra] cannot carry: the app side adds it. */
    fun takesSubtitleArray(packageName: String): Boolean = packageName.startsWith("com.mxtech.videoplayer")

    private const val MAX_TITLE = 200
    private const val MAX_URI = 2048
    private const val MAX_POSITION_MS = 48L * 60 * 60 * 1000
}

/**
 * Settings, Playback, Advanced, Tunneled playback: Auto leaves it to the device's own rules,
 * On and Off force it (a television's way of keeping the video clock with the audio's).
 */
enum class TunneledMode(val key: String) {
    Auto("auto"),
    On("on"),
    Off("off"),
    ;

    companion object {
        /** The stored mode; anything unknown, blank or missing is [Auto]. */
        fun from(stored: String?): TunneledMode {
            val clean = stored?.trim()?.lowercase() ?: return Auto
            return entries.firstOrNull { it.key == clean } ?: Auto
        }
    }
}

/**
 * The engines Settings, Playback offers (PR5): Automatic and ExoPlayer always, VLC only where
 * [EngineSelector] could run it ([EngineInput.vlcAvailable], the native library). A stored VLC on a
 * device without it stays stored (a backup from another phone, a build that gains the library
 * later) and plays in ExoPlayer meanwhile ([EngineReason.VlcUnavailable]); Settings says so.
 */
object EngineOptions {

    fun offered(vlcAvailable: Boolean): List<EngineChoice> =
        if (vlcAvailable) listOf(EngineChoice.Automatic, EngineChoice.ExoPlayer, EngineChoice.Vlc)
        else listOf(EngineChoice.Automatic, EngineChoice.ExoPlayer)

    /** True when the viewer's stored VLC cannot be honoured here, so the row has to say what plays instead. */
    fun storedButUnavailable(stored: EngineChoice, vlcAvailable: Boolean): Boolean = stored == EngineChoice.Vlc && !vlcAvailable
}

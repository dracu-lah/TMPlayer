package com.tmplayer.data

/**
 * When Android installs an update by itself (HX5): "Install updates by itself" in Settings, on
 * Android 12 and later, in the GitHub build. The rules are here, free of Android, so they can be
 * tested; the phone and the TV run them from `AutoUpdater` in :app.
 *
 * An install ends the app's process, so it only ever happens while nobody is using the app: in the
 * background, with nothing playing, casting, downloading or saving to a folder. It also costs a
 * download of tens of megabytes, so only on an unmetered network, and with the battery charging or
 * not low. One try per version, and after a try a [BACKOFF_MS] wait before the next one, so a run
 * of test versions that all fail stops on its own rather than downloading each.
 */
object AutoInstall {

    /** How long the app has to have been out of sight before an install is considered. */
    const val IDLE_MS = 3 * 60_000L

    /** The least time between two tries, whatever their versions. */
    const val BACKOFF_MS = 12 * 60 * 60_000L

    /** What the device is doing now, as the platform reads it. */
    data class Conditions(
        /** "Install updates by itself" is on. */
        val switchOn: Boolean,
        /** Android would install this app's update without asking (`SelfInstaller.canUpdateSilently` in :app). */
        val silentAllowed: Boolean,
        val inFront: Boolean,
        val playing: Boolean,
        val casting: Boolean,
        /** A video downloading, queued, or being saved to a folder. */
        val downloading: Boolean,
        val unmetered: Boolean,
        /** Charging, or the battery not low. */
        val powerOk: Boolean,
        /** How long the app has been out of sight, in milliseconds. */
        val idleMs: Long,
    )

    /** The last try: [version] at [at], epoch milliseconds. */
    data class Tried(val version: String, val at: Long) {
        fun encode(): String = "$version|$at"

        companion object {
            fun decode(text: String): Tried? {
                val version = text.substringBefore('|', "").takeIf { it.isNotBlank() } ?: return null
                val at = text.substringAfter('|', "").toLongOrNull() ?: return null
                return Tried(version, at)
            }
        }
    }

    /** Why nothing happens now, or [Go]. */
    enum class Decision { Go, Off, NotSilent, NothingNew, Skipped, AlreadyTried, Backoff, InUse, Metered, Battery, NotIdle }

    /**
     * Whether to fetch and install [offered] now. [offered] is the release on offer, already
     * filtered by "Get test versions" ([Updates.check]); [skipped] the version the viewer said to
     * skip; [tried] the last try.
     */
    fun decide(
        offered: String?,
        installed: String,
        skipped: String,
        tried: Tried?,
        now: Long,
        conditions: Conditions,
    ): Decision {
        if (!conditions.switchOn) return Decision.Off
        if (!conditions.silentAllowed) return Decision.NotSilent
        if (offered == null || !UpdateFeed.isNewer(offered, installed)) return Decision.NothingNew
        if (offered == skipped) return Decision.Skipped
        if (tried != null) {
            if (tried.version == offered) return Decision.AlreadyTried
            // A clock set back counts as waited, or it would never be due again.
            if (now >= tried.at && now - tried.at < BACKOFF_MS) return Decision.Backoff
        }
        return quiet(conditions) ?: Decision.Go
    }

    /**
     * The checks made again just before the install, after the download: the viewer may have come
     * back meanwhile. Null when it may go ahead.
     */
    fun quiet(conditions: Conditions): Decision? = when {
        conditions.inFront || conditions.playing || conditions.casting || conditions.downloading -> Decision.InUse
        !conditions.unmetered -> Decision.Metered
        !conditions.powerOk -> Decision.Battery
        conditions.idleMs < IDLE_MS -> Decision.NotIdle
        else -> null
    }

    /**
     * Free space an update of [apkBytes] needs: the download in the cache, Android's copy of it in
     * the install session, and room to spare. An unknown size asks for [UNKNOWN_SIZE_BYTES].
     */
    fun spaceNeeded(apkBytes: Long): Long {
        val apk = if (apkBytes > 0) apkBytes else UNKNOWN_SIZE_BYTES
        return apk * 2 + SPARE_BYTES
    }

    const val UNKNOWN_SIZE_BYTES = 64L * 1024 * 1024
    private const val SPARE_BYTES = 32L * 1024 * 1024
}

/**
 * What the app says after an update has replaced it (HX5): "Updated to 1.29.0" once, on the first
 * launch of the new version, and on Android 12 and later a notification to open it again, since
 * the install closed it. The install writes a [Pending] before Android takes the APK; the launch
 * reads it with [onLaunch].
 */
object UpdateRestart {

    /** A pending record older than this, with the old version still running, is dropped. */
    const val STALE_MS = 2L * 24 * 60 * 60_000L

    /**
     * An install handed to Android: [version] at [at]. [session] is Android 12's installer session
     * (which closes the app and has no "Open" button of its own), false for the system installer's
     * screen on Android 6 to 11. [foreground] is an install the viewer started from the popup.
     */
    data class Pending(val version: String, val at: Long, val session: Boolean, val foreground: Boolean) {
        fun encode(): String = listOf(version, at.toString(), flag(session), flag(foreground)).joinToString("|")

        companion object {
            private fun flag(on: Boolean) = if (on) "1" else "0"

            fun decode(text: String): Pending? {
                val parts = text.split('|')
                if (parts.size != 4 || parts[0].isBlank()) return null
                val at = parts[1].toLongOrNull() ?: return null
                return Pending(parts[0], at, parts[2] == "1", parts[3] == "1")
            }
        }
    }

    /** What a launch does: the version to say it was updated to, if any, and whether to forget the record. */
    data class Launch(val updatedTo: String?, val clear: Boolean)

    /**
     * On a launch of [installed] with the stored record [stored] ("" for none):
     *
     * - [installed] at or past the record's version: the update went in. Say so once, and forget it.
     * - An older [installed] with a record under [STALE_MS] old: Android may still be asking, so
     *   keep it. Older than that, or a clock set back: the install never happened; forget it.
     * - A record that does not read: forget it.
     */
    fun onLaunch(stored: String, installed: String, now: Long): Launch {
        if (stored.isEmpty()) return Launch(null, clear = false)
        val pending = Pending.decode(stored) ?: return Launch(null, clear = true)
        if (UpdateFeed.compare(installed, pending.version) >= 0) return Launch(pending.version, clear = true)
        val stale = now < pending.at || now - pending.at > STALE_MS
        return Launch(null, clear = stale)
    }

    /**
     * Whether the broadcast Android sends the new version once it has replaced the old one should
     * bring the app back with a notification: only after an installer session (Android 12 and
     * later), whose install closed the app with no "Open" button, and only for the version that
     * record was about.
     */
    fun notifyAfterReplace(stored: String, installed: String): Boolean {
        val pending = Pending.decode(stored) ?: return false
        return pending.session && UpdateFeed.compare(installed, pending.version) >= 0
    }
}

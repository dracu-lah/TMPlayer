package com.tmplayer.data

/**
 * The update state shown beside the app's version (WP-104), the same on every platform.
 */
sealed interface VersionState {
    /** No update check in this build (a store build, the preview build, iOS): the store keeps it current. */
    data object Off : VersionState

    /** Nothing has been checked yet, so nothing is claimed: the version shows alone. */
    data object None : VersionState
    data object Checking : VersionState

    /** A completed check found nothing newer. */
    data object UpToDate : VersionState

    /** A newer release is out, at some stage of fetching it. [version] is cut to [VersionLine.VERSION_MAX]. */
    data class Available(val version: String) : VersionState
    data object Failed : VersionState
}

/**
 * The three facts a platform gives about this build: [version] as built (`1.28.6-beta.3`, the
 * preview's `1.28.6-test`), [build] (the version code, or on the web the short commit and date) and
 * [device] (the system and model). Nothing about an account.
 */
data class VersionInfo(val version: String, val build: String, val device: String)

/** What the app says about its own version: the line a person copies for support, and the state. */
object VersionLine {
    const val VERSION_MAX = 40
    const val BUILD_MAX = 20
    const val DEVICE_MAX = 80

    /**
     * "TMPlayer 1.28.6 (12806099) Android 14, Google Pixel 8, phone". It takes the three facts and
     * nothing else: no account, profile, chat or path can be passed in, so none can leak. Each part
     * is cut, and a line break in any of them becomes a space.
     */
    fun copyText(version: String, build: String, device: String): String =
        "TMPlayer ${clean(version, VERSION_MAX)} (${clean(build, BUILD_MAX)}) ${clean(device, DEVICE_MAX)}".trimEnd()

    /** A version as shown: one line, at most [VERSION_MAX] characters. */
    fun shown(version: String): String = clean(version, VERSION_MAX)

    /**
     * The version as the compact mark shows it: "v1.28.7". Everything after the first dash or plus
     * (`-beta.3`, the preview's `-test`, a build suffix) is left off, since the full text, the
     * build and the update source are in About and in the copied support line. A version that
     * does not start with a digit (a development build) is shown as built, never invented into a
     * number.
     */
    fun compact(version: String): String {
        val flat = shown(version)
        val core = flat.substringBefore('-').substringBefore('+').trim()
        return if (core.isNotEmpty() && core[0].isDigit()) "v$core" else flat
    }

    /**
     * The state beside the version. "Up to date" needs a completed check ([lastCheck] above 0,
     * epoch milliseconds, 0 for never); before one the version shows alone, never a false claim.
     */
    fun state(update: UpdateState, enabled: Boolean, lastCheck: Long): VersionState = when {
        !enabled -> VersionState.Off
        else -> when (update) {
            is UpdateState.Checking -> VersionState.Checking
            is UpdateState.Available -> VersionState.Available(shown(update.release.version))
            is UpdateState.Downloading -> VersionState.Available(shown(update.release.version))
            is UpdateState.Ready -> VersionState.Available(shown(update.release.version))
            is UpdateState.Installing -> VersionState.Available(shown(update.release.version))
            is UpdateState.Failed -> VersionState.Failed
            is UpdateState.Idle -> if (lastCheck > 0L) VersionState.UpToDate else VersionState.None
        }
    }

    private fun clean(text: String, max: Int): String {
        val flat = buildString(text.length) {
            for (c in text) append(if (c.isISOControl()) ' ' else c)
        }.trim()
        return if (flat.length <= max) flat else flat.take(max).trimEnd()
    }
}

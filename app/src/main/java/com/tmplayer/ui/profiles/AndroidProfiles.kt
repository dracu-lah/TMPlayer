package com.tmplayer.ui.profiles

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.tmplayer.App
import com.tmplayer.data.AccountSwitcher
import com.tmplayer.data.BeforeSwitch
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Profile
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchCache
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.pauseAll
import com.tmplayer.ui.lock.ProfileFlow
import com.tmplayer.ui.lock.ProfilePlatform
import com.tmplayer.ui.settings.forgetProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The profiles on Android (HX88): one [ProfileFlow] for the process, over the process's
 * [AccountSwitcher], so the picker's state outlives a rotation or a recreated activity and is
 * gone with the process (a PIN is asked again after that). Null in a build or a test that never
 * opened the accounts: then nothing of the profiles is drawn.
 */
object AndroidProfiles {
    @Volatile
    private var flow: ProfileFlow? = null

    fun flow(context: Context): ProfileFlow? = flow ?: synchronized(this) {
        flow ?: AccountSwitcher.current()?.let { switcher ->
            ProfileFlow(switcher, AndroidProfilePlatform(context.applicationContext), App.backgroundScope).also { flow = it }
        }
    }

    /** For tests: a flow of their own, or none. */
    internal fun replaceForTest(next: ProfileFlow?) {
        synchronized(this) { flow = next }
    }
}

/** What the phone, the tablet and the TV give [ProfileFlow]. */
internal class AndroidProfilePlatform(private val app: Context) : ProfilePlatform {

    /** The player registers its save with [BeforeSwitch] while it is open; a cast or picture in picture counts too. */
    override fun playing(): Boolean = WatchCache.anyPlaying() || BeforeSwitch.playerCount > 0

    override fun downloading(): Boolean = OfflineDownloads.active.value.values.any { it.busy }

    /**
     * The player saves its place and closes ([BeforeSwitch], registered by PlayerActivity), and
     * this profile's downloads pause, their queue written while its stores are still the open
     * ones. The service is told on the main thread and given a moment to stop what moves.
     */
    /** The downloads this switch's wait paused, in memory only: a Cancel puts them back ([com.tmplayer.data.SwitchPause]). */
    @Volatile internal var held: com.tmplayer.data.SwitchPause.Held? = null

    override suspend fun beforeSwitch(onWaiting: (List<String>) -> Unit) {
        // The downloads pause alongside the other work, under the same deadline.
        // Added to what an earlier wait of the same profile and rows paused: a retry finds nothing moving.
        val stamp = com.tmplayer.data.Accounts.stamp
        val rows = OfflineDownloads.rowsGeneration
        val now = OfflineDownloads.active.value.values.filter { it.busy }
            .map { com.tmplayer.data.SwitchPause.Paused(it.fileId, it.request.chatId, it.request.messageId) }
        held = com.tmplayer.data.SwitchPause.hold(held, stamp, rows, now)
        if (downloading()) withContext(Dispatchers.Main) { runCatching { OfflineDownloads.pauseAll(app) } }
        val pausing = downloading()
        BeforeSwitch.runAll(onWaiting = { what -> onWaiting(if (pausing && downloading()) what + com.tmplayer.i18n.L.profilesWaitingDownloads else what) })
        withTimeoutOrNull(PAUSE_WAIT_MS) {
            while (downloading()) delay(50)
        }
    }

    override suspend fun switchCancelled() {
        val was = held
        held = null
        // Only into the profile and the rows they belong to, while they are still the open ones.
        resume(com.tmplayer.data.SwitchPause.toResume(was, com.tmplayer.data.Accounts.stamp, OfflineDownloads.rowsGeneration, rowsNow()))
    }

    private fun rowsNow(): Map<Int, Pair<Long, Long>> =
        OfflineDownloads.active.value.mapValues { (_, row) -> row.request.chatId to row.request.messageId }

    private suspend fun resume(ids: List<Int>) {
        if (ids.isEmpty()) return
        val runner = com.tmplayer.data.AndroidDownloadRunner(app)
        withContext(Dispatchers.Main) { for (id in ids) runCatching { OfflineDownloads.resume(runner, id) } }
    }

    override suspend fun wipe(profile: Profile, last: Boolean, deleteDownloads: Boolean) {
        // A sign out discards what its wait paused: the next sign in, on the same folder, is a new
        // database whose ids are other videos (HX88). Only that profile's: Remove all accounts
        // wipes the others first, and if it fails the open one's paused downloads still go on.
        if (held?.stamp?.folder == profile.folder) held = null
        // Its results, alerts and Play actions leave the shade with it (HX88).
        com.tmplayer.data.ProfileNotifications.clearProfile(app)
        forgetProfile(app, SettingsStore(app), WatchedStore(app), deleteDownloads, last)
    }

    override val appLock: kotlinx.coroutines.flow.Flow<com.tmplayer.data.AppLockSettings?>
        get() = SettingsStore(app).appLock

    override fun appLockChecker(): com.tmplayer.ui.lock.PinChecker = com.tmplayer.ui.lock.SettingsPinChecker(SettingsStore(app))

    /**
     * Never read on the main thread (no blocking at the first frame): unknown here, so the flow is
     * fail closed until [appLock]'s first value, a DataStore read on its own thread a few
     * milliseconds in. Only the picker (two profiles or more) and the guarded actions wait on it;
     * one profile with no PIN draws its shell at once.
     */
    override fun appLockNow(): com.tmplayer.data.AppLockSettings? = null

    override fun say(text: String) {
        Handler(Looper.getMainLooper()).post { runCatching { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() } }
    }

    private companion object {
        const val PAUSE_WAIT_MS = 3_000L
    }
}

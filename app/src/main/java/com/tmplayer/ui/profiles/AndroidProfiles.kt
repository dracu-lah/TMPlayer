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
    override fun playing(): Boolean = WatchCache.anyPlaying() || BeforeSwitch.count > 0

    override fun downloading(): Boolean = OfflineDownloads.active.value.values.any { it.busy }

    /**
     * The player saves its place and closes ([BeforeSwitch], registered by PlayerActivity), and
     * this profile's downloads pause, their queue written while its stores are still the open
     * ones. The service is told on the main thread and given a moment to stop what moves.
     */
    override suspend fun beforeSwitch() {
        BeforeSwitch.runAll()
        if (!downloading()) return
        withContext(Dispatchers.Main) { runCatching { OfflineDownloads.pauseAll(app) } }
        withTimeoutOrNull(PAUSE_WAIT_MS) {
            while (downloading()) delay(50)
        }
    }

    override suspend fun wipe(profile: Profile, last: Boolean, deleteDownloads: Boolean) {
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

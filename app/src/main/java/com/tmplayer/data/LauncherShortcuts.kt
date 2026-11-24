package com.tmplayer.data

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.tmplayer.MainActivity
import com.tmplayer.R
import com.tmplayer.i18n.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * The launcher's long press shortcuts (WP-42): Continue watching, Search, Downloads, and the chat
 * last played. Published from code rather than a static XML, for two reasons that a file cannot
 * meet: the labels are the app's own, in the language chosen in the app (a resource file would show
 * English everywhere), and the target package follows the build (the test and promo builds carry a
 * suffix a static shortcut would miss). Android 7.1 has shortcuts; below it nothing is published.
 *
 * The last chat is a profile's own data: it is published only while [OutsideWrites.mayWriteNow]
 * says so, and taken away the moment a PIN profile is hidden (locked, or the app left), like every
 * other surface outside the app (docs/agents/multi-account.md).
 */
object LauncherShortcuts {

    const val ID_CONTINUE = "continue"
    const val ID_SEARCH = "search"
    const val ID_DOWNLOADS = "downloads"
    const val ID_LAST_CHAT = "last_chat"

    /** What [MainActivity.EXTRA_OPEN] says for the two shortcuts that are not Downloads (which has its own value). */
    const val OPEN_SEARCH = "search"
    const val OPEN_CONTINUE = "continue"

    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.N_MR1

    /**
     * Which shortcuts to publish, by id: the three always, the last chat only with a chat to name and
     * nothing hidden. Pure, so the rule is tested without a launcher.
     */
    fun planIds(hidden: Boolean, lastChatId: Long, lastChatTitle: String?): List<String> = buildList {
        add(ID_CONTINUE)
        add(ID_SEARCH)
        add(ID_DOWNLOADS)
        if (!hidden && lastChatId != 0L && !lastChatTitle.isNullOrBlank()) add(ID_LAST_CHAT)
    }

    /**
     * Whether the last chat's name must stay off the launcher: the open profile is hidden (PIN locked, or
     * the app away), has a PIN at all (its chat names are its own, whether or not it is unlocked now),
     * a lock hides the app's contents (the widget's rule: [ContinueWidget.lockedBy], asking when the app
     * opens or the kids lock), outside writes are refused, or the lock has not been read yet ([lock]
     * null): never open before it is known.
     */
    fun hiddenFor(lock: AppLockSettings?, profileHidden: Boolean, profileHasPin: Boolean, mayWrite: Boolean): Boolean =
        lock == null || profileHidden || profileHasPin || ContinueWidget.lockedBy(lock) || !mayWrite

    /** Takes the last chat off the launcher, whatever else is published. */
    fun removeLastChat(context: Context) {
        if (!supported()) return
        runCatching { context.getSystemService(ShortcutManager::class.java)?.removeDynamicShortcuts(listOf(ID_LAST_CHAT)) }
    }

    /**
     * Follows the lock for the whole process, not only while a screen is up (the other outside surfaces'
     * way, [OutsideSurfaces]): the chat's shortcut is removed the moment the profile is hidden or a lock
     * covers the app, at the start of the process when that already holds (a lock not yet read counts),
     * and on every switch of profile. The activity publishes it again when nothing hides it.
     */
    fun follow(context: Context, scope: CoroutineScope): Job {
        val app = context.applicationContext
        val hidden = combine(
            SettingsStore(app).appLock.map<AppLockSettings, AppLockSettings?> { it }.onStart { emit(null) },
            ProfileUnlock.hidden(),
        ) { lock, profileHidden ->
            hiddenFor(lock, profileHidden, Accounts.active.pin != null, OutsideWrites.mayWriteNow())
        }.distinctUntilChanged()
        return OutsideSurfaces.follow(scope, hidden = hidden) { removeLastChat(app) }
    }

    /** Sets the shortcuts to [planIds], replacing what was there. A failure leaves them as they were. */
    fun publish(context: Context, lastChatId: Long, lastChatTitle: String?, hidden: Boolean) {
        if (!supported()) return
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val shortcuts = planIds(hidden, lastChatId, lastChatTitle).mapNotNull { id ->
                when (id) {
                    ID_CONTINUE -> build(context, id, L.continueShortcut, R.mipmap.ic_launcher) { it.putExtra(MainActivity.EXTRA_OPEN, OPEN_CONTINUE) }
                    ID_SEARCH -> build(context, id, L.searchShortcut, R.mipmap.ic_launcher) { it.putExtra(MainActivity.EXTRA_OPEN, OPEN_SEARCH) }
                    ID_DOWNLOADS -> build(context, id, L.downloadsShortcut, R.mipmap.ic_launcher) { it.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DOWNLOADS) }
                    ID_LAST_CHAT -> build(context, id, lastChatTitle.orEmpty(), R.mipmap.ic_launcher) {
                        it.putExtra(BackgroundChecks.EXTRA_OPEN_CHAT, lastChatId)
                    }
                    else -> null
                }
            }
            manager.dynamicShortcuts = shortcuts
        }
    }

    private fun build(context: Context, id: String, label: String, icon: Int, extras: (Intent) -> Unit): ShortcutInfo {
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).also(extras)
        return ShortcutInfo.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithResource(context, icon))
            .setIntent(intent)
            .build()
    }
}

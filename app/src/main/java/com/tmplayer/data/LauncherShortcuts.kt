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

    /**
     * Takes the last chat off the launcher, whatever else is published. A copy pinned to the home
     * screen (dragged out of the long press list) is not removed by that and would keep the chat's
     * name (SEC2-03), so every copy is first rewritten with a plain label and no chat in its intent:
     * [ShortcutManager.updateShortcuts] reaches pinned ones too. [publish] names the chat again when
     * nothing hides it.
     */
    fun removeLastChat(context: Context) {
        if (!supported()) return
        // profile-notifications: this only removes and relabels, so it names nothing and runs whenever the chat must go
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            unnameLastChat(context, manager)
            manager.removeDynamicShortcuts(listOf(ID_LAST_CHAT))
        }
    }

    /** Every copy of the last chat's shortcut, pinned ones too, as "Last chat" that only opens the app. */
    // profile-notifications: the signature only takes the manager; the body below relabels and names nothing
    private fun unnameLastChat(context: Context, manager: ShortcutManager) {
        // profile-notifications: relabels to the generic "Last chat" first, so no name stays on a pinned copy
        runCatching { manager.updateShortcuts(listOf(build(context, ID_LAST_CHAT, L.chatsLastShortcut, R.mipmap.ic_launcher) { })) }
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
            // A PIN set on the open profile hides its chat at once, not at the next lock change (SEC2-11).
            ProfileUnlock.activeHasPin(),
        ) { lock, profileHidden, hasPin ->
            hiddenFor(lock, profileHidden, hasPin, OutsideWrites.mayWriteNow())
        }.distinctUntilChanged()
        return OutsideSurfaces.follow(scope, hidden = hidden) { removeLastChat(app) }
    }

    /** Sets the shortcuts to [planIds], replacing what was there. A failure leaves them as they were. */
    fun publish(context: Context, lastChatId: Long, lastChatTitle: String?, hidden: Boolean) {
        if (!supported()) return
        runCatching {
            // profile-notifications: planIds leaves the last chat out while [hidden] (hiddenFor, one rule with the widget's)
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val ids = planIds(hidden, lastChatId, lastChatTitle)
            // A pinned copy outlives the dynamic list: it loses the chat's name with it (SEC2-03).
            if (ID_LAST_CHAT !in ids) unnameLastChat(context, manager)
            val shortcuts = ids.mapNotNull { id ->
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

    // profile-notifications: builds only what planIds lists, which has no chat while hidden
    private fun build(context: Context, id: String, label: String, icon: Int, extras: (Intent) -> Unit): ShortcutInfo {
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).also(extras)
        // profile-notifications: same as build above, the plan decides what is built
        return ShortcutInfo.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithResource(context, icon))
            .setIntent(intent)
            .build()
    }
}

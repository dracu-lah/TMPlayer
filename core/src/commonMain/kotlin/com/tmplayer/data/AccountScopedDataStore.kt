package com.tmplayer.data

import androidx.datastore.core.DataStore
import com.tmplayer.platform.Logger
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * A preferences store as the open profile sees it (HX88, docs/agents/multi-account.md).
 *
 * The adopted profile ([folder] null) reads and writes the account keys under their own names, so
 * its store is the one of the version before; any other profile's account keys live as
 * `@<folder>:<name>` and read back under the plain name. The device's keys are everyone's. A write
 * keeps every other profile's keys as they were. With the adopted profile alone and no marked key
 * in the file the store passes straight through.
 */
class AccountScopedDataStore(
    private val delegate: DataStore<Preferences>,
    private val folder: StateFlow<String?> = Accounts.activeFolder,
    private val isAccount: (String) -> Boolean = AccountKeys::isAccount,
    private val stamp: () -> AccountStamp = { Accounts.stamp },
) : DataStore<Preferences> {

    override val data: Flow<Preferences> = combine(delegate.data, folder) { prefs, f -> AccountViews.view(prefs, f, isAccount) }

    /**
     * A write for the profile of the calling coroutine's [AccountOwner], or for the one open now
     * when it has none. Work of a profile no longer open writes nothing: its edit is not even run.
     * The folder is taken once, so the view the edit reads and the keys it writes are one profile's,
     * whatever switch happens meanwhile.
     */
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val owner = currentCoroutineContext()[AccountOwner]?.stamp
        // A write addressed to one profile's keys on purpose (AccountAddress): always that profile's.
        val address = currentCoroutineContext()[AccountAddress]
        val f = if (address != null) address.folder else owner?.folder ?: folder.value
        val stored = delegate.updateData { stored ->
            if (address == null && owner != null && owner != stamp()) {
                Logger.i(TAG, "a write for a profile no longer open was dropped")
                stored
            } else {
                AccountViews.merge(stored, transform(AccountViews.view(stored, f, isAccount)), f, isAccount)
            }
        }
        return AccountViews.view(stored, f, isAccount)
    }

    /** Every profile's view of the store now: the adopted one's (null) and each `@folder:` one's. */
    suspend fun everyProfileNow(): Map<String?, Preferences> {
        val raw = delegate.data.first()
        val folders = listOf<String?>(null) + raw.asMap().keys
            .mapNotNull { key -> key.name.takeIf { it.startsWith(AccountKeys.MARK) }?.drop(1)?.substringBefore(':') }
            .distinct()
        return folders.associateWith { AccountViews.view(raw, it, isAccount) }
    }

    /** Takes a removed profile's keys out ([folder] "" is the adopted profile's plain account keys). */
    suspend fun purge(folder: String) {
        delegate.updateData { stored -> AccountViews.without(stored, folder, isAccount) }
    }

    companion object {
        /** Every key is the account's: the watched list, Watch later, the named lists, bookmarks. */
        val ALL_ACCOUNT: (String) -> Boolean = { true }

        /** The process's store over [delegate], cleared of each removed profile's keys ([Accounts.onPurge]). */
        fun opened(delegate: DataStore<Preferences>, isAccount: (String) -> Boolean = AccountKeys::isAccount): DataStore<Preferences> =
            AccountScopedDataStore(delegate, isAccount = isAccount).also { store -> Accounts.onPurge(store::purge) }

        private const val TAG = "AccountScopedDataStore"
    }
}

/** The two halves of [AccountScopedDataStore], apart from any store so a test can call them. */
internal object AccountViews {

    private fun marked(prefs: Preferences): Boolean = prefs.asMap().keys.any { it.name.startsWith(AccountKeys.MARK) }

    /** What the profile on [folder] sees of [prefs]. */
    fun view(prefs: Preferences, folder: String?, isAccount: (String) -> Boolean): Preferences {
        if (folder == null && !marked(prefs)) return prefs
        val mark = folder?.let(AccountKeys::markOf)
        val out = mutablePreferencesOf()
        for ((key, value) in prefs.asMap()) {
            val name = key.name
            if (name.startsWith(AccountKeys.MARK)) {
                if (mark != null && name.startsWith(mark)) {
                    val plain = name.removePrefix(mark)
                    if (isAccount(plain)) out.putAny(plain, value)
                }
            } else if (folder == null || !isAccount(name)) {
                out.putAny(name, value)
            }
        }
        return out.toPreferences()
    }

    /** [stored] with the view's part replaced by [edited], what the profile on [folder] wrote. */
    fun merge(stored: Preferences, edited: Preferences, folder: String?, isAccount: (String) -> Boolean): Preferences {
        if (folder == null && !marked(stored)) return edited
        val mark = folder?.let(AccountKeys::markOf)
        val out = mutablePreferencesOf()
        for ((key, value) in stored.asMap()) {
            val name = key.name
            val owned = if (name.startsWith(AccountKeys.MARK)) mark != null && name.startsWith(mark) else folder == null || !isAccount(name)
            if (!owned) out.putAny(name, value)
        }
        for ((key, value) in edited.asMap()) {
            val name = key.name
            if (name.startsWith(AccountKeys.MARK)) continue
            if (mark != null && isAccount(name)) out.putAny(mark + name, value) else out.putAny(name, value)
        }
        return out.toPreferences()
    }

    /** [stored] without the keys of the profile on [folder] ("" for the adopted profile's plain ones). */
    fun without(stored: Preferences, folder: String, isAccount: (String) -> Boolean): Preferences {
        val mark = folder.takeIf { it.isNotEmpty() }?.let(AccountKeys::markOf)
        val doomed = stored.asMap().keys.filter { key ->
            if (mark != null) key.name.startsWith(mark) else !key.name.startsWith(AccountKeys.MARK) && isAccount(key.name)
        }
        if (doomed.isEmpty()) return stored
        return stored.toMutablePreferences().apply { doomed.forEach { remove(it) } }.toPreferences()
    }

    /** Writes [value] under [name] with the key type its value has. */
    fun MutablePreferences.putAny(name: String, value: Any) {
        when (value) {
            is Boolean -> this[booleanPreferencesKey(name)] = value
            is Int -> this[intPreferencesKey(name)] = value
            is Long -> this[longPreferencesKey(name)] = value
            is Float -> this[floatPreferencesKey(name)] = value
            is Double -> this[doublePreferencesKey(name)] = value
            is String -> this[stringPreferencesKey(name)] = value
            is ByteArray -> this[byteArrayPreferencesKey(name)] = value
            is Set<*> -> this[stringSetPreferencesKey(name)] = value.mapTo(HashSet()) { it.toString() }
        }
    }
}

/**
 * A store write addressed to one profile's keys on purpose (HX88): the record of a file that a
 * job of that profile has already moved, written for its owner even after a switch, so the record
 * never points at the original that is gone. Only for that: everything else carries [AccountOwner].
 */
class AccountAddress(val folder: String?) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<AccountAddress>
}

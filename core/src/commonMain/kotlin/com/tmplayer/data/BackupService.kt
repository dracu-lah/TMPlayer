package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.online.MetaSettings
import com.tmplayer.online.MetaSettingsStore

/**
 * Backup and restore against the real stores, the same on the phone, the TV and the desktop. Where
 * the file comes from and goes to is each platform's business; this takes and gives text.
 *
 * [metadata] is null where posters were never set up, in which case their switches are neither
 * saved nor restored; [bookmarks] null (an older caller, a test) leaves bookmarks out the same way.
 */
class BackupService(
    private val settings: SettingsStore,
    private val watched: WatchedStore,
    private val metadata: MetaSettingsStore?,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val bookmarks: BookmarkStore? = null,
) {
    /** The file to suggest saving as, for today. */
    fun fileName(): String = Backups.fileName(now())

    /** This device as a backup file's text. */
    suspend fun export(): String = Backups.encode(
        Backups.collect(
            settings = settings.rawEntries(),
            watched = watched.rawEntries(),
            metadata = metadata?.now ?: MetaSettings(),
            createdAt = now(),
            appVersion = appVersion,
            bookmarks = bookmarks?.all().orEmpty(),
        ).let { if (metadata == null) it.copy(metadata = emptyMap()) else it },
    )

    fun read(text: String): BackupRead = Backups.decode(text)

    /** What [restore] would change, for the prompt that asks first. */
    suspend fun plan(backup: Backup): RestorePlan = Backups.plan(
        backup = backup,
        settings = settings.rawEntries(),
        watched = watched.rawEntries(),
        metadata = metadata?.now ?: MetaSettings(),
        bookmarks = bookmarks?.all().orEmpty(),
    )

    /** Merges [backup] in. Each store is written in one edit; nothing is removed from either. */
    suspend fun restore(backup: Backup) {
        settings.restoreEntries { device -> Backups.settingsWrites(device, backup) }
        watched.restoreEntries { device -> Backups.watchedWrites(device, backup) }
        watched.lists.restore(backup.lists)
        metadata?.update { Backups.metadataAfter(it, backup) }
        bookmarks?.let { store -> store.restore(Backups.bookmarksToAdd(store.all(), backup)) }
    }
}

/** The restore prompt's words, shared by every platform so they cannot drift apart. */
object BackupWords {

    /** One line per thing that changes, then where the file came from. */
    fun restoreMessage(plan: RestorePlan): String {
        val lines = buildList {
            if (plan.nothing) add(L.backupRestoreNothing)
            if (plan.settingsChanged > 0) add(L.backupRestoreSettings(plan.settingsChanged))
            if (plan.favouritesAdded > 0) add(L.backupRestoreFavourites(plan.favouritesAdded))
            if (plan.watchedAdded > 0) add(L.backupRestoreWatched(plan.watchedAdded))
            if (plan.watchLaterAdded > 0) add(L.backupRestoreWatchLater(plan.watchLaterAdded))
            if (plan.bookmarksAdded > 0) add(L.backupRestoreBookmarks(plan.bookmarksAdded))
            if (plan.listsAdded > 0) add(L.backupRestoreLists(plan.listsAdded))
            if (plan.resumeAdded > 0) add(L.backupRestoreResume(plan.resumeAdded))
            if (plan.memoryAdded > 0) add(L.backupRestoreMemory(plan.memoryAdded))
            if (plan.searchesAdded > 0) add(L.backupRestoreSearches(plan.searchesAdded))
            val backup = plan.backup
            if (backup.createdAt > 0) {
                add(L.backupRestoreFrom(L.messages.formatter.date(backup.createdAt), backup.appVersion.ifBlank { "?" }))
            }
        }
        return lines.joinToString("\n")
    }

    /** Why a file was not restored, or null when it can be. */
    fun refusal(read: BackupRead): String? = when (read) {
        is BackupRead.Ok -> null
        is BackupRead.TooNew -> L.backupTooNew
        BackupRead.NotABackup -> L.backupNotBackup
    }
}

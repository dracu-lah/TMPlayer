// What the web player keeps on this device: settings, where each video was left, what was watched
// and the starred chats, in one IndexedDB store (idb-keyval); thumbnails in the Cache API. When the
// browser refuses storage (private mode, quota full) everything still works for the session from
// memory. Sign out clears all of it.
//
// The code is in store/, one module per concern; this file is the one place the page imports from.
// store/kv.ts holds the database and the session's copy and every other module builds on it; load.ts
// (start) and clear.ts (sign out) are the only ones that reach across all of them. What a module
// exports for the others (a signal's setter, a storage key) is left out here on purpose.
export { storageWorks, onListsChanged, clearProfileData, flushWrites, read as kvRead, write as kvWrite, remove as kvRemove } from './store/kv.ts'
export { SIZE_DEFAULTS, settings, setSetting, type Settings, type Theme } from './store/settings.ts'
export { openingSpeed, rememberSpeed, readBoost, writeBoost, readNight, writeNight, readEven, writeEven } from './store/playerPrefs.ts'
export {
    watchedTick,
    getResume,
    saveResume,
    dropResume,
    resumes,
    watchedIds,
    progress,
    isWatched,
    watchedList,
    touches,
    setWatched,
    setWatchedMany,
    clearHistory,
    restoreHistory,
    removeFromHistory,
    type HistorySnapshot,
    type Resume,
} from './store/history.ts'
export { laterIds, showLaterId, setShowLater, setLater, dropLater, laterList, laterKey, removeLater, restoreLater, type Later } from './store/later.ts'
export {
    starredIds,
    setStarred,
    starred,
    homeChatRows,
    rememberHomeChats,
    chatPref,
    setChatPref,
    addedIds,
    addedMode,
    chatsGateWaived,
    setChatsGateWaived,
    chatsGated,
    addedGate,
    addedShows,
    addedView,
    setAddedChats,
    addedChats,
    type AddedChat,
    type AddedMode,
    type HomeChatRow,
} from './store/chats.ts'
export { picks, pickOf, setPick } from './store/picks.ts'
export { titles, TITLES_MAX, titleOf, setTitle } from './store/titles.ts'
export { apartIds, chosenCopies, copyHabit, CHOSEN_MAX, APART_MAX, setApart, chooseCopy, setCopyHabit } from './store/copies.ts'
export { POSTERS, cachedThumb } from './store/thumbs.ts'
export {
    namedLists,
    loadLists,
    createList,
    renameList,
    setListHome,
    deleteList,
    addToList,
    removeFromList,
    forgetListVideos,
    namedListsDoc,
    LISTS_BUDGET,
    syncMergeLists,
} from './store/lists.ts'
export { recentSearches, noteSearch, forgetSearch, clearSearches, restoreSearches } from './store/recents.ts'
export {
    syncTombs,
    syncPicks,
    syncTitles,
    syncTitleTombs,
    syncPutTitle,
    syncStars,
    syncWrote,
    syncPutResume,
    syncDropResume,
    syncPutWatched,
    syncDropWatched,
    syncPutLater,
    syncDropLater,
    syncPutStar,
    syncDropStar,
    syncPutPick,
} from './store/sync.ts'
export { loadSettings } from './store/load.ts'
export { clearAll } from './store/clear.ts'

// Reads everything the page keeps into its signals at start (settings, stars, Watch later, the
// added chats, lists, recent searches, picks, renames, copies) and puts the theme and look on the page.
import { cleanHomeChats, cleanSettings, readAddedMode } from '../ui/stored'
import { read } from './kv'
import { applyLook, applyTheme, DEFAULTS, OLD_DEFAULT_MIN, setSettings, settings } from './settings'
import { laterId, laterList, setLaterIds } from './later'
import { ADDED_MODE, addedChats, HOME_CHATS, setAddedIdsSignal, setAddedModeSignal, setHomeChatRows, setStarredIds, starred } from './chats'
import { loadLists } from './lists'
import { loadRecents } from './recents'
import { loadPicks } from './picks'
import { loadTitles } from './titles'
import { loadCopies } from './copies'

export async function loadSettings() {
    const [saved, stars, later, added, mode, chatRows] = await Promise.all([
        read<unknown>('settings'),
        starred(),
        laterList(),
        addedChats(),
        read<unknown>(ADDED_MODE),
        read<unknown>(HOME_CHATS),
    ])
    setHomeChatRows(cleanHomeChats(chatRows))
    // Keys of settings since removed (`rows`, the As rows layout) and values that do not hold up are dropped (ui/stored.ts): the defaults stand, and the next write leaves them behind.
    const known = cleanSettings(saved)
    if (known.sizeMin === OLD_DEFAULT_MIN) delete known.sizeMin
    setSettings({ ...DEFAULTS, ...known })
    setAddedIdsSignal(new Set(added.map((a) => a.chatId)))
    setAddedModeSignal(readAddedMode(mode))
    setStarredIds(new Set(stars))
    setLaterIds(new Set(later.map(laterId)))
    await loadLists()
    await loadRecents()
    await loadPicks()
    await loadTitles()
    await loadCopies()
    applyTheme(settings().theme)
    applyLook(settings())
}

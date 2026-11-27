// The chats: the starred ones, Home's chat rows, each chat's filters, and the chats the viewer
// added (the apps' AddedChats).
import { createSignal } from 'solid-js'
import { cleanHomeChats, type HomeChatRow } from '../ui/stored'
import { cleanPref, isDefault, type ChatPref } from '../ui/filterPref'
import { all, read, remove, write } from './kv'

// Starred chats

/** The starred chats, live, for every star drawn. */
const [starredIds, setStarredIds] = createSignal<ReadonlySet<number>>(new Set())
export { starredIds, setStarredIds }

export function setStarred(chatId: number, on: boolean) {
    const next = new Set(starredIds())
    if (on) {
        next.add(chatId)
        write(`star:${chatId}`, { chatId, time: Date.now() })
    } else {
        next.delete(chatId)
        remove(`star:${chatId}`)
    }
    setStarredIds(next)
}

export type { HomeChatRow }

export const HOME_CHATS = 'home-chats'
const [homeChatRows, setHomeChatRows] = createSignal<readonly HomeChatRow[]>([])
export { homeChatRows, setHomeChatRows }

/** Home's chat rows as just drawn, kept for Settings > Home's order list. */
export function rememberHomeChats(rows: readonly HomeChatRow[]) {
    const next = cleanHomeChats(rows)
    if (JSON.stringify(next) === JSON.stringify(homeChatRows())) return
    setHomeChatRows(next)
    write(HOME_CHATS, next)
}

export async function starred(): Promise<number[]> {
    return (await all<{ chatId: number; time: number }>('star:')).sort((a, b) => a.time - b.time).map((s) => s.chatId)
}

// A chat's filters (U6): the Show filter and the line of the others, kept for each chat on this device.

/** One chat's filters; nothing stored is the default. A stored value that does not hold up is the default (ui/videoFilter.ts). */
export async function chatPref(chatId: number): Promise<ChatPref> {
    return cleanPref(await read<unknown>(`view:${chatId}`))
}

/** Remembers a chat's filters; the default is no entry at all, so the store holds only chats that are filtered. */
export function setChatPref(chatId: number, pref: ChatPref) {
    const next = cleanPref(pref)
    if (isDefault(next)) remove(`view:${chatId}`, true)
    else write(`view:${chatId}`, next)
}

// The chats the viewer added (HX27, the apps' AddedChats)

/**
 * How much of the account this device draws from. 'unanswered' is every visitor from before the
 * picker existed, who keeps seeing everything; 'all' is the same view chosen on purpose, which is
 * what stops the one time prompt asking again; 'picked' honours the stored chats, and picking none
 * is a real answer rather than a reason to show them all.
 */
export type AddedMode = 'unanswered' | 'all' | 'picked'

export interface AddedChat {
    chatId: number
    /** The name it had when it was added, so a chat since left still has a row to remove. */
    title: string
    time: number
}

export const ADDED_MODE = 'addedMode'

const [addedMode, setAddedModeSignal] = createSignal<AddedMode>('unanswered')
/** Live, for every list that browses and for the picker's own ticks. */
const [addedIds, setAddedIdsSignal] = createSignal<ReadonlySet<number> | null>(null)
export { addedIds, addedMode, setAddedIdsSignal, setAddedModeSignal }

/**
 * Whether the chats gate (App.tsx) stands aside for this visit, with nothing written: the account
 * has too few chats to be worth choosing from, or its list would not load (offline). Kept in
 * memory only, so the gate asks again on the next visit, or once the connection comes back.
 */
const [chatsGateWaived, setChatsGateWaived] = createSignal(false)
export { chatsGateWaived, setChatsGateWaived }
/** A signed in visitor who has never chosen which chats to watch meets the chooser first (the gate). */
export const chatsGated = () => addedMode() === 'unanswered' && !chatsGateWaived()

/**
 * The chats a list may draw from, or null for every chat: the same shape the apps' gate has, so
 * one question is asked wherever videos are listed. Null while nobody has answered and under
 * 'all'; a set, possibly empty, once chats have been picked.
 */
export function addedGate(): ReadonlySet<number> | null {
    return addedMode() === 'picked' ? (addedIds() ?? new Set()) : null
}

/** Whether a chat's videos may be listed. */
export function addedShows(chatId: number): boolean {
    const gate = addedGate()
    return gate === null || gate.has(chatId)
}

/** `addedGate()`, as a plain array for a `ListView`/`HomeView` sent to the worker (it cannot read this store itself). */
export function addedView(): number[] | null {
    const gate = addedGate()
    return gate ? [...gate] : null
}

/** The whole choice, as the picker's Done writes it: the ids with the names they had. */
export function setAddedChats(chats: readonly { chatId: number; title: string }[], all = false) {
    const now = Date.now()
    const next = new Set<number>()
    for (const c of chats) {
        next.add(c.chatId)
        write(`added:${c.chatId}`, { chatId: c.chatId, title: c.title, time: now } satisfies AddedChat)
    }
    for (const id of addedIds() ?? []) if (!next.has(id)) remove(`added:${id}`)
    setAddedIdsSignal(next)
    // "All chats" (HX86) is the mode 'all' with the ticks kept beside it, so switching it off brings them back.
    setAddedMode(all ? 'all' : 'picked')
}

function setAddedMode(mode: AddedMode) {
    setAddedModeSignal(mode)
    if (mode === 'unanswered') remove(ADDED_MODE)
    else write(ADDED_MODE, mode)
}

/** The added chats with their names, newest first, for the picker and the list of gone chats. */
export async function addedChats(): Promise<AddedChat[]> {
    return (await all<AddedChat>('added:')).sort((a, b) => a.time - b.time)
}

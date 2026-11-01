// The Add chats picker's rules (HX27), ported from core's ChatPicking: which rows it lists, in
// which bands, and what Select all and Clear mean while a search or a filter narrows the list.
// Pure, so it runs under `node --test`. The Recommended band waits for the counts it needs; which
// chats it holds is core's ChatSuggestions, ported below beside the head start that feeds it.

/** Which chats the list is narrowed to. Saved Messages counts as a person, as it does elsewhere. */
export type Scope = 'all' | 'archived' | 'channels' | 'groups' | 'people'
export const SCOPES: Scope[] = ['all', 'archived', 'channels', 'groups', 'people']

/**
 * A band of the list, in the order they are drawn. Saved Messages has a row of its own, without a
 * heading. Recommended and starred only stand while the whole list is shown.
 */
export type Band = 'saved' | 'recommended' | 'starred' | 'chats' | 'archived' | 'gone'

export interface PickChat {
    id: number
    kind: 'user' | 'bot' | 'group' | 'channel'
    self: boolean
    archived?: boolean
}

export interface PickRow<C extends PickChat = PickChat> {
    id: number
    band: Band
    /** Null for a gone row, drawn from the name the chat had when it was added. */
    chat: C | null
    /** A gone row's stored name. */
    title: string
}

/** Above this many chats ticked in one go, the viewer is asked first: every added chat is read. */
export const CONFIRM_OVER = 50

export function inScope(chat: PickChat, scope: Scope): boolean {
    switch (scope) {
        case 'all':
            return true
        case 'archived':
            return !!chat.archived
        case 'channels':
            return chat.kind === 'channel'
        case 'groups':
            return chat.kind === 'group'
        case 'people':
            return chat.self || chat.kind === 'user' || chat.kind === 'bot'
    }
}

/**
 * The rows the picker lists.
 *
 * `chats` is every chat of the account, archive included. `name` gives the name a search reads.
 * `listComplete` says the chat list is whole: a chat still on its way must not be called gone,
 * or a list that failed part way would mark half an account as left. `recommended` is
 * `recommend`'s answer, in its order (fullest first).
 */
export function pickRows<C extends PickChat>(p: {
    chats: readonly C[]
    name: (chat: C) => string
    added: ReadonlySet<number>
    addedNames?: ReadonlyMap<number, string>
    starred?: ReadonlySet<number>
    recommended?: readonly number[]
    query?: string
    scope?: Scope
    listComplete?: boolean
}): PickRow<C>[] {
    const query = (p.query ?? '').trim().toLowerCase()
    const scope = p.scope ?? 'all'
    const whole = !query && scope === 'all'
    const matching = p.chats.filter((c) => inScope(c, scope) && (!query || p.name(c).toLowerCase().includes(query)))
    const taken = new Set<number>()
    const out: PickRow<C>[] = []
    const add = (chat: C, band: Band) => {
        if (taken.has(chat.id)) return
        taken.add(chat.id)
        out.push({ id: chat.id, band, chat, title: '' })
    }
    for (const c of matching) if (c.self) add(c, 'saved')
    if (whole && p.recommended?.length) {
        const byId = new Map(matching.map((c) => [c.id, c]))
        for (const id of p.recommended) {
            const c = byId.get(id)
            if (c && !c.self) add(c, 'recommended')
        }
    }
    // Starred next while the whole list is shown: the viewer said these matter.
    if (whole) for (const c of matching) if (p.starred?.has(c.id) && !c.archived) add(c, 'starred')
    for (const c of matching) if (!c.archived) add(c, 'chats')
    // The archive has a band of its own under the rest; under the Archived filter it is the list.
    for (const c of matching) if (c.archived) add(c, scope === 'archived' ? 'chats' : 'archived')
    if (whole && p.listComplete) {
        for (const id of p.added) {
            if (taken.has(id)) continue
            taken.add(id)
            out.push({ id, band: 'gone', chat: null, title: p.addedNames?.get(id) ?? '' })
        }
    }
    return out
}

/** What Select all and Clear act on: the rows on the screen now, gone ones aside. */
export const visibleIds = (rows: readonly PickRow[]): number[] => rows.filter((r) => r.band !== 'gone').map((r) => r.id)

/** Select all over what is shown: ticks made under another filter stay. */
export function selectAll(ticked: ReadonlySet<number>, visible: readonly number[]): Set<number> {
    const next = new Set(ticked)
    for (const id of visible) next.add(id)
    return next
}

/** Clear over what is shown, and only that. */
export function clearVisible(ticked: ReadonlySet<number>, visible: readonly number[]): Set<number> {
    const shown = new Set(visible)
    return new Set([...ticked].filter((id) => !shown.has(id)))
}

/** Whether Select all would tick nothing new, which is when the button reads Clear instead. */
export const allTicked = (ticked: ReadonlySet<number>, visible: readonly number[]) => visible.length > 0 && visible.every((id) => ticked.has(id))

/** How many chats Select all would add. */
export const adding = (ticked: ReadonlySet<number>, visible: readonly number[]) => visible.filter((id) => !ticked.has(id)).length

/** Whether ticking that many at once is worth a word first. */
export const needsConfirm = (count: number) => count > CONFIRM_OVER

/** Whether the ticks differ from the choice the picker opened with. */
export function changed(start: ReadonlySet<number>, now: ReadonlySet<number>): boolean {
    if (start.size !== now.size) return true
    for (const id of now) if (!start.has(id)) return true
    return false
}

// ---- The Recommended band (core's ChatSuggestions and ChatPicking.headStart) -------------------

/** Videos a chat needs before it is recommended. */
export const MIN_VIDEOS = 20
/** At most this many are ticked for the viewer, fullest first. */
export const MAX_PRETICKED = 30
/** Offered when nothing reaches MIN_VIDEOS, and never ticked. */
export const FALLBACK = 5
/** How far down the list the counts run before the viewer scrolls; the recommendation is made from these. */
export const COUNT_AHEAD = 60
/** The band is made from whatever has been counted by then, so it shows within the first seconds. */
export const RECOMMEND_BY_MS = 3000

/** One chat as the recommendation weighs it: its place in the list and its videos, null while not known. */
export interface CountedChat {
    id: number
    order: number
    videos: number | null
    savedMessages?: boolean
}

export interface Suggestion {
    /** The recommended chats, in the order the band lists them. */
    ids: number[]
    /** The ones ticked for the viewer, always a subset of `ids`. */
    ticked: Set<number>
    /** True when nothing reached MIN_VIDEOS and these are the fullest chats instead. */
    belowBar: boolean
}

/**
 * The recommendation: fullest first, and where two hold as many the chat listed first wins, so two
 * runs over the same account agree. A chat not counted is left out rather than called empty, and
 * Saved Messages is never here (it has a row of its own at the top).
 */
export function recommend(chats: readonly CountedChat[]): Suggestion {
    const counted = chats
        .filter((c) => !c.savedMessages && c.videos != null && c.videos > 0)
        .sort((a, b) => (b.videos ?? 0) - (a.videos ?? 0) || a.order - b.order)
    if (!counted.length) return { ids: [], ticked: new Set(), belowBar: false }
    const over = counted.filter((c) => (c.videos ?? 0) >= MIN_VIDEOS)
    // Shown so the band is never empty, unticked because none of them clears the bar.
    if (!over.length) return { ids: counted.slice(0, FALLBACK).map((c) => c.id), ticked: new Set(), belowBar: true }
    const ids = over.map((c) => c.id)
    return { ids, ticked: new Set(ids.slice(0, MAX_PRETICKED)), belowBar: false }
}

/** The chats counted before any row is drawn: Saved Messages, then the head of the list in Telegram's order. */
export function headStart(chats: readonly PickChat[], ahead = COUNT_AHEAD): number[] {
    const out = new Set<number>()
    for (const c of chats) if (c.self) out.add(c.id)
    for (const c of chats) {
        if (out.size >= ahead) break
        out.add(c.id)
    }
    return [...out]
}

/** What `recommend` weighs, from the list and the counts in hand (a negative count could not be read). */
export function counted(chats: readonly PickChat[], counts: ReadonlyMap<number, number>): CountedChat[] {
    return chats.map((c, order) => {
        const n = counts.get(c.id)
        return { id: c.id, order, videos: n != null && n >= 0 ? n : null, savedMessages: c.self }
    })
}

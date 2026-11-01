// The Add chats picker's rules (HX27), ported from core's ChatPicking: which rows it lists, in
// which bands, and what Select all and Clear mean while a search or a filter narrows the list.
// Pure, so it runs under `node --test`. The Recommended band waits for the counts it needs.

/** Which chats the list is narrowed to. Saved Messages counts as a person, as it does elsewhere. */
export type Scope = 'all' | 'archived' | 'channels' | 'groups' | 'people'
export const SCOPES: Scope[] = ['all', 'archived', 'channels', 'groups', 'people']

/** A band of the list, in the order they are drawn. Saved Messages has a row of its own, without a heading. */
export type Band = 'saved' | 'starred' | 'chats' | 'archived' | 'gone'

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
 * or a list that failed part way would mark half an account as left.
 */
export function pickRows<C extends PickChat>(p: {
    chats: readonly C[]
    name: (chat: C) => string
    added: ReadonlySet<number>
    addedNames?: ReadonlyMap<number, string>
    starred?: ReadonlySet<number>
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

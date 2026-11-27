// The copies of a video (HX38): the files kept apart from their copies, the copy the viewer chose,
// and the quality they usually play.
import { createSignal } from 'solid-js'
import { allEntries, id, read, remove, VIDEO_KEY, write } from './kv'

// The copies of a video (HX38), on this device only: the files kept apart from the copies they were
// grouped with ("Not the same video", `apart:<chat>:<message>`), the copy the viewer chose for a
// video in its list of copies (`chose:<chat>:<message>`, the time), and the quality they usually
// play (`copyhabit`, QualityHabit's line). The worker groups and picks by them (copies.ts).

const [apartIds, setApartIds] = createSignal<ReadonlySet<string>>(new Set())
const [chosenCopies, setChosenCopies] = createSignal<readonly string[]>([])
const [copyHabit, setCopyHabitSignal] = createSignal<string | null>(null)
export { apartIds, chosenCopies, copyHabit, setApartIds, setChosenCopies, setCopyHabitSignal }

/** The most choices kept; the oldest goes first. */
export const CHOSEN_MAX = 2_000
/** The most files kept apart (a viewer splits a handful; anything past this is not theirs). */
export const APART_MAX = 5_000
const HABIT_LINE = /^[\d:,]{1,400}$/

/** "Not the same video" (on) for one file, or "Group with its copies again" (off). */
export function setApart(chatId: number, messageId: number, on: boolean) {
    const key = id(chatId, messageId)
    const next = new Set(apartIds())
    if (on) {
        if (next.size >= APART_MAX && !next.has(key)) return
        next.add(key)
        write(`apart:${key}`, Date.now())
    } else {
        if (!next.delete(key)) return
        remove(`apart:${key}`)
    }
    setApartIds(next)
}

/**
 * The viewer chose `chosen` in a list of `among` (its copies): it is the one their cards of the
 * video play from now on, whatever is recommended. A choice made before among them is forgotten.
 */
export function chooseCopy(chosen: { chatId: number; messageId: number }, among: readonly { chatId: number; messageId: number }[]) {
    const key = id(chosen.chatId, chosen.messageId)
    const others = new Set(among.map((c) => id(c.chatId, c.messageId)))
    others.add(key)
    const kept = chosenCopies().filter((k) => !others.has(k))
    for (const k of chosenCopies()) if (others.has(k)) remove(`chose:${k}`)
    const next = [key, ...kept]
    for (const k of next.splice(CHOSEN_MAX)) remove(`chose:${k}`)
    write(`chose:${key}`, Date.now())
    setChosenCopies(next)
}

/** The quality line (QualityHabit) once a play is noted in it by the worker. */
export function setCopyHabit(line: string) {
    if (!HABIT_LINE.test(line)) return
    setCopyHabitSignal(line)
    write('copyhabit', line)
}

export async function loadCopies() {
    const [apart, chose, habit] = await Promise.all([allEntries<unknown>('apart:'), allEntries<unknown>('chose:'), read<unknown>('copyhabit')])
    const keyOf = (k: string, prefix: string) => {
        const key = k.slice(prefix.length)
        return VIDEO_KEY.test(key) ? key : null
    }
    const ids = new Set<string>()
    for (const [k, at] of apart) {
        const key = keyOf(k, 'apart:')
        if (key && typeof at === 'number' && ids.size < APART_MAX) ids.add(key)
    }
    const chosen: [string, number][] = []
    for (const [k, at] of chose) {
        const key = keyOf(k, 'chose:')
        if (key && typeof at === 'number' && Number.isFinite(at)) chosen.push([key, at])
    }
    chosen.sort((a, b) => b[1] - a[1])
    setApartIds(ids)
    setChosenCopies(chosen.slice(0, CHOSEN_MAX).map(([k]) => k))
    setCopyHabitSignal(typeof habit === 'string' && HABIT_LINE.test(habit) ? habit : null)
}

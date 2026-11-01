// Settings > Home's stored values (the apps' HomeLayout.of), checked: storage is outside data (a
// file cut short, an older build's, a hand edit), so a switch that is not a boolean is on, a
// source that is not known is Automatic, and only whole, non-zero chat ids are kept, at most
// RECENT_CHATS_MAX. Pure, so it runs under `node --test`.

export type RecentSource = 'auto' | 'chosen'

/** Chosen chats kept in the setting (HomeLayout.STORED_MAX): more than any account has, so a bad file stays small. */
export const RECENT_CHATS_MAX = 10_000

const SWITCHES = ['carousel', 'homeContinue', 'homeRecent', 'homeStarred'] as const

interface HomeKeys {
    carousel: boolean
    homeContinue: boolean
    homeRecent: boolean
    homeStarred: boolean
    recentSource: RecentSource
    recentChats: number[]
}

/** `saved` with Settings > Home's keys that do not hold up taken out (so the defaults stand) or cut down. */
export function cleanHome<T extends Partial<HomeKeys>>(saved: T): T {
    const out: Record<string, unknown> = { ...saved }
    for (const k of SWITCHES) if (k in out && typeof out[k] !== 'boolean') delete out[k]
    if ('recentSource' in out && out.recentSource !== 'auto' && out.recentSource !== 'chosen') delete out.recentSource
    if ('recentChats' in out) {
        const raw = out.recentChats
        out.recentChats = Array.isArray(raw) ? [...new Set(raw.filter((id): id is number => Number.isSafeInteger(id) && id !== 0))].slice(0, RECENT_CHATS_MAX) : []
    }
    return out as T
}

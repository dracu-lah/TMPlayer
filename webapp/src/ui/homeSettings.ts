// Settings > Home's stored values (the apps' HomeLayout.of), checked: storage is outside data (a
// file cut short, an older build's, a hand edit), so a switch that is not a boolean is on. Pure,
// so it runs under `node --test`.
//
// Recently added once had its own "Chosen chats" (recentSource, recentChats). It follows the chats
// added in Add chats now (2026-11-01), so an older build's values are dropped on the way in.

const SWITCHES = ['carousel', 'homeContinue', 'homeRecent', 'homeStarred'] as const

/** Keys an older build kept for Recently added's own chats, no longer read. */
const RETIRED = ['recentSource', 'recentChats'] as const

interface HomeKeys {
    carousel: boolean
    homeContinue: boolean
    homeRecent: boolean
    homeStarred: boolean
}

/** `saved` with Settings > Home's keys that do not hold up taken out (so the defaults stand), and the retired ones dropped. */
export function cleanHome<T extends Partial<HomeKeys>>(saved: T): T {
    const out: Record<string, unknown> = { ...saved }
    for (const k of SWITCHES) if (k in out && typeof out[k] !== 'boolean') delete out[k]
    for (const k of RETIRED) delete out[k]
    return out as T
}

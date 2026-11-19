// Where the remembered online subtitles live (subpicks.ts), and the one call the sign out needs without
// loading the rest of the player: empty that database. Plain, so the main chunk takes it for a few bytes.
import { clear, createStore } from 'idb-keyval'

export const PICKS_DB = 'tmplayer-subpicks'

/** Every pick and every copy kept with them (sign out); never throws, a browser that refuses storage has none to wipe. */
export async function wipeAllPicks(): Promise<void> {
    try {
        await clear(createStore(PICKS_DB, 'kv'))
    } catch {}
}

// The count line over a search's cards (EG-F8), the web twin of core's GlobalSearch.tally. Pure, so it
// runs under `node --test src/ui/searchTally.test.ts`.

/** What a card needs to be counted: a show stands for its episodes, a video for one, and each is in a chat. */
export interface Countable {
    kind: 'show' | 'file'
    episodes: number
    item: { chatId: number }
}

/** The videos the cards stand for, a show by its episodes, and the chats they are in, each once. */
export function tally(tiles: readonly Countable[]): { videos: number; chats: number } {
    const chats = new Set<number>()
    let videos = 0
    for (const tile of tiles) {
        videos += tile.kind === 'show' ? Math.max(1, Math.trunc(tile.episodes) || 1) : 1
        chats.add(tile.item.chatId)
    }
    return { videos, chats: chats.size }
}

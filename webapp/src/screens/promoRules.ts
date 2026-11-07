// The web twin of core's PromoCovers: which cover pictures are an uploader's advert. A cover
// (the key of Telegram's inline preview, see tg/media.ts coverKey) on 3 or more videos of one chat
// that are not all one title is an advert, and so is one on 3 or more chats with more than one
// title among them. The episodes of one show with one cover are one title, so never flagged.

export const MIN_FILES = 3
export const MIN_CHATS = 3

/** What the rules read of a video. */
export interface CoverItem {
    chatId: number
    cover?: string | null
    /** What the poster lookup asks about; its title is the show's or film's, never an episode's. */
    poster: { title: string } | null
}

/** A title as a key: letters and digits of any script, lower case, single spaces. */
export function titleKey(title: string): string {
    return title
        .toLowerCase()
        .replace(/[^\p{L}\p{N}]+/gu, ' ')
        .trim()
}

interface Tally {
    files: number
    first: string
    varied: boolean
    chats: Set<number>
}

function add(t: Tally | undefined, title: string, chat: number): Tally {
    const tally = t ?? { files: 0, first: title, varied: false, chats: new Set<number>() }
    tally.files++
    if (tally.first !== title) tally.varied = true
    if (tally.chats.size < MIN_CHATS) tally.chats.add(chat)
    return tally
}

/** The flagged covers among `items`, which may come from one chat or several. One pass. */
export function promoCovers(items: readonly CoverItem[]): Set<string> {
    const flagged = new Set<string>()
    if (items.length < MIN_FILES) return flagged
    const perChat = new Map<string, Tally>()
    const perCover = new Map<string, Tally>()
    for (const item of items) {
        const cover = item.cover
        if (!cover || flagged.has(cover)) continue
        // Without a title there is no telling two files apart from one: counts for nothing.
        const title = item.poster ? titleKey(item.poster.title) : ''
        if (!title) continue
        const chatKey = `${item.chatId}|${cover}`
        const here = add(perChat.get(chatKey), title, item.chatId)
        perChat.set(chatKey, here)
        if (here.files >= MIN_FILES && here.varied) {
            flagged.add(cover)
            continue
        }
        const all = add(perCover.get(cover), title, item.chatId)
        perCover.set(cover, all)
        if (all.chats.size >= MIN_CHATS && all.varied) flagged.add(cover)
    }
    return flagged
}

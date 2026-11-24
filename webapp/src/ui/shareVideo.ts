// Share a video's Telegram link with the device's own share sheet (WP-59): `navigator.share` where the
// browser has one (every phone and Safari on a Mac), and nothing where it does not, since Copy link
// is already there. Never the file: the link is the same one Copy link gives, and a chat that
// restricts saving gives none (ui/tgLinks.ts linkOffered), so there is nothing to share from it.

export interface Sharer {
    share?: (data: { url: string; title?: string }) => Promise<void>
}

/** Whether this browser can open a share sheet at all. */
export const canShare = (nav: Sharer | undefined = typeof navigator === 'undefined' ? undefined : navigator): boolean => typeof nav?.share === 'function'

export type Shared = 'shared' | 'cancelled' | 'failed' | 'unsupported'

/** Opens the share sheet for `link`. A sheet the person closed is `cancelled`, not a failure. */
export async function shareVideo(link: string, title: string, nav: Sharer | undefined = typeof navigator === 'undefined' ? undefined : navigator): Promise<Shared> {
    if (!nav || typeof nav.share !== 'function') return 'unsupported'
    try {
        await nav.share({ url: link, title: title.slice(0, 200) })
        return 'shared'
    } catch (e) {
        return (e as { name?: string } | null)?.name === 'AbortError' ? 'cancelled' : 'failed'
    }
}

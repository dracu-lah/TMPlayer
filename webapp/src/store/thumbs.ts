// Thumbnails and posters in the Cache API.
import { POSTERS as POSTER_CACHE, THUMBS, touch } from '../cacheBounds'
import { PREFIX } from './kv'

// Thumbnails

/** The Cache API's store for poster answers and pictures (screens/posters.ts). */
export const POSTERS = POSTER_CACHE

/** A thumbnail's JPEG from the Cache API, or from `fetch` (and then kept there). */
export async function cachedThumb(key: string, fetch: () => Promise<ArrayBuffer>): Promise<Blob> {
    // Another profile's thumbnails are its own (a private chat's message ids are per account), in the same capped cache.
    const url = `/thumb/${encodeURIComponent(PREFIX + key)}`
    const cache = await globalThis.caches?.open(THUMBS).catch(() => undefined)
    const hit = await cache?.match(url).catch(() => undefined)
    if (hit) {
        touch(THUMBS, url)
        return hit.blob()
    }
    const blob = new Blob([await fetch()], { type: 'image/jpeg' })
    cache?.put(url, new Response(blob)).then(() => touch(THUMBS, url, blob.size), () => {})
    return blob
}

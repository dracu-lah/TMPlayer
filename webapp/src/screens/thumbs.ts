import { call } from '../tg/rpc'
import { cachedThumb } from '../store'
import { createSlots } from '../ui/fetchSlots'
import { createThumbLoader } from '../ui/thumbJobs'

// Thumbnails: a few at a time, only for cards on screen, kept in the Cache API and for the session.
// A card that leaves the screen takes its ask back (HX54): one still waiting for a slot never runs,
// one already with Telegram finishes into the cache and stays joinable, and a hard scroll over
// hundreds of cards asks for the few the viewer stops on. Many cards may want one picture (a title
// in two rows): the fetch is shared (ui/thumbJobs.ts).

/** Fetches with Telegram at once. */
const THUMB_SLOTS = 4
const slots = createSlots(THUMB_SLOTS)

const loader = createThumbLoader({
    slots,
    // The slot guards the ask to Telegram only: a picture already on this device needs none.
    run: (key, slot) =>
        cachedThumb(key, async () => {
            const release = await slot()
            try {
                return await call('thumb', [key])
            } finally {
                release()
            }
        }).then((blob) => URL.createObjectURL(blob)),
})

/** The thumbnail's object URL. With `signal`, aborting it takes this ask back (rejects with an AbortError). */
export const loadThumb = loader.load

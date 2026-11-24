// A visitor on Save-Data, or on a connection that says 2g, is not sent code they have not asked for (WP-62):
// the idle prefetch of the other pages waits until each is opened, and the service worker keeps only the
// first page ahead (public/sw.js says the same). `navigator.connection` is Chromium's; Safari has none, and
// then nothing is held back.

export interface Connection {
    saveData?: boolean
    effectiveType?: string
}

/** True when the visitor asked the browser to save data, or the line is 2g or slower. */
export function saveData(c: Connection | undefined = typeof navigator === 'undefined' ? undefined : (navigator as Navigator & { connection?: Connection }).connection): boolean {
    if (!c) return false
    return c.saveData === true || c.effectiveType === 'slow-2g' || c.effectiveType === '2g'
}

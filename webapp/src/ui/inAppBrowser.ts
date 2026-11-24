// Two facts from the browser's user agent (WP-58): is the page inside another app's web view, and
// which iOS wording of "Add to Home Screen" applies. Pure, so `node --test` runs it over real strings.

export interface Agent {
    ua: string
    /** `navigator.maxTouchPoints`: an iPad asking for the desktop site says it is a Mac, and only touch tells. */
    touch: number
    /** Opened as an installed app: its user agent has no `Safari/` either, and it is not an in-app browser. */
    standalone: boolean
    /** The browser has a service worker. */
    worker: boolean
}

const IPHONE = /iPhone|iPad|iPod/
const MAC = /Macintosh/

/** An iPhone or iPad (an iPad on the desktop site says Macintosh, with a touch screen). */
export const isIos = (a: Pick<Agent, 'ua' | 'touch'>) => IPHONE.test(a.ua) || (MAC.test(a.ua) && a.touch > 1)

/**
 * Another app's web view on iOS (a link opened inside Telegram, Instagram, Facebook, Mail): a
 * WKWebView user agent has no `Safari/`. It cannot be added to the Home Screen, has no service worker
 * of its own for our stream path, and keeps its storage apart from Safari's. An installed Home Screen
 * app has no `Safari/` either, so it is told apart by `standalone`.
 */
export function inAppBrowser(a: Agent): boolean {
    if (!isIos(a) || a.standalone) return false
    return !/\bSafari\//.test(a.ua) || !a.worker
}

/** Which app, when its own name is in the string: for the log line, never shown. */
export function inAppName(ua: string): string {
    return /Instagram/.test(ua) ? 'instagram' : /FBAN|FBAV|FB_IAB/.test(ua) ? 'facebook' : /Line\//.test(ua) ? 'line' : /Telegram/i.test(ua) ? 'telegram' : 'webview'
}

/**
 * The iOS version that decides the wording, 0 when it is not iOS. Safari 26 keeps "iPhone OS 18_x"
 * in its string, so the Safari version is read first, the OS version after.
 */
export function iosMajor(a: Pick<Agent, 'ua' | 'touch'>): number {
    if (!isIos(a)) return 0
    const safari = /\bVersion\/(\d+)/.exec(a.ua)
    if (safari) return Number(safari[1])
    const os = /\bOS (\d+)[_.]/.exec(a.ua)
    return os ? Number(os[1]) : 0
}

/** iOS 26 moved Share under the More button of Safari's bar, and Add to Home Screen opens as a web app by default. */
export const newInstallSteps = (major: number) => major >= 26

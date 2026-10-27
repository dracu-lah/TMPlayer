// Installing and updating: the service worker (workbox-window says when a new version waits), the
// browser's install prompt where there is one, the iPhone's Add to Home Screen steps where there is
// not, and asking the browser to keep TMPlayer's data.
import { createSignal } from 'solid-js'
import { Workbox } from 'workbox-window'

const HOUR = 60 * 60 * 1000

/** A new version is downloaded and waits for Reload. */
const [updateReady, setUpdateReady] = createSignal(false)
export { updateReady }

let wb: Workbox | null = null
let waiting = false

/** Registers the service worker. A new version found later waits until the user picks Reload, so nothing changes under a playing video. */
export function registerWorker() {
    if (!('serviceWorker' in navigator)) return
    wb = new Workbox('/sw.js')
    wb.addEventListener('waiting', () => {
        waiting = true
        setUpdateReady(true)
    })
    // Another tab reloaded into the new version: this one still runs the old one.
    wb.addEventListener('controlling', (e) => e.isExternal && setUpdateReady(true))
    wb.register().catch((e) => console.warn('[sw]', e))
    // A Home Screen app is rarely closed: it looks for a new version when it comes back after an hour.
    let checked = Date.now()
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState !== 'visible' || Date.now() - checked < HOUR) return
        checked = Date.now()
        wb?.update().catch(() => {})
    })
    checkKept().then(() => standalone && kept() === false && keepData())
}

/** Reloads into the new version: the waiting worker takes over first. */
export function applyUpdate() {
    if (!wb || !waiting) return location.reload()
    wb.addEventListener('controlling', () => location.reload())
    wb.messageSkipWaiting()
}

interface InstallPrompt extends Event {
    prompt(): Promise<void>
    userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

/** Opened as an installed app (Home Screen, its own window). */
export const standalone = matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true

/** Safari on an iPhone or iPad (an iPad says it is a Mac): no install prompt, Share then Add to Home Screen instead. */
export const iosBrowser = !standalone && (/iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1))

const [installPrompt, setInstallPrompt] = createSignal<InstallPrompt | null>(null)
const [installed, setInstalled] = createSignal(standalone)
export { installPrompt, installed }

// The browser's own install bar still shows; the event is kept for the Install button in Settings.
window.addEventListener('beforeinstallprompt', (e) => setInstallPrompt(e as InstallPrompt))
window.addEventListener('appinstalled', () => {
    setInstallPrompt(null)
    setInstalled(true)
    keepData()
})

export async function install() {
    const p = installPrompt()
    if (!p) return
    await p.prompt()
    // A prompt can be shown once; a dismissed one comes back as a new event later.
    setInstallPrompt(null)
}

/** Whether the browser keeps TMPlayer's data when the device runs low on space; null where it cannot say. */
const [kept, setKept] = createSignal<boolean | null>(null)
/** The browser said no to the last ask. */
const [refused, setRefused] = createSignal(false)
export { kept, refused }

export async function checkKept() {
    setKept((await navigator.storage?.persisted?.().catch(() => null)) ?? null)
}

/** Once the user is signed in, asks the browser to keep the data (one ask per browser; Settings can ask again). */
export async function askKeepOnce() {
    try {
        if (localStorage.getItem('tmplayer-persist-asked')) return
        localStorage.setItem('tmplayer-persist-asked', '1')
    } catch {}
    await checkKept()
    if (kept() === false) await keepData()
}

/** Asks the browser to keep the data. Installed apps get it without a question; a tab may be refused. */
export async function keepData(): Promise<boolean> {
    const ok = (await navigator.storage?.persist?.().catch(() => false)) ?? false
    setKept(ok)
    setRefused(!ok)
    return ok
}

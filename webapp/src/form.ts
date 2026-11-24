// Which of the apps' three layouts (DeviceForm) the page shows: phone, desktop or TV, set on
// <html data-form> before the first paint by index.html's inline script, which holds the same rules
// as detect() below (keep the two in step). A phone always gets the phone layout; anything else
// gets the desktop or TV layout from detection, or from Settings > Layout (Desktop or TV, as in the
// desktop app) once one is picked. ?tv=1 or ?tv=0 in the address stores TV or Desktop as that choice,
// in the inline script, before this module runs, and fixes it for the tab (layoutFixed). A resize or a rotation changes the form live, except while a
// video plays, when the change waits until the player closes.
import { createSignal } from 'solid-js'
import { isTablet } from './ui/tablet'

export type Form = 'phone' | 'desktop' | 'tv'
/** The choice Settings offers off a phone. */
export type Layout = 'desktop' | 'tv'

export const LAYOUT_KEY = 'tmplayer-layout'

/** Models that call themselves a TV; Android with "TV" in it covers Android TV and Google TV. */
const TV_AGENT = /Web0S|webOS|Tizen|SMART-TV|SmartTV|\bAFT|CrKey|HbbTV|Android.*\bTV\b/i

export function detect(
    ua = navigator.userAgent,
    width = innerWidth,
    height = innerHeight,
    media: (q: string) => boolean = (q) => matchMedia(q).matches,
    touchPoints: number | undefined = navigator.maxTouchPoints,
): Form {
    if (TV_AGENT.test(ua)) return 'tv'
    // Android without a touch screen is a TV or a stick whose agent does not say so (a Mi TV's WebView
    // sends "Android 9; MiTV-AESP0 ... Mobile Safari", with a fine pointer); phones and tablets have touch.
    if (/Android/i.test(ua) && touchPoints === 0) return 'tv'
    // A touch tablet (short side 840 px or more) gets the desktop layout, like the app's wide phone/tablet form.
    if (media('(pointer: coarse)') && media('(hover: none)') && Math.min(width, height) < 840) return 'phone'
    return 'desktop'
}

/**
 * The address chose the layout (?tv=1 from a TV wrapper, or ?tv=0) in this tab: Settings leaves its
 * Layout out, since a wrapper opens with ?tv=1 every time and Desktop there would only take the remote away.
 */
export function layoutFixed(): boolean {
    try {
        return sessionStorage.getItem('tmplayer-layout-fixed') === '1'
    } catch {
        return false
    }
}

/**
 * A touch device with a tablet's screen (its short side 600 px or more, whatever the window is now): an
 * iPad in Split View or a foldable half open gets the phone layout while the window is narrow, and
 * Settings then offers it the choice (WP-62), kept over the narrow window.
 */
export const tabletClass = (): boolean =>
    isTablet(typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches, typeof screen === 'undefined' ? 0 : screen.width, typeof screen === 'undefined' ? 0 : screen.height)

/** The stored choice, or null for none (an old 'phone' choice counts as none). */
export function savedLayout(): Layout | null {
    try {
        const v = localStorage.getItem(LAYOUT_KEY)
        if (v === 'desktop' || v === 'tv') return v
    } catch {}
    return null
}

/** A window narrower than this (an iPad's Slide Over is 320) is the phone's whatever the choice: the desktop layout does not fit it. */
const TABLET_WINDOW = 500

const [saved, setSaved] = createSignal<Layout | null>(savedLayout())

function resolve(): Form {
    const d = detect()
    // A phone is always the phone layout; a tablet in a narrow window is one until it picks another (WP-62).
    return d === 'phone' && !(tabletClass() && Math.min(innerWidth, innerHeight) >= TABLET_WINDOW) ? d : saved() ?? d
}

const [form, setForm] = createSignal<Form>((document.documentElement.dataset.form as Form | undefined) ?? resolve())
export { form }

/** What Settings > Layout shows as picked: the form itself off a phone. */
export const layout = (): Layout => (form() === 'tv' ? 'tv' : 'desktop')

let holding = false

function apply() {
    const f = resolve()
    document.documentElement.dataset.form = f
    setForm(f)
}

/** Settings > Layout: stored (so the next load paints in it) and applied at once, even while a video plays. */
export function setLayout(next: Layout) {
    setSaved(next)
    try {
        localStorage.setItem(LAYOUT_KEY, next)
    } catch {}
    apply()
}

/** Settings > Layout, Automatic: the stored choice goes and the window decides again. */
export function clearLayout() {
    setSaved(null)
    try {
        localStorage.removeItem(LAYOUT_KEY)
    } catch {}
    apply()
}

/** The watch page tells whether a video is playing: a resize waits, and is applied when it ends. */
export function holdForm(on: boolean) {
    holding = on
    if (!on && resolve() !== form()) apply()
}

/** Called once at start: makes sure the attribute is there and follows resizes and pointer changes. */
export function watchForm() {
    apply()
    const again = () => {
        if (!holding && resolve() !== form()) apply()
    }
    addEventListener('resize', again)
    addEventListener('orientationchange', again)
    for (const q of ['(pointer: coarse)', '(hover: none)']) matchMedia(q).addEventListener?.('change', again)
}

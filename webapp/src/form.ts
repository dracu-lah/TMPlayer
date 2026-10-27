// Which of the apps' three layouts (DeviceForm) the page shows: phone, desktop or TV, set on
// <html data-form> before the first paint by index.html's inline script, which holds the same rules
// as detect() below (keep the two in step). A phone always gets the phone layout; anything else
// gets the desktop or TV layout from detection, or from Settings > Layout (Desktop or TV, as in the
// desktop app) once one is picked. A resize or a rotation changes the form live, except while a
// video plays, when the change waits until the player closes.
import { createSignal } from 'solid-js'

export type Form = 'phone' | 'desktop' | 'tv'
/** The choice Settings offers off a phone. */
export type Layout = 'desktop' | 'tv'

export const LAYOUT_KEY = 'tmplayer-layout'

/** Models that call themselves a TV; Android with "TV" in it covers Android TV and Google TV. */
const TV_AGENT = /Web0S|webOS|Tizen|SMART-TV|SmartTV|\bAFT|CrKey|HbbTV|Android.*\bTV\b/i

export function detect(ua = navigator.userAgent, width = innerWidth, height = innerHeight, media: (q: string) => boolean = (q) => matchMedia(q).matches): Form {
    if (TV_AGENT.test(ua)) return 'tv'
    // A touch tablet (short side 840 px or more) gets the desktop layout, like the app's wide phone/tablet form.
    if (media('(pointer: coarse)') && media('(hover: none)') && Math.min(width, height) < 840) return 'phone'
    return 'desktop'
}

/** The stored choice, or null for none (an old 'phone' choice counts as none). */
export function savedLayout(): Layout | null {
    try {
        const v = localStorage.getItem(LAYOUT_KEY)
        if (v === 'desktop' || v === 'tv') return v
    } catch {}
    return null
}

const [saved, setSaved] = createSignal<Layout | null>(savedLayout())

function resolve(): Form {
    const d = detect()
    return d === 'phone' ? d : saved() ?? d
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

// Which of the apps' three layouts (DeviceForm) the page shows: phone, desktop or TV, set on
// <html data-form> before the first paint by index.html's inline script, which holds the same rules
// as detect() below (keep the two in step). Settings > Layout (auto, phone, desktop, tv) wins over
// detection; in Auto a resize or a rotation changes the form live, except while a video plays,
// when the change waits until the player closes.
import { createSignal } from 'solid-js'

export type Form = 'phone' | 'desktop' | 'tv'
export type Layout = 'auto' | Form

export const LAYOUT_KEY = 'tmplayer-layout'

/** Models that call themselves a TV; Android with "TV" in it covers Android TV and Google TV. */
const TV_AGENT = /Web0S|webOS|Tizen|SMART-TV|SmartTV|\bAFT|CrKey|HbbTV|Android.*\bTV\b/i

export function detect(ua = navigator.userAgent, width = innerWidth, height = innerHeight, media: (q: string) => boolean = (q) => matchMedia(q).matches): Form {
    if (TV_AGENT.test(ua)) return 'tv'
    // A touch tablet (short side 840 px or more) gets the desktop layout, like the app's wide phone/tablet form.
    if (media('(pointer: coarse)') && media('(hover: none)') && Math.min(width, height) < 840) return 'phone'
    return 'desktop'
}

export function savedLayout(): Layout {
    try {
        const v = localStorage.getItem(LAYOUT_KEY)
        if (v === 'phone' || v === 'desktop' || v === 'tv') return v
    } catch {}
    return 'auto'
}

const [layout, setLayoutSignal] = createSignal<Layout>(savedLayout())
const [form, setForm] = createSignal<Form>(
    (document.documentElement.dataset.form as Form | undefined) ?? (layout() === 'auto' ? detect() : layout() as Form),
)
export { form, layout }

let holding = false

function resolve(): Form {
    const l = layout()
    return l === 'auto' ? detect() : l
}

function apply() {
    const f = resolve()
    document.documentElement.dataset.form = f
    setForm(f)
}

/** Settings > Layout: stored (so the next load paints in it) and applied at once, even while a video plays. */
export function setLayout(next: Layout) {
    setLayoutSignal(next)
    try {
        if (next === 'auto') localStorage.removeItem(LAYOUT_KEY)
        else localStorage.setItem(LAYOUT_KEY, next)
    } catch {}
    apply()
}

/** The watch page tells whether a video is playing: a resize in Auto waits, and is applied when it ends. */
export function holdForm(on: boolean) {
    holding = on
    if (!on && layout() === 'auto' && resolve() !== form()) apply()
}

/** Called once at start: makes sure the attribute is there and follows resizes and pointer changes in Auto. */
export function watchForm() {
    apply()
    const again = () => {
        if (layout() === 'auto' && !holding && resolve() !== form()) apply()
    }
    addEventListener('resize', again)
    addEventListener('orientationchange', again)
    for (const q of ['(pointer: coarse)', '(hover: none)']) matchMedia(q).addEventListener?.('change', again)
}

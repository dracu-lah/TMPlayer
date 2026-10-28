// The seek bar's preview card (StoryboardPreview.kt's SeekPreview): where it sits, which moment a
// frame is grabbed at, how a storyboard's map is read, and the grabber that draws a natively
// playable video's own frames from a hidden second element. Pure parts first, for the node tests.

/** The card's standard size in CSS pixels: 16:9, 240 by 135 on the desktop layout, 160 by 90 on a phone. */
export function cardSize(phone: boolean, barWidth: number): { width: number; height: number } {
    const wanted = phone ? 160 : 240
    const width = Math.max(96, Math.min(wanted, barWidth > 0 ? barWidth : wanted))
    return { width, height: Math.round((width * 9) / 16) }
}

/**
 * The card's left edge in the bar: centred on `x`, kept whole inside the bar (and on a phone up to
 * `overhang` past each end, into the screen's side gutter).
 */
export function cardLeft(x: number, cardWidth: number, barWidth: number, overhang = 0): number {
    const min = overhang > 0 ? -overhang : 0
    const max = barWidth - cardWidth + overhang
    if (max < min) return (barWidth - cardWidth) / 2
    return Math.max(min, Math.min(max, x - cardWidth / 2))
}

/**
 * The moment a frame for `seconds` is grabbed at: the middle of its slot. A slot is a hundred and
 * twentieth of the video, at least a second and at most ten, so a short video still has a frame a
 * second and a long one is not asked for a frame per pixel.
 */
export function frameSlot(duration: number, seconds: number): number {
    const step = Math.max(1, Math.min(10, Number.isFinite(duration) && duration > 0 ? duration / 120 : 10))
    const s = Math.max(0, Number.isFinite(seconds) ? seconds : 0)
    const at = Math.floor(s / step) * step + step / 2
    return Math.round((duration > 0 ? Math.min(at, Math.max(0, duration - 0.05)) : at) * 1000) / 1000
}

export interface MapFrame {
    t: number
    x: number
    y: number
}

/**
 * A storyboard's map. Telegram writes `frame_width=`, `frame_height=` and then one `seconds,x,y`
 * line per frame; a WebVTT map (a cue a tile, `sprite.jpg#xywh=x,y,w,h`) is read too. Lines that
 * do not parse are skipped. Null where nothing usable is in it.
 */
export function parseStoryboardMap(text: string): { width: number; height: number; frames: MapFrame[] } | null {
    let width = 0
    let height = 0
    const frames: MapFrame[] = []
    const lines = text.split(/\r?\n/)
    let cueStart: number | null = null
    for (const raw of lines) {
        const line = raw.trim()
        if (!line) continue
        if (line.startsWith('frame_width=')) width = Number(line.slice(12)) || 0
        else if (line.startsWith('frame_height=')) height = Number(line.slice(13)) || 0
        else if (line.includes('-->')) cueStart = vttClock(line.split('-->')[0].trim())
        else if (line.includes('#xywh=')) {
            const [x, y, w, h] = line.slice(line.indexOf('#xywh=') + 6).split(',').map(Number)
            if (cueStart !== null && [x, y, w, h].every(Number.isFinite) && w > 0 && h > 0) {
                frames.push({ t: cueStart, x, y })
                if (!width) width = w
                if (!height) height = h
            }
            cueStart = null
        } else if (line.includes(',')) {
            const [t, x, y] = line.split(',').map(Number)
            if ([t, x, y].every(Number.isFinite) && t >= 0 && x >= 0 && y >= 0) frames.push({ t, x, y })
        }
    }
    if (!(width > 0) || !(height > 0) || width > 4096 || height > 4096 || !frames.length) return null
    frames.sort((a, b) => a.t - b.t)
    return { width, height, frames }
}

function vttClock(value: string): number | null {
    const parts = value.split(':').map(Number)
    if (!parts.length || parts.length > 3 || !parts.every(Number.isFinite)) return null
    return parts.reduce((total, p) => total * 60 + p, 0)
}

/** iPhone and iPad (an iPad says it is a Mac with a touch screen): no second video element there. */
export const appleTouch =
    typeof navigator !== 'undefined' && (/iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1))

/**
 * The video's own frames for the card, for a file the browser plays natively: a hidden, muted
 * second element on the same stream URL, seeked to the slot's moment and drawn to a canvas. One
 * seek at a time, the latest asked for next, no more than one every [THROTTLE_MS]; frames are kept
 * as small JPEG blobs, the oldest dropped past [CACHE]. Not made on an iPhone or iPad (Safari's
 * memory and range limits), on the TV layout, or for a file played through remux or libav.js.
 */
export class FrameGrabber {
    private el: HTMLVideoElement | null = null
    private canvas: HTMLCanvasElement | null = null
    private cache = new Map<number, string>()
    private missed = new Map<number, number>()
    private wanted: number | null = null
    private busy = false
    private last = 0
    private timer = 0
    private closed = false
    private listeners = new Set<() => void>()

    private readonly url: string

    constructor(url: string) {
        this.url = url
    }

    /** The frame grabbed for slot `at` (an object URL), if it is in. */
    frame(at: number): string | undefined {
        const url = this.cache.get(at)
        if (url) {
            // Kept as the most recent.
            this.cache.delete(at)
            this.cache.set(at, url)
        }
        return url
    }

    /** Whether slot `at` could not be grabbed a moment ago. */
    declined(at: number): boolean {
        const when = this.missed.get(at)
        return when !== undefined && Date.now() - when < MISS_MEMORY_MS
    }

    /** Calls `fn` whenever a grab ends, picture or none; returns the way to stop. */
    listen(fn: () => void): () => void {
        this.listeners.add(fn)
        return () => this.listeners.delete(fn)
    }

    want(at: number) {
        if (this.closed) return
        if (this.cache.has(at) || this.declined(at)) return this.tell()
        this.wanted = at
        this.pump()
    }

    private pump() {
        if (this.closed || this.busy || this.wanted === null) return
        const wait = this.last + THROTTLE_MS - Date.now()
        if (wait > 0) {
            clearTimeout(this.timer)
            this.timer = window.setTimeout(() => this.pump(), wait)
            return
        }
        const at = this.wanted
        this.wanted = null
        this.busy = true
        this.last = Date.now()
        this.grab(at)
            .then((url) => {
                if (this.closed) return url && URL.revokeObjectURL(url)
                if (url) this.keep(at, url)
                else this.missed.set(at, Date.now())
            })
            .catch((e) => {
                console.warn('[player] preview frame', at, e)
                this.missed.set(at, Date.now())
            })
            .finally(() => {
                this.busy = false
                if (this.closed) return
                this.tell()
                this.pump()
            })
    }

    private keep(at: number, url: string) {
        this.cache.set(at, url)
        while (this.cache.size > CACHE) {
            const [oldest, old] = this.cache.entries().next().value as [number, string]
            this.cache.delete(oldest)
            URL.revokeObjectURL(old)
        }
        if (this.missed.size > CACHE) this.missed.clear()
    }

    private tell() {
        for (const fn of this.listeners) fn()
    }

    private element(): HTMLVideoElement {
        if (this.el) return this.el
        const v = document.createElement('video')
        v.muted = true
        v.playsInline = true
        v.preload = 'metadata'
        v.disablePictureInPicture = true
        v.src = this.url
        this.el = v
        return v
    }

    private async grab(at: number): Promise<string | null> {
        const v = this.element()
        if (v.error) return null
        if (v.readyState < 1) await settle(v, 'loadedmetadata', GRAB_TIMEOUT_MS)
        if (this.closed || v.error) return null
        v.currentTime = at
        // A seek that timed out may still show the frame before it: no picture rather than a wrong one.
        if (!(await settle(v, 'seeked', GRAB_TIMEOUT_MS))) return null
        if (this.closed || v.readyState < 2 || !v.videoWidth || !v.videoHeight) return null
        const width = Math.min(FRAME_WIDTH, v.videoWidth)
        const height = Math.max(1, Math.round((width * v.videoHeight) / v.videoWidth))
        const c = (this.canvas ??= document.createElement('canvas'))
        c.width = width
        c.height = height
        const g = c.getContext('2d')
        if (!g) return null
        g.drawImage(v, 0, 0, width, height)
        const blob = await new Promise<Blob | null>((done) => c.toBlob(done, 'image/jpeg', 0.82))
        return blob ? URL.createObjectURL(blob) : null
    }

    /** Stops the element and frees every frame: leaving the player, or a change of source. */
    destroy() {
        if (this.closed) return
        this.closed = true
        clearTimeout(this.timer)
        this.listeners.clear()
        if (this.el) {
            this.el.removeAttribute('src')
            this.el.load()
            this.el = null
        }
        this.canvas = null
        for (const url of this.cache.values()) URL.revokeObjectURL(url)
        this.cache.clear()
        this.missed.clear()
    }
}

/** Resolves true on `event`, false on an error or after `ms`, whichever comes first. */
function settle(v: HTMLVideoElement, event: string, ms: number): Promise<boolean> {
    return new Promise((done) => {
        const finish = (ok: boolean) => {
            clearTimeout(timer)
            v.removeEventListener(event, hit)
            v.removeEventListener('error', miss)
            done(ok)
        }
        const hit = () => finish(true)
        const miss = () => finish(false)
        const timer = window.setTimeout(miss, ms)
        v.addEventListener(event, hit)
        v.addEventListener('error', miss)
    })
}

const THROTTLE_MS = 150
const GRAB_TIMEOUT_MS = 4000
const MISS_MEMORY_MS = 4000
const CACHE = 40
const FRAME_WIDTH = 480

/**
 * The seek bar's pictures are hidden on every device for now (Trickplay.PICTURES_SHOWN in core):
 * what showed was mostly Telegram's own thumbnail. Scrubbing shows the time in a chip alone.
 */
export const PICTURES_SHOWN = false

/** No answer from any source for this long and the card shows its fallback instead of shimmering. */
export const GIVE_UP_MS = 1500

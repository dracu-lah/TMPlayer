// The seek bar and the preview card over it (the apps' SeekBar and StoryboardPreview's SeekPreview).
import { SEEK_SHORT } from '../playerKeys'
import { createEffect, createMemo, createSignal, For, on, onCleanup, Show } from 'solid-js'
import { t } from '../../i18n'
import { frameAt, type Frames } from '../storyboard'
import { cardLeft, cardSize, frameSlot, GIVE_UP_MS, PICTURES_SHOWN, type FrameGrabber } from '../seekpreview'
import { coverBackground } from '../../screens/cover'
import { loadThumb } from '../../screens/thumbs'
import { fineRate } from '../extras'
import type { VideoItem } from '../../tg/types'
import { clock } from './parts'

/**
 * The seek bar: the played run in the accent over the buffered run, a thumb that grows while
 * held, and over it the preview card for the time under the pointer or the finger, or the TV's
 * place after an arrow press ([PreviewCard], StoryboardPreview's SeekPreview). The seek lands on
 * release.
 */
export function SeekBar(props: {
    time: () => number
    duration: () => number
    buffered: () => number
    frames: () => Frames | null
    grabber: () => FrameGrabber | null
    item: VideoItem
    /** The TV's place after an arrow press, for the card; null otherwise. */
    peek: () => number | null
    tv: boolean
    dragTime: () => number | null
    setDragTime: (s: number | null) => void
    onSeek: (s: number) => void
    /** A key on the bar: the one stepper (seekStepper.ts) answers, so a held arrow neither storms nor skips the video's pace. */
    onStep?: (forward: boolean, repeat: boolean) => void
    phone: boolean
    /** The chapter starts, drawn as gaps in the bar. */
    marks: () => number[]
    /** The chapter's name for a time, for the preview card. */
    chapterAt: (s: number) => string | null
}) {
    let bar!: HTMLDivElement
    const [hoverX, setHoverX] = createSignal<number | null>(null)
    const [barWidth, setBarWidth] = createSignal(0)
    const fraction = (s: number) => (props.duration() ? Math.max(0, Math.min(1, s / props.duration())) : 0)
    const timeAt = (clientX: number) => {
        const r = bar.getBoundingClientRect()
        setBarWidth(r.width)
        return Math.max(0, Math.min(1, (clientX - r.left) / r.width)) * props.duration()
    }
    const xOf = (clientX: number) => clientX - bar.getBoundingClientRect().left
    let dragging = false
    // A finger moving off the bar slows the drag to a half and a quarter (FineScrub): the bar's position is its own, not the finger's.
    let fine = { x: 0, last: 0, y: 0 }
    const [slow, setSlow] = createSignal(1)
    const down = (e: PointerEvent) => {
        if (!props.duration() || e.button > 0) return
        dragging = true
        bar.setPointerCapture(e.pointerId)
        const r = bar.getBoundingClientRect()
        fine = { x: e.clientX, last: e.clientX, y: r.top + r.height / 2 }
        setSlow(1)
        props.setDragTime(timeAt(e.clientX))
        if (e.pointerType === 'mouse') setHoverX(xOf(e.clientX))
        e.stopPropagation()
    }
    const move = (e: PointerEvent) => {
        if (dragging) {
            if (e.pointerType === 'mouse') props.setDragTime(timeAt(e.clientX))
            else {
                const rate = fineRate(e.clientY - fine.y)
                const r = bar.getBoundingClientRect()
                fine.x = Math.max(r.left, Math.min(r.right, fine.x + (e.clientX - fine.last) * rate))
                fine.last = e.clientX
                setSlow(rate)
                props.setDragTime(timeAt(fine.x))
            }
        }
        if (e.pointerType === 'mouse') {
            setBarWidth(bar.getBoundingClientRect().width)
            setHoverX(xOf(e.clientX))
        }
    }
    const up = () => {
        if (!dragging) return
        dragging = false
        const s = props.dragTime()
        if (s !== null) props.onSeek(s)
        props.setDragTime(null)
        setSlow(1)
    }
    const played = () => fraction(props.dragTime() ?? props.time())
    const ahead = () => fraction(props.buffered())
    // Where the card points: the finger while dragging, else the pointer.
    const at = createMemo(() => {
        const d = props.dragTime()
        if (d !== null) return { time: d, x: fraction(d) * (barWidth() || bar?.clientWidth || 0) }
        const x = hoverX()
        if (x === null || !props.duration()) return null
        const w = barWidth() || bar.clientWidth
        return { time: (Math.max(0, Math.min(w, x)) / w) * props.duration(), x }
    })
    // The TV's arrows: the card over the new place for a moment.
    const card = createMemo(() => {
        const a = at()
        if (a) return a
        const p = props.peek()
        if (p === null || !props.duration()) return null
        return { time: p, x: fraction(p) * (barWidth() || bar?.clientWidth || 0) }
    })
    return (
        <div
            ref={bar}
            class="pc-bar"
            classList={{ thick: hoverX() !== null || props.dragTime() !== null }}
            role="slider"
            tabIndex={0}
            aria-label={t('web.seek_bar')}
            aria-valuemin={0}
            aria-valuemax={Math.round(props.duration())}
            aria-valuenow={Math.round(props.time())}
            aria-valuetext={`${clock(props.time())} / ${clock(props.duration())}`}
            onPointerDown={down}
            onPointerMove={move}
            onPointerUp={up}
            onPointerCancel={up}
            onPointerLeave={(e) => e.pointerType === 'mouse' && !dragging && setHoverX(null)}
            onKeyDown={(e) => {
                if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
                e.preventDefault()
                e.stopPropagation()
                if (props.onStep) props.onStep(e.key === 'ArrowRight', e.repeat)
                else props.onSeek(props.time() + (e.key === 'ArrowRight' ? SEEK_SHORT : -SEEK_SHORT))
            }}
        >
            <div class="pc-track">
                <Show when={ahead() > played()}>
                    <div class="pc-buffered" style={{ width: `${ahead() * 100}%` }} />
                </Show>
                <div class="pc-played" style={{ width: `${played() * 100}%` }} />
                <For each={props.marks()}>{(m) => <span class="pc-mark" style={{ left: `${fraction(m) * 100}%` }} />}</For>
            </div>
            <div class="pc-thumb" style={{ left: `${played() * 100}%` }} />
            <Show when={card()}>
                {(k) => (
                    <PreviewCard
                        time={() => k().time}
                        x={() => k().x}
                        barWidth={() => barWidth() || bar.clientWidth}
                        duration={props.duration}
                        frames={props.frames}
                        grabber={props.grabber}
                        item={props.item}
                        phone={props.phone}
                        tv={props.tv}
                        chapter={() => props.chapterAt(k().time)}
                        slow={slow}
                    />
                )}
            </Show>
        </div>
    )
}

/**
 * The seek bar's card (StoryboardPreview's SeekPreview): always shown while scrubbing, 16:9 at a
 * standard size, with the time in a chip at its foot. Telegram's storyboard tile where the video
 * has one, else the video's own frame from [FrameGrabber]; the skeleton shimmers until a picture
 * for the moment is in, the last one stays up while the next is grabbed, and where nothing has
 * answered within [GIVE_UP_MS] it shows the video's thumbnail dimmed (or the title's cover) with
 * the time large, so it never shimmers for ever.
 */
function PreviewCard(props: {
    time: () => number
    x: () => number
    barWidth: () => number
    duration: () => number
    frames: () => Frames | null
    grabber: () => FrameGrabber | null
    item: VideoItem
    phone: boolean
    tv: boolean
    chapter: () => string | null
    slow: () => number
}) {
    const size = createMemo(() => {
        const w = props.barWidth()
        if (props.tv) {
            const width = Math.round(Math.max(240, Math.min(400, w * 0.22)))
            return { width, height: Math.round((width * 9) / 16) }
        }
        return cardSize(props.phone, w)
    })
    const left = () => cardLeft(props.x(), size().width, props.barWidth(), props.phone ? 16 : 0)
    // A grab ending (picture or none) makes the card look again.
    const [tick, setTick] = createSignal(0)
    createEffect(() => {
        const g = props.grabber()
        if (!g) return
        const stop = g.listen(() => setTick((n) => n + 1))
        onCleanup(stop)
    })
    const slot = createMemo(() => frameSlot(props.duration(), props.time()))
    const tile = createMemo(() => {
        const f = props.frames()
        if (!f) return null
        const { width, height } = size()
        // Letterboxed: the tile's own shape inside the 16:9 card.
        const scale = Math.min(width / f.width, height / f.height)
        const p = frameAt(f, props.time())
        return {
            width: `${Math.round(f.width * scale)}px`,
            height: `${Math.round(f.height * scale)}px`,
            'background-image': `url(${f.url})`,
            'background-size': `${f.spriteWidth * scale}px ${f.spriteHeight * scale}px`,
            'background-position': `${-p.x * scale}px ${-p.y * scale}px`,
        }
    })
    const grabbed = createMemo(() => {
        tick()
        return props.grabber()?.frame(slot()) ?? null
    })
    const declined = createMemo(() => {
        tick()
        return props.grabber()?.declined(slot()) ?? false
    })
    // Asked once per slot, and not where the storyboard answers; the grabber throttles and keeps only the newest wish.
    createEffect(
        on(slot, (s) => {
            const g = props.grabber()
            if (g && !tile()) g.want(s)
        }),
    )
    const [last, setLast] = createSignal<string | null>(null)
    createEffect(() => {
        const u = grabbed()
        if (u) setLast(u)
    })
    const [late, setLate] = createSignal(false)
    createEffect(
        on(slot, () => {
            setLate(false)
            const id = window.setTimeout(() => setLate(true), GIVE_UP_MS)
            onCleanup(() => clearTimeout(id))
        }),
    )
    const gaveUp = () => !tile() && !grabbed() && (late() || declined())
    const picture = () => (tile() ? null : (grabbed() ?? (gaveUp() ? null : last())))
    const [thumb, setThumb] = createSignal<string | null>(null)
    let thumbAsked = false
    createEffect(() => {
        if (!gaveUp() || thumbAsked || !props.item.thumb) return
        thumbAsked = true
        loadThumb(props.item.thumb).then(setThumb, () => {})
    })
    return (
        <div class="pc-preview" style={{ left: `${left()}px`, width: `${size().width}px` }} aria-hidden="true">
            <Show when={PICTURES_SHOWN} fallback={<span class="pc-chip">{clock(props.time())}</span>}>
            <div class="pc-card" style={{ width: `${size().width}px`, height: `${size().height}px` }}>
                <Show
                    when={tile()}
                    fallback={
                        <Show
                            when={picture()}
                            fallback={
                                <Show when={gaveUp()} fallback={<div class="pc-card-wait" />}>
                                    <div class="pc-card-fallback" style={{ background: coverBackground(props.item.title) }}>
                                        <Show when={thumb()}>{(u) => <img class="pc-card-thumb" src={u()} alt="" />}</Show>
                                        <span class="pc-card-time">{clock(props.time())}</span>
                                    </div>
                                </Show>
                            }
                        >
                            {(u) => <img class="pc-card-pic" src={u()} alt="" />}
                        </Show>
                    }
                >
                    {(style) => <div class="pc-card-tile" style={style()} />}
                </Show>
                <Show when={tile() || picture() || !gaveUp()}>
                    <span class="pc-chip">{clock(props.time())}</span>
                </Show>
            </div>
            </Show>
            <Show when={props.chapter()}>{(name) => <span class="pc-chapter">{name()}</span>}</Show>
            <Show when={props.slow() < 1}>
                <span class="pc-slow">{t(props.slow() <= 0.25 ? 'player.scrub_quarter' : 'player.scrub_half')}</span>
            </Show>
        </div>
    )
}

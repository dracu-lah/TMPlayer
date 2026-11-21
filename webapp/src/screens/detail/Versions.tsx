// The copies of a video the chat holds, and the repair offer for a broken one.
import { createSignal, For, onMount, Show } from 'solid-js'
import { useLocation, useNavigate } from '@solidjs/router'
import { Link04Icon, Tick02Icon, Unlink04Icon } from '@hugeicons/core-free-icons'
import { t } from '../../i18n'
import '../series.css'
import { Icon } from '../../ui/Icon'
import { arrowKeys } from '../../ui/arrows'
import { form } from '../../form'
import { detailPath, shortLength, size } from '../VideoList'
import { chooseCopyOf, isApart, keepApart } from '../../copies'
import '../versions.css'
import type { CopyRef, VideoItem, WatchInfo } from '../../tg/types'
import { dot } from '../../ui/dot'


/** Rows of a long copies list shown at once; More shows the next as many. */
const COPY_PAGE = 50

/**
 * The copies of this video the chat holds (the apps' Versions): one quiet line naming the copy this
 * page is about ("1080p · H.264 · Blu-ray · 885 MB · from Film Club") with Change at its end, and
 * under it, once Change is pressed, every copy as a row of its own to choose from, recommended
 * first with "Best for this device" (HX38: one this browser plays natively, a phone's 1080p before
 * 4K), the count the card says. Choosing one remembers it for the video (its cards play it from
 * then on) and turns the page to that copy, so Play and the facts speak of it; the remote and the
 * keyboard land back on this line.
 */
export function Versions(props: { item: VideoItem; info: WatchInfo }) {
    const navigate = useNavigate()
    const location = useLocation<{ video?: VideoItem; versions?: boolean }>()
    const [open, setOpen] = createSignal(false)
    const [shown, setShown] = createSignal(COPY_PAGE)
    let summaryButton: HTMLButtonElement | undefined
    let list: HTMLUListElement | undefined
    const isHere = (c: CopyRef) => c.item.messageId === props.item.messageId && c.item.chatId === props.item.chatId
    const here = () => props.info.copies.find(isHere)
    const heading = (c: CopyRef) => (c.facts.length ? c.facts.join(dot) : size(c.item.size))
    const summary = () => {
        const c = here()
        return c ? [heading(c), c.facts.length ? size(c.item.size) : '', t('detail.copy_from', { chat: c.chat })].filter(Boolean).join(dot) : ''
    }
    const device = () => (form() === 'tv' ? 'tv' : form() === 'phone' ? 'phone' : 'computer')
    // CopyRef.date is in seconds.
    const datesDiffer = () => new Set(props.info.copies.map((c) => Math.floor(c.date / 86_400))).size > 1
    const detail = (c: CopyRef) =>
        [
            c.fit === 'stutter' ? t('detail.copy_may_stutter', { device: device() }) : c.fit === 'smooth' ? t('detail.copy_smooth', { device: device() }) : '',
            c.facts.length ? size(c.item.size) : '',
            shortLength(c.item.duration),
            datesDiffer() ? t('detail.copy_posted', { date: new Date(c.item.date).toLocaleDateString('en', { day: 'numeric', month: 'short', year: 'numeric' }) }) : '',
            c.started ? t('detail.copy_started') : '',
        ]
            .filter(Boolean)
            .join(dot)
    const pick = (c: CopyRef) => {
        setOpen(false)
        // Chosen even when it is the copy shown: the viewer pins it over the recommendation for its cards.
        chooseCopyOf(c.item, props.info.copies.map((x) => x.item))
        if (isHere(c)) return void summaryButton?.focus()
        navigate(detailPath(c.item), { replace: true, state: { video: c.item, versions: true } })
    }
    const more = () => {
        const from = shown()
        setShown(from + COPY_PAGE)
        // The first row the press brought in takes the focus, so the remote and the keys go on from there.
        queueMicrotask(() => list?.querySelectorAll<HTMLElement>('[role=option]')[from]?.focus())
    }
    // Back from a choice (the page turned to the copy chosen): the focus is on this line again, not on Play.
    onMount(() => {
        if (!location.state?.versions) return
        requestAnimationFrame(() => summaryButton?.focus())
    })
    return (
        <section class="detail-versions" aria-label={t('detail.copies_title')}>
            <button type="button" class="detail-copy-summary" ref={summaryButton} aria-expanded={open()} onClick={() => setOpen(!open())}>
                <span class="detail-copy-text">
                    <span class="detail-copy-line">{summary()}</span>
                    <span class="muted small">{t('detail.versions', { count: props.info.copies.length })}</span>
                </span>
                <span class="detail-copy-change">{open() ? t('detail.copy_done') : t('detail.copy_change')}</span>
            </button>
            <Show when={open()}>
                <ul class="detail-copy-list" ref={list} role="listbox" aria-label={t('detail.copies_title')} onKeyDown={(e) => form() !== 'tv' && arrowKeys(e, e.currentTarget, '[role=option]', { vertical: true })}>
                    <For each={props.info.copies.slice(0, shown())}>
                        {(c) => (
                            <li role="presentation">
                                <button type="button" role="option" class="detail-copy" aria-selected={isHere(c)} onClick={() => pick(c)}>
                                    <span class="detail-copy-text">
                                        <span class="detail-copy-line">
                                            <span>{heading(c)}</span>
                                            <Show when={c.best}>
                                                <span class="detail-copy-best">{t('detail.copy_best')}</span>
                                            </Show>
                                        </span>
                                        <span class="muted small" classList={{ 'detail-copy-stutter': c.fit === 'stutter' }}>{detail(c)}</span>
                                        <span class="muted small detail-copy-file">{c.fileName}</span>
                                    </span>
                                    <Show when={isHere(c)}>
                                        <Icon icon={Tick02Icon} size={22} />
                                    </Show>
                                </button>
                            </li>
                        )}
                    </For>
                </ul>
                <Show when={props.info.copies.length > shown()}>
                    <button type="button" class="link detail-copy-more" onClick={more}>
                        {`${t('common.more')}${dot}${props.info.copies.length - shown()}`}
                    </button>
                </Show>
            </Show>
        </section>
    )
}

/**
 * "Not the same video" under the copies (the apps' repair, last on the page): this file stands
 * apart from the copies it was grouped with, its own card and page from then on; on a file kept
 * apart, "Group with its copies again". Nothing is lost either way, so it asks nothing; the focus
 * stays on the line, which turns into the other once the page has read again.
 */
export function CopyRepair(props: { item: VideoItem; grouped: boolean }) {
    let wrap: HTMLDivElement | undefined
    const apart = () => isApart(props.item)
    const press = (on: boolean) => {
        keepApart(props.item, on)
        const refocus = (tries: number) => {
            const next = wrap?.querySelector<HTMLButtonElement>('button')
            if (next && next.dataset.on !== String(on)) return next.focus()
            if (tries > 0) setTimeout(() => refocus(tries - 1), 100)
        }
        queueMicrotask(() => refocus(30))
    }
    return (
        <div class="detail-copy-repair" ref={wrap}>
            <Show when={apart()}>
                <button type="button" class="detail-copy-repair-button" data-on="false" onClick={() => press(false)}>
                    <Icon icon={Link04Icon} size={20} />
                    <span>{t('grid.same_video_again')}</span>
                </button>
            </Show>
            <Show when={!apart() && props.grouped}>
                <button type="button" class="detail-copy-repair-button" data-on="true" onClick={() => press(true)}>
                    <Icon icon={Unlink04Icon} size={20} />
                    <span class="detail-copy-text">
                        <span>{t('grid.not_same_video')}</span>
                        <span class="muted small">{t('grid.not_same_video_detail')}</span>
                    </span>
                </button>
            </Show>
        </div>
    )
}

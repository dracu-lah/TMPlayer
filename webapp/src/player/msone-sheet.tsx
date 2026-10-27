// MSone's Malayalam subtitles for the video playing (plan CP33 B4), behind the hidden flag
// (msoneFlag.ts): the subtitle menu's "Malayalam subtitles from MSone" opens it. It checks the
// gateway's state (kept for hours, so opening it is cheap), searches with the video's own title,
// season and episode, and a picked release is downloaded through the gateway and laid over the
// video as a picked file would be. Whatever goes wrong says so in the list's place, with a way on
// (Try again or Check again) and the file picker beside it. While MSone is paused, the releases of
// the last search whose file is kept on the device are listed under the state, and still load.
import { createSignal, For, Match, onCleanup, onMount, Show, Switch } from 'solid-js'
import { t } from '../i18n'
import { stateLine } from '../msoneFlag'
import { PhoneSheet, variant } from './sheets'
import { download, health, keptHits, knownHealth, search, type Ask, type Failure, type Health, type Hit } from './msone'

/** What the search asks about the video: its cleaned title and year, and the episode it is. */
export interface MsoneTarget extends Ask {
    q: string
    season: number | null
    episode: number | null
}

type Phase = 'checking' | 'searching' | 'results' | 'failed' | 'state'

/** A failure's line in the app's words; null for one that says nothing (the sheet closed). */
function failureLine(f: Failure): string | null {
    switch (f.kind) {
        case 'too_short':
            return t('msone.too_short')
        case 'no_results':
            return t('msone.no_results')
        case 'busy':
            return t('msone.busy')
        case 'offline':
            return t('msone.offline')
        case 'unreadable':
        case 'no_unzip':
            return t('msone.unreadable')
        case 'state':
            return stateLine(f.state)
        case 'aborted':
            return null
    }
}

/**
 * The track's name, as the apps write it: "Malayalam · MSone" (online.track_label). A browser
 * without Intl.DisplayNames (the TV build's oldest engines) gets "Subtitles by MSone".
 */
function trackLabel(): string {
    try {
        const language = new Intl.DisplayNames(['en'], { type: 'language' }).of('ml')
        if (language && language !== 'ml') return t('online.track_label', { language, provider: 'MSone' })
    } catch {}
    return t('msone.credit')
}

/** "2020  ·  Season pack  ·  S1 E3": what a hit says of itself under its title. */
function facts(hit: Hit): string {
    const ep = hit.season !== null && hit.episode !== null ? `S${hit.season} E${hit.episode}` : hit.season !== null ? `S${hit.season}` : hit.episode !== null ? `E${hit.episode}` : ''
    return [hit.year !== null ? String(hit.year) : '', hit.kind === 'pack' ? t('msone.pack') : '', ep].filter((p) => p).join('  ·  ')
}

export function MsoneSheet(props: {
    target: MsoneTarget
    /** Lays the file over the video; throws when it cannot be read. */
    onLoad: (file: File, as: { label: string; language: string }) => Promise<void>
    onPickFile: () => void
    onClose: () => void
}) {
    const [phase, setPhase] = createSignal<Phase>('checking')
    const [status, setStatus] = createSignal<Health>(knownHealth())
    const [failure, setFailure] = createSignal<Failure | null>(null)
    const [hits, setHits] = createSignal<Hit[]>([])
    /** While MSone is paused: the last search's hits whose file is kept, which still load. */
    const [kept, setKept] = createSignal<Hit[]>([])
    /** The hit being downloaded: another pick meanwhile is ignored. */
    const [running, setRunning] = createSignal<string | null>(null)
    const [hitFailure, setHitFailure] = createSignal<{ id: string; failure: Failure } | null>(null)
    // Closing the sheet stops whatever it is waiting for.
    const work = new AbortController()
    let closed = false
    let box!: HTMLDivElement
    onCleanup(() => {
        closed = true
        work.abort()
    })

    /** The remote (and the keyboard) lands on the first result, or on the first way on; a phone's touch needs no focus. */
    const focusFirst = () =>
        queueMicrotask(() => {
            if (closed || !box || variant() === 'phone') return
            box.querySelector<HTMLElement>('.pc-msone-pick, .pc-msone-state button')?.focus({ preventScroll: true })
        })

    const where = () => ({ season: props.target.season, episode: props.target.episode })
    const showState = async (h: Health) => {
        setStatus(h)
        setKept([])
        setPhase('state')
        if (h.state === 'paused') {
            const list = await keptHits(props.target, where())
            if (!closed && status().state === 'paused') setKept(list)
        }
        focusFirst()
    }

    const run = async (force: boolean) => {
        setFailure(null)
        setHitFailure(null)
        setPhase('checking')
        const h = await health(force)
        if (closed) return
        setStatus(h)
        if (h.state !== 'on') return void showState(h)
        setPhase('searching')
        const r = await search(props.target, work.signal)
        if (closed) return
        if (r.ok) {
            setHits(r.hits)
            setPhase('results')
            focusFirst()
        } else if (r.failure.kind === 'state') void showState(knownHealth())
        else if (r.failure.kind !== 'aborted') {
            setFailure(r.failure)
            setPhase('failed')
            focusFirst()
        }
    }

    const pick = async (hit: Hit) => {
        if (running() !== null) return
        setRunning(hit.id)
        setHitFailure(null)
        try {
            const r = await download(hit, where(), work.signal)
            if (closed) return
            if (!r.ok) {
                if (r.failure.kind === 'state') return void showState(knownHealth())
                if (r.failure.kind !== 'aborted') setHitFailure({ id: hit.id, failure: r.failure })
                return
            }
            try {
                await props.onLoad(r.file, { label: trackLabel(), language: 'ml' })
            } catch {
                if (!closed) setHitFailure({ id: hit.id, failure: { kind: 'unreadable' } })
                return
            }
            if (!closed) props.onClose()
        } finally {
            if (!closed) setRunning(null)
        }
    }

    onMount(() => void run(false))

    const HitList = (p: { list: Hit[] }) => (
        <ul class="pc-msone-hits">
            <For each={p.list}>
                {(hit) => (
                    <li>
                        <button type="button" class="pc-msone-pick" aria-busy={running() === hit.id} onClick={() => void pick(hit)}>
                            <span class="pc-msone-name">{hit.title}</span>
                            <Show when={facts(hit)}>
                                <span class="pc-msone-facts">{facts(hit)}</span>
                            </Show>
                            <Show when={running() === hit.id}>
                                <span class="pc-msone-loading" role="status">
                                    <span class="pc-msone-spin" aria-hidden="true" />
                                    {t('common.loading')}
                                </span>
                            </Show>
                        </button>
                        <div class="pc-msone-links">
                            <Show when={hit.translator}>
                                {(tr) => (
                                    <Show when={tr().url} fallback={<span>{t('msone.translator', { name: tr().name })}</span>}>
                                        <a href={tr().url!} target="_blank" rel="noopener noreferrer">{t('msone.translator', { name: tr().name })}</a>
                                    </Show>
                                )}
                            </Show>
                            <Show when={hit.pageUrl}>
                                <a href={hit.pageUrl!} target="_blank" rel="noopener noreferrer">{t('msone.release_page')}</a>
                            </Show>
                            <span>{t('msone.credit')}</span>
                        </div>
                        <Show when={hitFailure()?.id === hit.id && hitFailure()}>
                            {(hf) => (
                                <div class="pc-msone-state" role="status">
                                    <p>{failureLine(hf().failure)}</p>
                                    <div class="pc-msone-actions">
                                        <button type="button" class="pc-sheet-button primary" onClick={() => void pick(hit)}>{t('common.try_again')}</button>
                                        <button type="button" class="pc-sheet-button" onClick={props.onPickFile}>{t('msone.pick_file')}</button>
                                    </div>
                                </div>
                            )}
                        </Show>
                    </li>
                )}
            </For>
        </ul>
    )

    return (
        <PhoneSheet title={t('msone.title')} note={status().state === 'on' ? t('msone.state_on') : undefined} onClose={props.onClose} class="pc-msone-sheet">
            <div ref={box} class="pc-msone">
                <Switch>
                    <Match when={phase() === 'checking' || phase() === 'searching'}>
                        <p class="pc-msone-wait" role="status">
                            <span class="pc-msone-spin" aria-hidden="true" />
                            {phase() === 'checking' ? t('msone.checking') : t('msone.searching')}
                        </p>
                    </Match>
                    <Match when={phase() === 'state'}>
                        <div class="pc-msone-state" role="status">
                            <p>{stateLine(status().state)}</p>
                            <Show when={status().message}>
                                <p class="pc-msone-message">{status().message}</p>
                            </Show>
                            <div class="pc-msone-actions">
                                <button type="button" class="pc-sheet-button primary" onClick={props.onPickFile}>{t('msone.pick_file')}</button>
                                <button type="button" class="pc-sheet-button" onClick={() => void run(true)}>{t('msone.check_again')}</button>
                            </div>
                        </div>
                        <Show when={kept().length}>
                            <HitList list={kept()} />
                        </Show>
                    </Match>
                    <Match when={phase() === 'failed' && failure()}>
                        {(f) => (
                            <div class="pc-msone-state" role="status">
                                <p>{failureLine(f())}</p>
                                <div class="pc-msone-actions">
                                    <button type="button" class="pc-sheet-button primary" onClick={() => void run(false)}>{t('common.try_again')}</button>
                                    <button type="button" class="pc-sheet-button" onClick={props.onPickFile}>{t('msone.pick_file')}</button>
                                </div>
                            </div>
                        )}
                    </Match>
                    <Match when={phase() === 'results'}>
                        <HitList list={hits()} />
                    </Match>
                </Switch>
            </div>
        </PhoneSheet>
    )
}

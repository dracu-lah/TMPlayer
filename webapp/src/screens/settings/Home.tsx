import { createEffect, createMemo, createSignal, For, on, Show } from 'solid-js'
import { A } from '@solidjs/router'
import { ArrowDown01Icon, ArrowUp01Icon, ClapperboardIcon, Clock01Icon, Delete02Icon, Home03Icon, Image01Icon, Message01Icon, Search01Icon, StarIcon, VideoReplayIcon } from '@hugeicons/core-free-icons'
import { clearSearches, homeChatRows, namedLists, settings, setSetting, starredIds } from '../../store'
import { editorRows, encodeOrder, moveKey, parseOrder, type OrderEntry } from '../../ui/homeOrder'
import { t } from '../../i18n'
import { clearHistoryWithUndo } from '../../clearHistory'
import { form } from '../../form'
import { Icon, type IconData } from '../../ui/Icon'
import { ActionRow, Chevron, RowBody, TmdbCredit, ToggleRow } from '../../ui/SettingsParts'
import { rowDomId } from '../SettingsFind'
import { chosenDetail } from './shared'

type Key = Parameters<typeof t>[0]

/** Home's four switches: the setting, its search row, icon and words. The page draws them as rows, the TV as panels. */
export const HOME_SWITCHES: { id: string; row: string; key: 'carousel' | 'homeContinue' | 'homeRecent' | 'homeStarred'; icon: IconData; title: Key; on: Key; off: Key }[] = [
    { id: 'carousel', row: 'home_carousel', key: 'carousel', icon: Home03Icon, title: 'settings.home_carousel', on: 'settings.home_carousel_on', off: 'settings.home_carousel_off' },
    { id: 'home-continue', row: 'home_continue', key: 'homeContinue', icon: VideoReplayIcon, title: 'settings.home_continue', on: 'settings.home_continue_on', off: 'settings.home_continue_off' },
    { id: 'home-recent', row: 'home_recent', key: 'homeRecent', icon: Clock01Icon, title: 'settings.home_recent', on: 'settings.home_recent_on', off: 'settings.home_recent_off' },
    { id: 'home-starred', row: 'home_starred_rows', key: 'homeStarred', icon: StarIcon, title: 'settings.home_starred_rows', on: 'settings.home_starred_rows_on', off: 'settings.home_starred_rows_off' },
]

/**
 * Settings > Home (the apps' Home page, 8f55cf2): a switch for each of Home's rows, and a note that
 * Recently added (and the carousel drawn from it) follows the chats added in Add chats. Its own
 * "Chosen chats" picker repeated Add chats and is gone (2026-11-01).
 */
export function HomePage() {
    return (
        <>
            <div class="set-card">
                <h3 class="set-block-title set-card-title">{t('settings.home_rows')}</h3>
                <For each={HOME_SWITCHES}>
                    {(s) => (
                        <ToggleRow
                            id={s.row}
                            icon={s.icon}
                            title={t(s.title)}
                            subtitle={settings()[s.key] ? t(s.on) : t(s.off)}
                            on={settings()[s.key]}
                            onChange={(on) => setSetting(s.key, on)}
                        />
                    )}
                </For>
            </div>
            <div class="set-card">
                <ToggleRow
                    id="remember_searches"
                    icon={Search01Icon}
                    title={t('settings.remember_searches')}
                    subtitle={settings().rememberSearches ? t('settings.remember_searches_on') : t('settings.remember_searches_off')}
                    on={settings().rememberSearches}
                    onChange={(on) => {
                        setSetting('rememberSearches', on)
                        // Off means nothing kept: the list goes too, as the apps' switch does.
                        if (!on) clearSearches()
                    }}
                />
            </div>
            <p class="muted small">{t('settings.home_follows_added')}</p>
            <HomeOrder />
        </>
    )
}

/** The rows Settings > Home's order lists: Continue watching, Recently added and each starred chat Home drew, in the stored order. */
function homeOrderRows(): OrderEntry[] {
    const chats = homeChatRows().filter((c) => starredIds().has(c.chatId))
    const lists = namedLists().filter((l) => l.info.home && l.lines.length).map((l) => ({ id: l.info.id, name: l.info.name }))
    return editorRows(chats, parseOrder(settings().homeOrder), lists)
}

function homeOrderTitle(row: OrderEntry): string {
    return row.key === 'continue' ? t('settings.home_continue') : row.key === 'recent' ? t('settings.home_recent') : (row.listName ?? row.chatTitle ?? row.key)
}

function homeOrderOff(row: OrderEntry): boolean {
    if (row.listName !== undefined) return false
    return row.key === 'continue' ? !settings().homeContinue : row.key === 'recent' ? !settings().homeRecent : !settings().homeStarred
}

/**
 * Settings > Home, Order of rows (EG-F7, EG-F23): Move up and Move down on every row, and a row can
 * be dragged by a mouse. On a TV the buttons are what the remote reaches (OK presses one). The order
 * is stored as the apps' HomeOrder text and applied by Home's own build in the worker.
 */
export function HomeOrder() {
    const rows = createMemo(homeOrderRows)
    const [dragging, setDragging] = createSignal<string | null>(null)
    const put = (keys: string[]) => setSetting('homeOrder', encodeOrder(keys))
    // The rows are drawn again after a move: the keyboard follows the row (its same button, or the other where this one has reached the end).
    let list!: HTMLOListElement
    let follow: { key: string; by: number } | null = null
    createEffect(
        on(
            rows,
            () => {
                const f = follow
                follow = null
                if (!f) return
                queueMicrotask(() => {
                    const buttons = [...(list.querySelectorAll<HTMLElement>(`[data-key="${CSS.escape(f.key)}"] .order-move`) ?? [])]
                    const same = buttons[f.by > 0 ? 1 : 0]
                    ;((same as HTMLButtonElement | undefined)?.disabled ? buttons[f.by > 0 ? 0 : 1] : same)?.focus()
                })
            },
            { defer: true },
        ),
    )
    const move = (key: string, by: number) => {
        follow = { key, by }
        put(moveKey(rows().map((r) => r.key), key, by))
    }
    // A row dropped on another takes that row's place.
    const drop = (onto: string) => {
        const from = dragging()
        setDragging(null)
        if (!from || from === onto) return
        const keys = rows().map((r) => r.key)
        put(moveKey(keys, from, keys.indexOf(onto) - keys.indexOf(from)))
    }
    return (
        <div class="set-card" id={rowDomId('home_order')}>
            <h3 class="set-block-title set-card-title">{t('settings.home_order')}</h3>
            <ol class="order-list" ref={list}>
                <For each={rows()}>
                    {(row, i) => (
                        <li
                            class="order-row"
                            data-key={row.key}
                            classList={{ 'order-dragging': dragging() === row.key }}
                            draggable={form() === 'desktop'}
                            onDragStart={() => setDragging(row.key)}
                            onDragEnd={() => setDragging(null)}
                            onDragOver={(e) => dragging() && e.preventDefault()}
                            onDrop={(e) => {
                                e.preventDefault()
                                drop(row.key)
                            }}
                        >
                            <span class="set-text">
                                <span class="set-title">{homeOrderTitle(row)}</span>
                                <Show when={homeOrderOff(row)}>
                                    <span class="set-sub">{t('common.off')}</span>
                                </Show>
                            </span>
                            <button type="button" class="order-move" aria-label={`${t('settings.home_order_up')}: ${homeOrderTitle(row)}`} disabled={i() === 0} onClick={() => move(row.key, -1)}>
                                <Icon icon={ArrowUp01Icon} size={20} />
                            </button>
                            <button type="button" class="order-move" aria-label={`${t('settings.home_order_down')}: ${homeOrderTitle(row)}`} disabled={i() === rows().length - 1} onClick={() => move(row.key, 1)}>
                                <Icon icon={ArrowDown01Icon} size={20} />
                            </button>
                        </li>
                    )}
                </For>
            </ol>
            <p class="muted small order-hint">{form() === 'desktop' ? t('settings.home_order_hint_web') : t('settings.home_order_hint_buttons')}</p>
            <Show when={settings().homeOrder !== ''}>
                <button class="outline-button order-reset" type="button" onClick={() => put([])}>
                    {t('settings.home_order_reset')}
                </button>
            </Show>
        </div>
    )
}

/** The library's rows, under Home's on the one Home and library page. */
export function Library() {
    return (
        <>
            <div class="set-card">
                <A class="set-row" id={rowDomId('chosen_chats')} href="/added-chats">
                    <RowBody icon={Message01Icon} title={t('picker.row_title')} subtitle={chosenDetail()} />
                    <Chevron />
                </A>
                <ToggleRow
                    id="posters"
                    icon={Image01Icon}
                    title={t('web.posters_title')}
                    subtitle={settings().posters ? t('web.posters_on_detail') : t('metadata.toggle_off')}
                    on={settings().posters}
                    onChange={(on) => setSetting('posters', on)}
                />
                <ToggleRow
                    id="trailers_in_app"
                    icon={ClapperboardIcon}
                    service="youtube"
                    title={t('metadata.trailers_in_app')}
                    subtitle={t('metadata.trailers_in_app_detail')}
                    on={settings().trailersInApp}
                    onChange={(on) => setSetting('trailersInApp', on)}
                />
                <div class="set-block set-credit">
                    <TmdbCredit />
                </div>
            </div>
            <div class="set-card">
                {/* One row for Continue watching and Watched together; Watch later is kept. Done at once, with Undo. */}
                <ActionRow row="clear_history" icon={Delete02Icon} title={t('browse.clear_history')} subtitle={t('browse.clear_history_detail')} onClick={() => void clearHistoryWithUndo()} />
            </div>
        </>
    )
}

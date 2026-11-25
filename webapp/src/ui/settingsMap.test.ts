// WB20: Settings' map on the web over the real rules (core's SettingsMap and SettingsSearch, built
// by `:core:webRules`): every row the web draws is in the apps' map or found by the web's own match,
// every one is reachable by its own title, a row the form does not have is not searched, and an old
// address lands on the page that took its place.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import * as core from '../rules/core/tmplayer-rules.mjs'
import type { Rules } from '../rules'
import { MOVED, OLD_PAGES, coreForm, mergeHits, ownHits, pageFor, PAGE_IDS, PAGE_TITLES, QUERY_MAX, ROWS, rowFor, shownRows, words, type RowContext } from './settingsMap.ts'

const json = readFileSync(new URL('../../../core/src/commonMain/resources/i18n/en.json', import.meta.url), 'utf8')
core.provideCatalog('en', json)
const r = core as unknown as Rules
const en = (() => {
    const flat: Record<string, string> = {}
    const walk = (o: Record<string, unknown>, prefix: string) => {
        for (const [k, v] of Object.entries(o)) {
            if (typeof v === 'string') flat[`${prefix}${k}`] = v
            else if (v && typeof v === 'object') walk(v as Record<string, unknown>, `${prefix}${k}.`)
        }
    }
    walk(JSON.parse(json), '')
    return flat
})()
const text = (key: string) => en[key] ?? ''

const everything = (form: RowContext['form']): RowContext => ({
    form,
    layoutFixed: false,
    msone: true,
    sync: true,
    syncFile: true,
    installable: true,
    storageEstimate: true,
})

test('the rows are unique, on a known page, with a title the catalog has', () => {
    const ids = new Set<string>()
    for (const row of ROWS) {
        assert.ok(!ids.has(row.id), `${row.id} twice`)
        ids.add(row.id)
        if (row.page !== null) assert.ok((PAGE_IDS as readonly string[]).includes(row.page), row.id)
        assert.ok(text(row.title), `${row.id}: ${row.title}`)
        for (const d of row.details ?? []) assert.ok(text(d), `${row.id}: ${d}`)
    }
    for (const id of PAGE_IDS) assert.ok(text(PAGE_TITLES[id]), id)
})

test('every row the apps have is in their map for the form that draws it', () => {
    for (const form of ['phone', 'desktop'] as const) {
        const known = new Set(r.settingsRows(coreForm(form)).map((x) => x.id))
        for (const row of shownRows(everything(form)).filter((x) => x.core)) assert.ok(known.has(row.id), `${row.id} on ${form}`)
    }
})

test('every row is reachable: its own title finds it, on a phone and on a desktop', () => {
    for (const form of ['phone', 'desktop'] as const) {
        const shown = shownRows(everything(form))
        const only = shown.filter((x) => x.core).map((x) => x.id)
        for (const row of shown) {
            const query = text(row.title)
            const found = [...(row.core ? r.settingsSearch(query, coreForm(form), only) : []), ...ownHits(query, shown, text)]
            assert.ok(found.includes(row.id), `${row.id} (${query}) on ${form}`)
        }
    }
})

test('the apps rows are searched only among the ones this page draws', () => {
    const phone = shownRows({ ...everything('phone'), msone: false })
    assert.ok(!phone.some((x) => x.id === 'msone'))
    assert.ok(!phone.some((x) => x.id === 'layout'), 'a phone has no Layout')
    const only = phone.filter((x) => x.core).map((x) => x.id)
    // The map has subtitle rows (the SubDL key) the web does not draw: they never come back.
    assert.deepEqual(r.settingsSearch('subdl', 'phone', only), [])
    assert.ok(r.settingsSearch('subdl', 'phone', null).length > 0)
})

test('a form takes the rows it has: layout not on a phone or a fixed layout, nothing on a TV', () => {
    assert.ok(shownRows(everything('desktop')).some((x) => x.id === 'layout'))
    assert.ok(!shownRows({ ...everything('desktop'), layoutFixed: true }).some((x) => x.id === 'layout'))
    assert.deepEqual(shownRows(everything('tv')), [])
    const none = { ...everything('desktop'), sync: false, syncFile: false, installable: false, storageEstimate: false }
    const ids = shownRows(none).map((x) => x.id)
    for (const gone of ['resume_sync_delete', 'install', 'clear_cache']) assert.ok(!ids.includes(gone), gone)
    assert.ok(shownRows({ ...none, syncFile: true }).some((x) => x.id === 'resume_sync_delete'))
})

test('a search that matches nothing is empty, and a blank or hostile one is too', () => {
    const shown = shownRows(everything('desktop'))
    const only = shown.filter((x) => x.core).map((x) => x.id)
    for (const q of ['zzzzqqq', '   ', '', '\u0000‮', '😀😀😀', 'a'.repeat(5000), '"; drop table', '((((']) {
        assert.deepEqual(r.settingsSearch(q, 'desktop', only), [], JSON.stringify(q.slice(0, 20)))
        assert.deepEqual(ownHits(q, shown, text), [], JSON.stringify(q.slice(0, 20)))
    }
    assert.ok(words('x'.repeat(5000)).length <= 1)
    // Only the first QUERY_MAX characters are read, so a long query costs what a short one does.
    assert.deepEqual(ownHits('cache ' + 'q'.repeat(QUERY_MAX), shown, text), [])
})

test('accents and capitals are folded, and a word needs only its start', () => {
    assert.deepEqual(words('Éléphant  Über-Größe'), ['elephant', 'uber', 'große'])
    const shown = shownRows(everything('desktop'))
    assert.ok(ownHits('CACHE', shown, text).includes('clear_cache'))
    assert.ok(ownHits('inst', shown, text).includes('install'))
    assert.ok(r.settingsSearch('SIGN OUT', 'phone', ['sign_out']).includes('sign_out'))
    assert.ok(r.settingsSearch('log out', 'phone', ['sign_out']).includes('sign_out'), 'the words people type for it')
})

test('hits merge in the rules order, apps first, and drop what is not drawn', () => {
    const shown = shownRows(everything('phone'))
    const hits = mergeHits(['posters', 'layout', 'nope', 'posters'], ['clear_cache', 'clear_cache', 'theme'], shown)
    assert.deepEqual(hits.map((x) => x.id), ['posters', 'clear_cache', 'theme'])
    assert.deepEqual(mergeHits([], [], shown), [])
})

test('old page addresses land on the page that took their place', () => {
    assert.equal(pageFor('library'), 'home')
    assert.equal(pageFor('home'), 'home')
    assert.equal(pageFor('storage'), 'storage')
    for (const bad of [undefined, '', 'nope', '__proto__', 'constructor', 'toString', 'LIBRARY']) assert.equal(pageFor(bad), null, String(bad))
})

test('an anchor names a row, and nothing else', () => {
    assert.equal(rowFor('posters')?.page, 'home')
    assert.equal(rowFor('sign_out')?.page, null)
    for (const bad of ['', 'nope', '__proto__', 'constructor']) assert.equal(rowFor(bad), undefined, bad)
})

test('what is new is the apps sheet: a version, an address and lines', () => {
    const n = r.whatsNew()
    assert.match(n.version, /^\d+\.\d+\.\d+$/)
    assert.match(n.changelog, /^https:\/\//)
    assert.ok(n.highlights.length >= 3 && n.highlights.every((h) => h.trim() && !h.startsWith('whatsnew.')))
    assert.ok(n.sections.length >= 2 && n.sections.every((x) => x.title.trim() && x.lines.length > 0))
    assert.equal(n.sections.reduce((sum, x) => sum + x.lines.length, 0), n.highlights.length)
})

// WP-78: the order and the names of core's SettingsGroup.
test('the pages are in the apps\' order and have the apps\' names', () => {
    assert.deepEqual([...PAGE_IDS], ['account', 'playback', 'subtitles', 'storage', 'appearance', 'home', 'help', 'about'])
    const core = readFileSync(new URL('../../../core/src/pureMain/kotlin/com/tmplayer/data/SettingsMap.kt', import.meta.url), 'utf8')
    const titles = [...core.matchAll(/^    \w+\("([a-z_.]+)", "[a-z_.]+"(?:, \w+)?\),?;?$/gm)].map((m) => m[1]!)
    // The groups core lists (a sub page and Notifications have no web page); the web's pages follow in the same order.
    const web = PAGE_IDS.map((id) => PAGE_TITLES[id])
    const listed = titles.filter((k) => web.includes(k))
    assert.deepEqual(listed, web)
})

test('one home for every row: no id twice, and a page the web lacks (Privacy and lock) leads to the one that took its rows', () => {
    const ids = ROWS.map((r) => r.id)
    assert.equal(new Set(ids).size, ids.length)
    assert.equal(pageFor('privacy'), 'account')
    assert.equal(OLD_PAGES.privacy, 'account')
    assert.equal(pageFor('library'), 'home')
    assert.equal(rowFor('resume_sync')?.page, 'account')
    // The one name: the row is titled by the string the apps title theirs and the hint names (core SyncHintNameTest).
    assert.equal(rowFor('resume_sync')?.title, 'syncplace.switch')
})

test('"Moved to" lines are for rows that exist, on the page they left, pointing at their new page', () => {
    for (const m of MOVED) {
        const row = rowFor(m.id)
        assert.ok(row, m.id)
        assert.equal(row.page, m.to, `${m.id} lives on ${m.to}`)
        assert.notEqual(m.from, m.to)
        assert.ok((PAGE_IDS as readonly string[]).includes(m.from))
    }
    assert.ok(text('settings.also_in_moved').includes('{page}'))
})

test('Language is the first row of Appearance and language, as in the apps', () => {
    assert.equal(ROWS.find((r) => r.page === 'appearance')?.id, 'language')
})

test('Sign out, Back up and Language are found by their words', () => {
    const context = everything('desktop')
    const rows = shownRows(context)
    for (const [query, id] of [['sign out', 'sign_out'], ['back up', 'back_up'], ['language', 'language'], ['sync', 'resume_sync']] as const) {
        assert.ok(ownHits(query, rows, text).includes(id) || r.settingsSearch(query, coreForm('desktop'), null).includes(id), `${query} finds ${id}`)
    }
})

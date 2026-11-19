// WB12: the page's languages against core's (WebRules languageList and languageResolve, built by
// `pnpm rules`), the catalog's ICU subset, and a downloaded catalog read as untrusted: key by key
// over English, garbage never thrown past the loader, every shipped catalog readable.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import * as core from '../rules/core/tmplayer-rules.mjs'
import { PAGE_STRINGS } from '../../strings.config.ts'
import { argumentNames, findLanguage, format, formatsFor, isRtl, LANGUAGES, loadCatalog, matchLanguage, readCatalog, resolveLanguage } from './language.ts'

const catalogFile = (tag: string) => new URL(`../../../core/src/commonMain/resources/i18n/${tag}.json`, import.meta.url)
const en = formatsFor('en')

/** The page's English dictionary as src/i18n.ts flattens it: `section.key` lines (sections themselves are left out here). */
function pageEnglish(): Record<string, string> {
    const all = JSON.parse(readFileSync(catalogFile('en'), 'utf8')) as Record<string, Record<string, string>>
    const out: Record<string, string> = {}
    for (const entry of PAGE_STRINGS) {
        const [section, key] = entry.split('.')
        for (const [k, v] of Object.entries(all[section])) if (!key || k === key) out[`${section}.${k}`] = v
    }
    return out
}

test('the list is core’s: the same tags, names and directions in the same order', () => {
    const theirs = core.languageList().map((l) => ({ tag: l.tag, name: l.name, rtl: l.rtl }))
    assert.deepEqual(LANGUAGES.map((l) => ({ ...l })), theirs)
    assert.equal(LANGUAGES.length, 22)
    assert.deepEqual(LANGUAGES.filter((l) => l.rtl).map((l) => l.tag), ['ar', 'fa'])
})

test('every shipped language has a catalog file', () => {
    for (const l of LANGUAGES) assert.ok(readFileSync(catalogFile(l.tag), 'utf8').length > 1000, l.tag)
})

test('the browser’s languages resolve as core resolves them', () => {
    const tags = [
        '', ' ', 'en', 'en-US', 'en-GB', 'EN', 'de', 'de-AT', 'de_CH', 'fr-CA', 'es', 'es-ES', 'es-MX', 'es-419', 'pt', 'pt-PT', 'pt-BR', 'pt_br',
        'zh', 'zh-CN', 'zh-SG', 'zh-MY', 'zh-TW', 'zh-HK', 'zh-MO', 'zh-US', 'zh-Hans', 'zh-Hant', 'zh-Hans-TW', 'zh-Hant-CN', 'zh-hant-hk', 'zh_TW',
        'in', 'in-ID', 'id', 'iw', 'he-IL', 'ji', 'ar', 'ar-EG', 'fa-IR', 'ml-IN', 'bn-BD', 'hi', 'ja-JP', 'ko-KR', 'vi-VN', 'tr', 'uk-UA', 'ru',
        'cs-CZ', 'pl', 'it-IT', 'nl', 'sv-SE', 'x', '-', '--de', 'de--AT', '419', 'en-XA', 'und', 'sr-Latn-RS', 'zh-419', 'zh-001',
    ]
    for (const tag of tags) {
        assert.equal(resolveLanguage('', [tag]), core.languageResolve('', [tag]), `system ${JSON.stringify(tag)}`)
        assert.equal(matchLanguage(tag) ?? 'en', core.languageResolve('', [tag]), `match ${JSON.stringify(tag)}`)
    }
    const lists = [[], ['nl', 'de'], ['nl', 'sv'], ['zh-US', 'ja'], ['en-US', 'de'], ['de-DE', 'en']]
    for (const saved of [null, '', 'de', 'DE', 'zh-tw', 'xx', ' ', 'en-XA', 'ar']) {
        for (const list of lists) assert.equal(resolveLanguage(saved, list), core.languageResolve(saved, list), `${saved} over ${list}`)
    }
})

test('a stored choice wins over the browser, and anything but a shipped tag is no choice', () => {
    assert.equal(resolveLanguage('ja', ['de-DE', 'en']), 'ja')
    assert.equal(resolveLanguage('klingon', ['de-DE']), 'de')
    assert.equal(resolveLanguage(null, ['nl', 'fr-BE']), 'fr')
    assert.equal(resolveLanguage(undefined, [42, null, {}, 'ar-SA']), 'ar')
    assert.equal(resolveLanguage('', []), 'en')
    assert.equal(findLanguage('PT-br')?.tag, 'pt-BR')
    assert.equal(findLanguage(null), undefined)
    assert.ok(isRtl('fa') && isRtl('AR') && !isRtl('de') && !isRtl('xx'))
})

test('the ICU subset: arguments, plurals per language, select, nesting', () => {
    assert.equal(format('Now in {language}', { language: 'Deutsch' }, en), 'Now in Deutsch')
    assert.equal(format('{count, plural, =0 {None} one {# video} other {# videos}}', { count: 0 }, en), 'None')
    assert.equal(format('{count, plural, one {# video} other {# videos}}', { count: 1 }, en), '1 video')
    assert.equal(format('{count, plural, one {# video} other {# videos}}', { count: 1234 }, en), '1,234 videos')
    assert.equal(format('{count, plural, one {# Video} other {# Videos}}', { count: 1234 }, formatsFor('de')), '1.234 Videos')
    // Polish has a "few" form; Arabic writes its own digits.
    const pl = formatsFor('pl')
    assert.equal(format('{n, plural, one {# plik} few {# pliki} many {# plików} other {# pliku}}', { n: 3 }, pl), '3 pliki')
    assert.equal(format('{n, plural, one {# plik} few {# pliki} many {# plików} other {# pliku}}', { n: 5 }, pl), '5 plików')
    assert.equal(format('{n, plural, other {# x}}', { n: 3 }, formatsFor('ar')), `${new Intl.NumberFormat('ar').format(3)} x`)
    assert.equal(format('{form, select, tv {TV {n}} other {Phone}}', { form: 'tv', n: 2 }, en), 'TV 2')
    assert.equal(format('{form, select, tv {TV} other {Phone}}', { form: 'web' }, en), 'Phone')
    assert.equal(format('Missing {name}', {}, en), 'Missing {name}')
})

test('text the ICU subset cannot read is shown as it is, never thrown', () => {
    for (const text of ['{', '}', '{}', '{ }', '{,}', '{#}', '{a, plural,', '{a, plural, one {', '{{{', '}}}{', 'a {b {c}} d', '{n, plural, one}', '{n, select, {x}}']) {
        assert.equal(typeof format(text, { a: 1, n: 1 }, en), 'string', text)
    }
    assert.equal(format('{ }', {}, en), '{ }')
    assert.equal(format('{__proto__}', {}, en), '{__proto__}')
    assert.equal(format('{toString}', {}, en), '{toString}')
})

test('argument names, and null for a group that never closes or names nothing', () => {
    assert.deepEqual([...argumentNames('Now in {language}')!], ['language'])
    assert.deepEqual([...argumentNames('{count, plural, one {# in {chat}} other {# in {chat}, {more}}}')!].sort(), ['chat', 'count', 'more'])
    assert.deepEqual([...argumentNames('No braces at all')!], [])
    assert.equal(argumentNames('Now in {language'), null)
    assert.equal(argumentNames('Now in { }'), null)
    assert.equal(argumentNames('{n, plural, one {# {x}'), null)
})

test('a catalog is laid over English key by key; whatever is wrong with a line keeps English', () => {
    const english = { 'a.one': 'One', 'a.two': 'Two {n}', 'a.three': 'Three', 'b.x': 'X {count, plural, one {# video} other {# videos}}', a: { one: 'One' } }
    const { dict, translated } = readCatalog(
        {
            a: { one: 'Eins', two: 'Zwei {m}', three: 7, extra: 'Extra', four: '' },
            b: { x: 'X {count, plural, one {# Video} other {# Videos}}' },
            c: 'not a section',
            d: [1, 2],
        },
        english,
    )
    assert.equal(dict['a.one'], 'Eins')
    assert.equal(dict['a.two'], 'Two {n}', 'a line naming an argument English does not pass stays English')
    assert.equal(dict['a.three'], 'Three', 'a number stays English')
    assert.equal(dict['b.x'], 'X {count, plural, one {# Video} other {# Videos}}')
    assert.equal((dict as Record<string, unknown>)['a.extra'], undefined, 'a key English lacks is dropped')
    assert.deepEqual(dict.a, { one: 'One' })
    assert.equal(translated, 2)
    // English itself is never changed.
    assert.equal(english['a.one'], 'One')
})

test('lines too long, cut short or poisoned stay English', () => {
    const english = { 'a.one': 'One', 'a.two': 'Two {n}' }
    const { dict } = readCatalog({ a: { one: 'x'.repeat(10_000), two: 'Zwei {n' }, __proto__: { one: 'P' } }, english)
    assert.deepEqual(dict, english)
    const poisoned = readCatalog(JSON.parse('{"__proto__": {"one": "P"}, "a": {"__proto__": "Q", "constructor": "R"}}'), english)
    assert.deepEqual(poisoned.dict, english)
    assert.equal(({} as Record<string, unknown>).one, undefined)
})

test('a file that is not a catalog throws, for the loader to count as failed', () => {
    for (const raw of [null, undefined, 42, 'text', [], [{}], true]) assert.throws(() => readCatalog(raw, { 'a.b': 'c' }), String(raw))
    assert.deepEqual(readCatalog({}, { 'a.b': 'c' }).dict, { 'a.b': 'c' })
})

test('random garbage never throws past readCatalog and keeps every English line', () => {
    let seed = 12345
    const rand = () => ((seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31)
    const chars = '{}#,= abcplural select one other\u0000‮"'
    const word = () => Array.from({ length: Math.floor(rand() * 20) }, () => chars[Math.floor(rand() * chars.length)]).join('')
    const english = pageEnglish()
    const keys = Object.keys(english)
    for (let round = 0; round < 200; round++) {
        const raw: Record<string, Record<string, unknown>> = {}
        for (let i = 0; i < 30; i++) {
            const [section, key] = keys[Math.floor(rand() * keys.length)].split('.')
            raw[section] ??= {}
            raw[section][key] = rand() < 0.2 ? Math.floor(rand() * 9) : word()
        }
        const { dict } = readCatalog(raw, english)
        for (const k of keys) {
            assert.equal(typeof dict[k], 'string')
            assert.equal(typeof format(dict[k], { count: 2, n: 1, language: 'x' }, en), 'string')
        }
    }
})

test('every shipped catalog reads, translates most of the page, and keeps English where it lacks a line', () => {
    const english = pageEnglish()
    const total = Object.keys(english).length
    for (const l of LANGUAGES) {
        if (l.tag === 'en') continue
        const raw = JSON.parse(readFileSync(catalogFile(l.tag), 'utf8'))
        const { dict, translated } = readCatalog(raw, english)
        assert.ok(translated > total * 0.8, `${l.tag}: ${translated} of ${total}`)
        for (const k of Object.keys(english)) assert.equal(typeof dict[k], 'string', `${l.tag} ${k}`)
    }
})

/** A fetch stand in: [answer] for every call, after [delayMs]. */
function fakeFetch(answer: { ok?: boolean; status?: number; body?: string } | Error, delayMs = 0) {
    const calls: string[] = []
    const fetcher = (url: string, init: { signal: AbortSignal }) => {
        calls.push(url)
        return new Promise<{ ok: boolean; status: number; text: () => Promise<string> }>((resolve, reject) => {
            const timer = setTimeout(() => (answer instanceof Error ? reject(answer) : resolve({ ok: answer.ok ?? true, status: answer.status ?? 200, text: async () => answer.body ?? '' })), delayMs)
            init.signal.addEventListener('abort', () => {
                clearTimeout(timer)
                reject(new Error('aborted'))
            })
        })
    }
    return { fetcher, calls }
}

test('the loader: a good file is read over English, anything else throws so the page stays as it was', async () => {
    const english = { 'a.one': 'One', 'a.two': 'Two' }
    const good = fakeFetch({ body: JSON.stringify({ a: { one: 'Eins' } }) })
    assert.deepEqual(await loadCatalog('/assets/strings-de-1.json', english, good.fetcher, 1000), { 'a.one': 'Eins', 'a.two': 'Two' })
    assert.deepEqual(good.calls, ['/assets/strings-de-1.json'])
    await assert.rejects(loadCatalog(undefined, english, good.fetcher, 1000), /no catalog/)
    await assert.rejects(loadCatalog('/x', english, fakeFetch({ ok: false, status: 404 }).fetcher, 1000), /404/)
    await assert.rejects(loadCatalog('/x', english, fakeFetch({ body: '{"a": {"one": "Ei' }).fetcher, 1000), /not JSON/)
    await assert.rejects(loadCatalog('/x', english, fakeFetch({ body: '[1, 2]' }).fetcher, 1000), /not an object/)
    await assert.rejects(loadCatalog('/x', english, fakeFetch({ body: '' }).fetcher, 1000), /not JSON/)
    await assert.rejects(loadCatalog('/x', english, fakeFetch(new TypeError('Failed to fetch')).fetcher, 1000), /Failed to fetch/)
    await assert.rejects(loadCatalog('/x', english, fakeFetch({ body: '{}' }, 500).fetcher, 20), /timed out|aborted/)
})

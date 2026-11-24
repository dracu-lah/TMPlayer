import assert from 'node:assert/strict'
import { test } from 'node:test'
import { BACKUP_FORMAT, BACKUP_VERSION, MAX_BACKUP_BYTES, accountTagOf, backupFileName, backupText, decodeBackup, otherAccount } from './backup.ts'
import { emptyDoc, tdlibMessageId } from './format.ts'
import { emptyLists } from '../ui/lists.ts'

const OUT = { now: Date.UTC(2026, 10, 24, 12), appVersion: 'web', accountTag: '0123456789abcdef:1a2b' }

function held() {
    const doc = emptyDoc(42)
    doc.resume.push({ chat: -100, msg: tdlibMessageId(7), file: 1, title: 'Lighthouse', chatTitle: '', size: 5, durationSec: 60, positionMs: 12_000, durationMs: 60_000, updatedAt: 1000, state: 'a,b,c,d,1' })
    doc.watched.push({ chat: -100, msg: tdlibMessageId(8), file: 0, title: 'Done', chatTitle: '', size: 5, durationSec: 60, watchedAt: 2000, manual: true })
    doc.later.push({ chat: -100, msg: tdlibMessageId(9), file: 0, title: 'Later', fileName: '', chatTitle: '', size: 5, durationSec: 60, mime: '', addedAt: 3000, series: '' })
    doc.favourites.set(-100, 1)
    doc.bookmarks.push({ chat: -100, msg: tdlibMessageId(7), id: 5, positionMs: 3000, note: 'here', at: 5 })
    return doc
}

test('a file this module writes reads back with every list', () => {
    const text = backupText({ doc: held(), lists: emptyLists(), recentSearches: ['harbour'] }, OUT)
    const read = decodeBackup(text)
    assert.equal(read.kind, 'ok')
    if (read.kind !== 'ok') return
    assert.equal(read.doc.resume[0]!.positionMs, 12_000)
    assert.equal(read.doc.resume[0]!.state, 'a,b,c,d,1')
    assert.equal(read.doc.watched[0]!.manual, true)
    assert.equal(read.doc.later[0]!.addedAt, 3000)
    assert.deepEqual([...read.doc.favourites.keys()], [-100])
    assert.equal(read.doc.bookmarks[0]!.note, 'here')
    assert.deepEqual(read.searches, ['harbour'])
    assert.equal(read.accountTag, '0123456789abcdef:1a2b')
    assert.equal(read.account, null)
})

test('the file says the apps own names, a list-less file is version 1, one with lists version 2', () => {
    const plain = JSON.parse(backupText({ doc: held(), lists: emptyLists() }, OUT))
    assert.equal(plain.format, BACKUP_FORMAT)
    assert.equal(plain.version, 1)
    assert.deepEqual(Object.keys(plain.resume[0]).sort(), ['chat', 'chatTitle', 'durationMs', 'durationSec', 'file', 'message', 'positionMs', 'size', 'state', 'title', 'updatedAt'])
    assert.deepEqual(plain.favourites, [-100])
    assert.deepEqual(plain.settings, {})
    const withList = JSON.parse(backupText({ doc: held(), lists: { lists: [{ id: 'abcd12', name: 'Night', at: 1, home: true }], lines: [], removed: [] } }, OUT))
    assert.equal(withList.version, BACKUP_VERSION)
    assert.equal(withList.lists.lists[0].name, 'Night')
})

test('a backup the apps wrote reads: their settings are left alone, their records are read', () => {
    const apps = JSON.stringify({
        format: 'tmplayer-backup',
        version: 2,
        createdAt: 1_700_000_000_000,
        app: '1.9.0',
        settings: { accent: { text: 'red' }, remember_searches: { bool: true } },
        metadata: { enabled: true },
        favourites: [-1001, 5],
        recentSearches: ['a', 'b'],
        memory: { x: { long: 1 } },
        resume: [{ chat: -1001, message: tdlibMessageId(3), file: 9, title: 'Ep', chatTitle: 'C', size: 1, durationSec: 5, positionMs: 1000, durationMs: 5000, updatedAt: 9 }],
        watched: [{ chat: -1001, message: tdlibMessageId(4), file: 9, title: 'Ep', chatTitle: 'C', size: 1, durationSec: 5, watchedAt: 8, manual: false }],
        watchLater: [],
        bookmarks: [],
        lists: { lists: [{ id: 'abcd12', name: 'Night', at: 1, home: true }], entries: [] },
    })
    const read = decodeBackup(apps)
    assert.equal(read.kind, 'ok')
    if (read.kind !== 'ok') return
    assert.equal(read.doc.resume.length, 1)
    assert.equal(read.doc.watched.length, 1)
    assert.deepEqual([...read.doc.favourites.keys()].sort(), [-1001, 5])
    assert.equal(read.doc.favourites.get(5), 1_700_000_000_000)
    assert.equal(read.account, null)
    assert.notEqual(read.lists, undefined)
})

test('not a backup: other JSON, other formats, text, nothing', () => {
    for (const bad of ['', 'hello', '[]', '{}', 'null', '{"format":"tmplayer-sync","version":1}', '{"format":"tmplayer-backup"}', '{"format":"tmplayer-backup","version":0}', '{"format":"tmplayer-backup","version":"2"}']) {
        assert.equal(decodeBackup(bad).kind, 'notBackup', bad)
    }
})

test('a backup from a newer TMPlayer is refused with its version', () => {
    const r = decodeBackup(JSON.stringify({ format: BACKUP_FORMAT, version: 9 }))
    assert.deepEqual(r, { kind: 'tooNew', version: 9 })
})

test('a file too large is not read', () => {
    assert.equal(decodeBackup('x'.repeat(MAX_BACKUP_BYTES + 1)).kind, 'tooBig')
})

test('truncated and wrong typed files never throw: bad records are skipped on their own', () => {
    const good = backupText({ doc: held(), lists: emptyLists() }, OUT)
    for (let cut = 0; cut < good.length; cut += 37) assert.doesNotThrow(() => decodeBackup(good.slice(0, cut)))
    const odd = decodeBackup(
        JSON.stringify({
            format: BACKUP_FORMAT,
            version: 1,
            createdAt: 'now',
            app: 7,
            favourites: 'many',
            resume: [null, 5, 'x', { chat: 'a' }, { chat: 1, message: 2, file: 0, positionMs: 1 }, { chat: 1, message: tdlibMessageId(2), file: 3, positionMs: -5 }, { chat: 1, message: tdlibMessageId(2), file: 3, positionMs: 4000, title: 'ok' }],
            watched: { not: 'a list' },
            watchLater: [{ chat: 1 }],
            bookmarks: [{ chat: 1, message: 2, id: 0, positionMs: 1 }],
            recentSearches: [1, null, '  ', 'fine'],
            lists: [],
        }),
    )
    assert.equal(odd.kind, 'ok')
    if (odd.kind !== 'ok') return
    assert.equal(odd.doc.resume.length, 1)
    assert.equal(odd.doc.resume[0]!.title, 'ok')
    assert.equal(odd.doc.watched.length, 0)
    assert.equal(odd.doc.favourites.size, 0)
    assert.deepEqual(odd.searches, ['fine'])
    assert.equal(odd.lists, undefined)
})

test('huge arrays are capped, not walked', () => {
    const resume = Array.from({ length: 20_000 }, (_, i) => ({ chat: 1, message: tdlibMessageId(i + 1), file: 1, positionMs: 1000, title: 't' }))
    const read = decodeBackup(JSON.stringify({ format: BACKUP_FORMAT, version: 1, resume, favourites: Array.from({ length: 50_000 }, (_, i) => i + 1), recentSearches: Array.from({ length: 5_000 }, (_, i) => `s${i}`) }))
    assert.equal(read.kind, 'ok')
    if (read.kind !== 'ok') return
    assert.ok(read.doc.resume.length <= 400)
    assert.ok(read.doc.favourites.size <= 2_000)
    assert.ok(read.searches.length <= 50)
})

test('the file name is the date', () => {
    assert.match(backupFileName(Date.UTC(2026, 10, 24, 12)), /^tmplayer-backup-2026-11-2[345]\.json$/)
})

test('the file names the account by a salted 16 bit tag, never by its Telegram user id (SEC2-10)', async () => {
    const tag = await accountTagOf(424242)
    assert.match(tag, /^[0-9a-f]{16}:[0-9a-f]{4}$/)
    // A salt of its own per file: two files of one account do not match each other.
    assert.notEqual(tag, await accountTagOf(424242))
    const salt = tag.slice(0, 16)
    assert.equal(tag, await accountTagOf(424242, salt))
    assert.equal(await accountTagOf(0), '')
    const text = backupText({ doc: held(), lists: emptyLists() }, { ...OUT, accountTag: tag })
    assert.doesNotMatch(text, /424242/)
    assert.equal('account' in JSON.parse(text), false)
    const read = decodeBackup(text)
    assert.equal(read.kind, 'ok')
    if (read.kind !== 'ok') return
    assert.equal(await otherAccount(read, 424242), false)
    let warned = 0
    for (let id = 1; id <= 200; id++) if (await otherAccount(read, id)) warned++
    assert.ok(warned >= 198, `another account is warned (${warned} of 200)`)
    // Trying every id does not name the account: many others fit the same tag.
    let fit = 0
    for (let id = 1_000_000; id < 1_400_000 && fit < 2; id++) if ((await accountTagOf(id, salt)) === tag) fit++
    assert.equal(fit, 2)
    // A file from before the tag still warns by its user id; a file that names no account never does.
    const old = JSON.parse(text)
    delete old.accountTag
    old.account = 424242
    const oldRead = decodeBackup(JSON.stringify(old))
    assert.equal(oldRead.kind, 'ok')
    if (oldRead.kind !== 'ok') return
    assert.equal(oldRead.doc.resume.length, 1)
    assert.equal(await otherAccount(oldRead, 7), true)
    assert.equal(await otherAccount({ account: 424242, accountTag: null }, 7), true)
    assert.equal(await otherAccount({ account: 424242, accountTag: null }, 424242), false)
    assert.equal(await otherAccount({ account: null, accountTag: null }, 7), false)
    assert.equal(await otherAccount(read, 0), false)
    // A malformed tag is not read: the file restores as one that names no account.
    const bad = decodeBackup(text.replace(tag, 'zz'))
    assert.equal(bad.kind === 'ok' && bad.accountTag, null)
})

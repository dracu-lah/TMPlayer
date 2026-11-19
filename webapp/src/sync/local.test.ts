// This device's lists as a sync file and a merged file written back into them: the web's ids and
// units against the apps', removals, picks joined to the apps' other lines of a correction, and a
// round trip between the web and a file core wrote.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { apply, decode, emptyDoc, encode, keyOf, mergeCounting, tdlibMessageId, type SyncDoc } from './format.ts'
import { applyLocal, collect, nextBase, noBase, withLines, withPick, withoutPick, type Lists, type Writer } from './local.ts'

const CHAT = -1001234567890
const NOW = 1_800_000_000_000
const lists = (over: Partial<Lists> = {}): Lists => ({ resumes: [], watched: [], later: [], stars: [], picks: [], tombs: [], ...over })
const coreFile = async (): Promise<SyncDoc> => {
    const r = await decode(new Uint8Array(readFileSync(new URL('./fixtures/core-v3.json.gz', import.meta.url))), 777)
    assert.equal(r.kind, 'ok')
    return (r as { doc: SyncDoc }).doc
}

function recorder() {
    const log: string[] = []
    const writer: Writer = {
        putResume: (r) => log.push(`resume ${r.chatId}:${r.messageId} ${r.position}/${r.length} @${r.time}`),
        dropResume: (c, m) => log.push(`unresume ${c}:${m}`),
        putWatched: (c, m, t) => void log.push(`watched ${c}:${m} @${t}`),
        dropWatched: (c, m) => log.push(`unwatched ${c}:${m}`),
        video: async (c, m) => (m === 404 ? null : { chatId: c, messageId: m }),
        putLater: (i, t) => log.push(`later ${i.chatId}:${i.messageId} @${t}`),
        dropLater: (c, m) => log.push(`unlater ${c}:${m}`),
        putStar: (c) => log.push(`star ${c}`),
        dropStar: (c) => log.push(`unstar ${c}`),
        putPick: (c, m, p, at) => log.push(`pick ${c}:${m} ${p ? `${p.kind}:${p.id}` : 'none'} @${at}`),
        putTitle: (c, m, title, at) => log.push(`title ${c}:${m} ${title ?? 'none'} @${at}`),
    }
    return { log, writer }
}

test('a place is written in the apps\' ids and units, its title kept from the file, else a placeholder', () => {
    const remote = emptyDoc(1)
    remote.resume.push({ chat: CHAT, msg: tdlibMessageId(5), file: 9, title: 'Night Lantern', chatTitle: '', size: 0, durationSec: 0, positionMs: 1, durationMs: 0, updatedAt: 1, state: '1,ja,0,en,1.25' })
    const doc = collect(lists({ resumes: [{ chatId: CHAT, messageId: 5, position: 754.4, length: 5400, time: NOW - 1 }, { chatId: 7, messageId: 8, position: 90, length: 0, time: NOW - 2 }, { chatId: 7, messageId: 9, position: 0, length: 100, time: NOW }] }), noBase(), remote, 1, NOW, 'web')
    assert.deepEqual(doc.resume.map((r) => [r.chat, r.msg, r.title, r.positionMs, r.durationMs, r.durationSec, r.updatedAt, r.file > 0, r.state ?? null]), [
        [CHAT, 5 * 2 ** 20, 'Night Lantern', 754_400, 5_400_000, 5_400, NOW - 1, true, '1,ja,0,en,1.25'],
        [7, 8 * 2 ** 20, 'Video', 90_000, 0, 0, NOW - 2, true, null],
    ])
})

test('a place taken off since the last sync is a removal; a favourite starred since is stamped now, one held then keeps the last sync\'s time', () => {
    const base = { resumeKeys: [keyOf(1, tdlibMessageId(2)), keyOf(1, tdlibMessageId(3))], favourites: [10, 11], at: NOW - 5_000 }
    const doc = collect(lists({ resumes: [{ chatId: 1, messageId: 3, position: 60, length: 100, time: NOW - 1 }], stars: [{ chatId: 10, time: 5 }, { chatId: 12, time: 6 }] }), base, emptyDoc(1), 1, NOW, 'web')
    assert.deepEqual(doc.removed.map((r) => `${r.list}:${r.key}@${r.at}`).sort(), [`favourite:11@${NOW}`, `resume:${keyOf(1, tdlibMessageId(2))}@${NOW}`].sort())
    assert.equal(doc.favourites.get(10), NOW - 5_000)
    assert.equal(doc.favourites.get(12), NOW)
    // A favourite the file already has keeps the file's time.
    const remote = emptyDoc(1)
    remote.favourites.set(12, 77)
    assert.equal(collect(lists({ stars: [{ chatId: 12, time: 6 }] }), noBase(), remote, 1, NOW, 'web').favourites.get(12), 77)
})

test('what the viewer took off is a removal at the time they did; one older than 90 days or with a bad key is dropped', () => {
    const doc = collect(
        lists({
            tombs: [
                { list: 'watched', key: keyOf(1, tdlibMessageId(2)), at: NOW - 1_000 },
                { list: 'later', key: keyOf(1, tdlibMessageId(3)), at: NOW - 2_000 },
                { list: 'watched', key: keyOf(1, tdlibMessageId(4)), at: NOW - 91 * 24 * 3_600_000 },
                { list: 'watched', key: 'garbage', at: NOW },
            ],
        }),
        noBase(), emptyDoc(1), 1, NOW, 'web',
    )
    assert.deepEqual(doc.removed.map((r) => `${r.list}@${NOW - r.at}`).sort(), ['later@2000', 'watched@1000'])
})

test('a poster pick keeps the rename and "not the same video" the apps put on that video; taking it off leaves them', () => {
    const remote = emptyDoc(1)
    remote.corrections.push({ chat: CHAT, msg: tdlibMessageId(6), text: 'separate\ntmdb=show:123\ntitle=Night Lantern (2022)', at: 5 })
    const picked = collect(lists({ picks: [{ chatId: CHAT, messageId: 6, text: 'tmdb=film:99', at: NOW }] }), noBase(), remote, 1, NOW, 'web')
    assert.deepEqual(picked.corrections.map((c) => [c.text, c.at]), [['separate\ntmdb=film:99\ntitle=Night Lantern (2022)', NOW]])
    const cleared = collect(lists({ tombs: [{ list: 'correction', key: keyOf(CHAT, tdlibMessageId(6)), at: NOW - 10 }] }), noBase(), remote, 1, NOW, 'web')
    assert.deepEqual(cleared.corrections.map((c) => [c.text, c.at]), [['separate\ntitle=Night Lantern (2022)', NOW - 10]])
    assert.deepEqual(cleared.removed, [])
    // With nothing else on the correction, taking the pick off is a removal.
    const bare = emptyDoc(1)
    bare.corrections.push({ chat: CHAT, msg: tdlibMessageId(6), text: 'tmdb=show:5', at: 5 })
    const gone = collect(lists({ tombs: [{ list: 'correction', key: keyOf(CHAT, tdlibMessageId(6)), at: NOW - 10 }] }), noBase(), bare, 1, NOW, 'web')
    assert.deepEqual(gone.removed.map((r) => r.list), ['correction'])
    assert.equal(withPick(undefined, 'tmdb=film:1'), 'tmdb=film:1')
    assert.equal(withPick('wrong', 'tmdb=film:1'), 'tmdb=film:1', 'a pick lifts a wrong match')
    assert.equal(withoutPick('wrong\ntmdb=film:1'), 'wrong')
})

test('a rename is the title line of the video\'s correction, joined to the pick and "not the same video" the file holds, cut to the file\'s 60', () => {
    const msg = tdlibMessageId(6)
    const key = keyOf(CHAT, msg)
    // Alone: a correction with the title line, at the time it was set.
    const alone = collect(lists({ titles: [{ chatId: CHAT, messageId: 6, title: '  Night Lantern (2022)  ', at: NOW - 5 }] }), noBase(), emptyDoc(1), 1, NOW, 'web')
    assert.deepEqual(alone.corrections.map((c) => [c.chat, c.msg, c.text, c.at]), [[CHAT, msg, 'title=Night Lantern (2022)', NOW - 5]])
    // Beside what the apps put on the video: the pick and the split stay, the title is the web's.
    const remote = emptyDoc(1)
    remote.corrections.push({ chat: CHAT, msg, text: 'separate\ntmdb=show:123\ntitle=Phone name', at: 5 })
    const joined = collect(lists({ titles: [{ chatId: CHAT, messageId: 6, title: 'Web name', at: NOW }] }), noBase(), remote, 1, NOW, 'web')
    assert.deepEqual(joined.corrections.map((c) => [c.text, c.at]), [['separate\ntmdb=show:123\ntitle=Web name', NOW]])
    // A pick and a rename together are one correction at the newer time.
    const both = collect(lists({ picks: [{ chatId: CHAT, messageId: 6, text: 'tmdb=film:9', at: NOW - 9 }], titles: [{ chatId: CHAT, messageId: 6, title: 'Web name', at: NOW - 3 }] }), noBase(), emptyDoc(1), 1, NOW, 'web')
    assert.deepEqual(both.corrections.map((c) => [c.text, c.at]), [['tmdb=film:9\ntitle=Web name', NOW - 3]])
    // The file carries 60 characters, never half an emoji; the page keeps its 120.
    const long = collect(lists({ titles: [{ chatId: CHAT, messageId: 6, title: `${'a'.repeat(59)}😀 tail`, at: NOW }] }), noBase(), emptyDoc(1), 1, NOW, 'web')
    assert.equal(long.corrections[0].text, `title=${'a'.repeat(59)}😀`)
    // Resetting the rename leaves the pick and the split; with nothing else it is a removal.
    const reset = collect(lists({ titleTombs: [{ key, at: NOW - 10 }] }), noBase(), remote, 1, NOW, 'web')
    assert.deepEqual(reset.corrections.map((c) => [c.text, c.at]), [['separate\ntmdb=show:123', NOW - 10]])
    const bare = emptyDoc(1)
    bare.corrections.push({ chat: CHAT, msg, text: 'title=Phone name', at: 5 })
    const gone = collect(lists({ titleTombs: [{ key, at: NOW - 10 }] }), noBase(), bare, 1, NOW, 'web')
    assert.deepEqual(gone.removed.map((r) => [r.list, r.key]), [['correction', key]])
    assert.deepEqual(gone.corrections, [])
    // A reset older than 90 days or with a bad key says nothing; a pick taken off leaves the rename.
    const stale = collect(lists({ titleTombs: [{ key, at: NOW - 91 * 24 * 3_600_000 }, { key: 'garbage', at: NOW }] }), noBase(), remote, 1, NOW, 'web')
    assert.deepEqual([stale.corrections, stale.removed], [[], []])
    const unpicked = collect(lists({ tombs: [{ list: 'correction', key, at: NOW - 10 }], titles: [{ chatId: CHAT, messageId: 6, title: 'Web name', at: NOW - 20 }] }), noBase(), remote, 1, NOW, 'web')
    assert.deepEqual(unpicked.corrections.map((c) => c.text), ['separate\ntitle=Web name'])
    assert.equal(withLines('separate\ntitle=A', { title: null }), 'separate')
    // A line this web does not know (a newer app's) is kept verbatim beside a pick or a rename, and never makes a correction empty.
    const newer = emptyDoc(1)
    newer.corrections.push({ chat: CHAT, msg, text: 'separate\nyear=2019\ntitle=Phone name\nfuture-flag', at: 5 })
    const kept = collect(lists({ titles: [{ chatId: CHAT, messageId: 6, title: 'Web name', at: NOW }] }), noBase(), newer, 1, NOW, 'web')
    assert.deepEqual(kept.corrections.map((c) => c.text), ['separate\ntitle=Web name\nyear=2019\nfuture-flag'])
    const picked2 = collect(lists({ picks: [{ chatId: CHAT, messageId: 6, text: 'tmdb=film:9', at: NOW }] }), noBase(), newer, 1, NOW, 'web')
    assert.deepEqual(picked2.corrections.map((c) => c.text), ['separate\ntmdb=film:9\ntitle=Phone name\nyear=2019\nfuture-flag'])
    const onlyUnknown = emptyDoc(1)
    onlyUnknown.corrections.push({ chat: CHAT, msg, text: 'year=2019\ntitle=Phone name', at: 5 })
    const left = collect(lists({ titleTombs: [{ key, at: NOW - 10 }] }), noBase(), onlyUnknown, 1, NOW, 'web')
    assert.deepEqual(left.corrections.map((c) => c.text), ['year=2019'])
    assert.deepEqual(left.removed, [], 'unknown lines left: no removal of the whole correction')
    // A hostile pile of unknown lines is carried only so far.
    const pile = emptyDoc(1)
    pile.corrections.push({ chat: CHAT, msg, text: `separate\n${Array.from({ length: 50 }, (_, i) => `x${i}=${'y'.repeat(500)}`).join('\n')}`, at: 5 })
    const bounded = collect(lists({ titles: [{ chatId: CHAT, messageId: 6, title: 'T', at: NOW }] }), noBase(), pile, 1, NOW, 'web')
    assert.ok(bounded.corrections[0].text.length < 1_000)
    assert.equal(withLines('tmdb=film:1\ntitle=A', { title: undefined }), 'tmdb=film:1\ntitle=A')
})

test('a rename comes in from the apps\' file, a reset or a removal there takes it off, and the newer side wins both ways', async () => {
    const remote = await coreFile()
    const now = remote.updatedAt + 1_000
    // The core fixture carries "Night Lantern (2022)" as a rename on video 6.
    const local = collect(lists(), noBase(), remote, 777, now, 'web')
    const a = apply(local, mergeCounting(remote, local, now).doc)
    const { log, writer } = recorder()
    await applyLocal(a, writer)
    assert.deepEqual(log.filter((l) => l.startsWith('title')), [`title ${CHAT}:6 Night Lantern (2022) @${remote.corrections[0].at}`])
    // The web renamed it later: its title wins and the pick the file holds stays on the line.
    const newer = collect(lists({ titles: [{ chatId: CHAT, messageId: 6, title: 'Lantern', at: remote.corrections[0].at + 10 }] }), noBase(), remote, 777, now, 'web')
    const merged = mergeCounting(remote, newer, now).doc
    assert.equal(merged.corrections.find((c) => c.msg === tdlibMessageId(6))?.text, 'tmdb=show:123\ntitle=Lantern')
    assert.deepEqual(apply(newer, merged).correctionsIn, [])
    // The apps reset the rename later than the web set it: the web's title goes, the pick stays.
    const web = collect(lists({ titles: [{ chatId: CHAT, messageId: 6, title: 'Lantern', at: now - 5_000 }] }), noBase(), emptyDoc(777), 777, now, 'web')
    const phone = emptyDoc(777)
    phone.corrections.push({ chat: CHAT, msg: tdlibMessageId(6), text: 'tmdb=show:123', at: now - 4_000 })
    const taken = recorder()
    await applyLocal(apply(web, mergeCounting(phone, web, now).doc), taken.writer)
    assert.deepEqual(taken.log.filter((l) => /^(pick|title)/.test(l)), [`pick ${CHAT}:6 show:123 @${now - 4_000}`, `title ${CHAT}:6 none @${now - 4_000}`])
    // The apps removed the whole correction later than the web wrote it: both lines go.
    const removedOnPhone = emptyDoc(777)
    removedOnPhone.removed.push({ list: 'correction', key: keyOf(CHAT, tdlibMessageId(6)), at: now - 3_000 })
    const out = recorder()
    await applyLocal(apply(web, mergeCounting(removedOnPhone, web, now).doc), out.writer)
    assert.deepEqual(out.log.filter((l) => /^(pick|title)/.test(l)), [`pick ${CHAT}:6 none @0`, `title ${CHAT}:6 none @0`])
})

test('an apps\' file written into a web with nothing: places, marks and lines in the web\'s ids, the show line, bookmarks and lists left alone', async () => {
    const remote = await coreFile()
    const now = remote.updatedAt + 1_000
    const local = collect(lists(), noBase(), remote, 777, now, 'web')
    const { doc: merged } = mergeCounting(remote, local, now)
    const a = apply(local, merged)
    const { log, writer } = recorder()
    const done = await applyLocal(a, writer)
    assert.deepEqual(log.filter((l) => l.startsWith('resume')).sort(), [`resume ${CHAT}:5 754/600 @${remote.resume[0].updatedAt}`, `resume 42:77 61/600 @${remote.resume[1].updatedAt}`].sort())
    assert.ok(log.some((l) => l.startsWith(`watched ${CHAT}:6 `)))
    assert.ok(log.some((l) => l.startsWith(`watched ${CHAT}:7 `)))
    assert.ok(log.some((l) => l.startsWith(`later ${CHAT}:8 `)))
    assert.ok(!log.some((l) => l.startsWith(`later ${CHAT}:3 `)), 'the show line is the apps\' own')
    assert.deepEqual(log.filter((l) => l.startsWith('pick')), [`pick ${CHAT}:6 show:123 @${remote.corrections[0].at}`])
    assert.deepEqual(log.filter((l) => l.startsWith('star')).sort(), [`star ${CHAT}`, 'star 42'].sort())
    assert.equal(done.changes, 8)
    assert.deepEqual([...done.resumed].sort(), remote.resume.map((r) => keyOf(r.chat, r.msg)).sort())
    // Writing it back changes nothing the apps hold: bookmarks, named lists and the show line are all still there.
    const out = mergeCounting(remote, local, now).doc
    assert.equal(out.bookmarks.length, 1)
    assert.ok(out.lists)
    assert.equal(out.later.filter((l) => l.series).length, 1)
    assert.deepEqual(nextBase(local, a, done.resumed, now).resumeKeys.sort(), remote.resume.map((r) => keyOf(r.chat, r.msg)).sort())
})

test('a mark taken off on the web reaches a device that held it, and not the other way round', async () => {
    const remote = await coreFile()
    const now = remote.updatedAt + 60_000
    // The web synced once, then the viewer unmarked video 6.
    const web = collect(lists({ tombs: [{ list: 'watched', key: keyOf(CHAT, tdlibMessageId(6)), at: now - 1_000 }] }), noBase(), remote, 777, now, 'web')
    const merged = mergeCounting(remote, web, now).doc
    assert.ok(!merged.watched.some((w) => w.msg === tdlibMessageId(6)))
    const onApp = apply(remote, merged)
    assert.deepEqual([...onApp.watchedOut.keys()], [keyOf(CHAT, tdlibMessageId(6))])
    // A web that never held it does not take the apps' mark off for being absent.
    const web2 = collect(lists(), noBase(), remote, 777, now, 'web')
    const merged2 = mergeCounting(remote, web2, now).doc
    assert.equal(apply(remote, merged2).watchedOut.size, 0)
    assert.ok(merged2.watched.some((w) => w.msg === tdlibMessageId(6)))
})

test('the file the web writes after a sync is a file the apps read, with a file id that is positive and no other chat names', async () => {
    const remote = await coreFile()
    const now = remote.updatedAt + 1_000
    const local = collect(lists({ resumes: [{ chatId: CHAT, messageId: 11, position: 300, length: 1200, time: now - 10 }] }), noBase(), remote, 777, now, 'web')
    const merged = mergeCounting(remote, local, now).doc
    const { bytes } = await encode({ ...merged, account: 777, updatedAt: now, appVersion: 'web' })
    const back = await decode(bytes, 777)
    assert.equal(back.kind, 'ok')
    const doc = (back as { doc: SyncDoc }).doc
    assert.ok(doc.resume.some((r) => r.msg === tdlibMessageId(11) && r.file > 0 && r.chatTitle === ''))
    assert.equal(doc.resume.length, remote.resume.length + 1)
})

test('watch later lines are read from Telegram newest first within the budget, a gone video is skipped, the rest wait', async () => {
    const remote = emptyDoc(1)
    for (let i = 1; i <= 5; i++) remote.later.push({ chat: 1, msg: tdlibMessageId(i === 3 ? 404 : i), file: 0, title: `V${i}`, fileName: '', chatTitle: '', size: 0, durationSec: 0, mime: 'video/mp4', addedAt: 100 + i, series: '' })
    const a = apply(emptyDoc(1), remote)
    const { log, writer } = recorder()
    const done = await applyLocal(a, writer, 3)
    assert.deepEqual(log.filter((l) => l.startsWith('later')), ['later 1:5 @105', 'later 1:4 @104'])
    assert.equal(done.left, 2)
})

test('a message id that is not a server message\'s is left alone, never turned into a wrong one', async () => {
    const remote = emptyDoc(1)
    remote.resume.push({ chat: 1, msg: 12345, file: 3, title: 'Local', chatTitle: '', size: 0, durationSec: 0, positionMs: 5_000, durationMs: 0, updatedAt: 9 })
    remote.watched.push({ chat: 1, msg: 77, file: 0, title: '', chatTitle: '', size: 0, durationSec: 0, watchedAt: 9, manual: false })
    const { log, writer } = recorder()
    const done = await applyLocal(apply(emptyDoc(1), remote), writer)
    assert.deepEqual(log, [])
    assert.equal(done.changes, 0)
})

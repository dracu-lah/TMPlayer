import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { apply, fileNameOf, offered, readMessage, saveUrl, whyNot, type Env, type Save, type Savable } from './saveToDevice.ts'

const env: Env = { worker: true, streams: true }
const video = (o: Partial<Savable> = {}): Savable => ({ key: 'k1', size: 5_000_000, title: 'Plain Clip', canBeSaved: true, ...o })

test('a restricted chat never offers Save to device, nor a video in pieces, an archive, with no size, or a browser without the stream', () => {
    assert.equal(offered(video(), env), true)
    assert.equal(whyNot(video({ canBeSaved: false }), env), 'restricted')
    assert.equal(whyNot(video({ joined: {} }), env), 'pieces')
    assert.equal(whyNot(video({ zip: true }), env), 'archive')
    assert.equal(whyNot(video({ size: 0 }), env), 'noSize')
    assert.equal(whyNot(video(), { worker: false, streams: true }), 'noWorker')
    assert.equal(whyNot(video(), { worker: true, streams: false }), 'noStreams')
    // The restriction wins over everything else.
    assert.equal(whyNot(video({ canBeSaved: false, size: 0 }), { worker: false, streams: false }), 'restricted')
})

test('no room check: the browser cannot know the Downloads disk, so a save is never refused from storage.estimate()', () => {
    const run = readFileSync(new URL('./saveRuntime.ts', import.meta.url), 'utf8')
    const rules = readFileSync(new URL('./saveToDevice.ts', import.meta.url), 'utf8')
    assert.doesNotMatch(run, /storage\??\.estimate|\.estimate\?\./)
    assert.doesNotMatch(rules, /estimate|quota/i)
    // The size is said when the save starts.
    assert.match(run, /web\.save_size_to_downloads/)
})

test('a full disk is the browser failing the download: the save ends as failed, keeps what was read, and the toast says so', () => {
    const one: Save = { id: 'a', name: 'A', size: 100, loaded: 0, state: 'running' }
    let s = apply([one], { type: 'save', id: 'a', state: 'progress', loaded: 60 })
    s = apply(s, { type: 'save', id: 'a', state: 'failed', why: 'read' })
    assert.deepEqual([s[0]!.state, s[0]!.loaded, s[0]!.why], ['failed', 60, 'read'])
    // A late progress message after the failure changes nothing.
    s = apply(s, { type: 'save', id: 'a', state: 'progress', loaded: 90 })
    assert.equal(s[0]!.loaded, 60)
    const run = readFileSync(new URL('./saveRuntime.ts', import.meta.url), 'utf8')
    assert.match(run, /t\('web\.save_failed'/)
    assert.match(run, /START_MS/)
})

test('the file is called what it is, never a path, and gets an extension from its type', () => {
    assert.equal(fileNameOf({ fileName: 'Harbour.Notes.S01E02.mkv', title: 'x' }), 'Harbour.Notes.S01E02.mkv')
    assert.equal(fileNameOf({ title: 'Plain Clip', mime: 'video/mp4' }), 'Plain Clip.mp4')
    assert.equal(fileNameOf({ title: 'Clip', mime: 'video/x-matroska; codecs=x' }), 'Clip.mkv')
    assert.equal(fileNameOf({ title: '../../etc/passwd' }), 'etc passwd.mp4')
    assert.equal(fileNameOf({ title: 'a\u0000b\nc:d|e' }), 'a b c d e.mp4')
    assert.equal(fileNameOf({ title: '...hidden' }), 'hidden.mp4')
    assert.equal(fileNameOf({ title: '' }), 'video.mp4')
    assert.ok(fileNameOf({ title: 'x'.repeat(500) }).length <= 125)
})

test('the address names the stream, the size, the type, the profile, the name and the save', () => {
    const url = saveUrl({ streamKey: 'save:k1:a', size: 12, mime: 'video/mp4; codecs=avc1', profile: 'p1', name: 'A B.mp4', id: 'a' })
    assert.equal(url, '/stream/save%3Ak1%3Aa?size=12&mime=video%2Fmp4&p=p1&save=A%20B.mp4&id=a')
})

test('the worker\'s messages are read by what they are, and nothing else counts', () => {
    assert.deepEqual(readMessage({ type: 'save', id: 'a', state: 'progress', loaded: 10 }), { type: 'save', id: 'a', state: 'progress', loaded: 10 })
    assert.deepEqual(readMessage({ type: 'save', id: 'a', state: 'failed', why: 'gone' }), { type: 'save', id: 'a', state: 'failed', why: 'gone' })
    for (const bad of [null, undefined, 5, 'x', {}, { type: 'read' }, { type: 'save' }, { type: 'save', id: 5, state: 'done' }, { type: 'save', id: 'a', state: 'weird' }]) assert.equal(readMessage(bad), null)
    assert.equal(readMessage({ type: 'save', id: 'a', state: 'progress', loaded: Infinity })?.loaded, undefined)
})

test('progress adds up, done and cancelled end a save, one that is over stays as it ended, other saves are untouched', () => {
    const one: Save = { id: 'a', name: 'A', size: 100, loaded: 0, state: 'running' }
    const two: Save = { id: 'b', name: 'B', size: 100, loaded: 0, state: 'running' }
    let s = apply([one, two], { type: 'save', id: 'a', state: 'progress', loaded: 40 })
    assert.equal(s[0]!.loaded, 40)
    assert.equal(s[1]!.loaded, 0)
    s = apply(s, { type: 'save', id: 'a', state: 'progress', loaded: 30 })
    assert.equal(s[0]!.loaded, 40)
    s = apply(s, { type: 'save', id: 'a', state: 'progress', loaded: 400 })
    assert.equal(s[0]!.loaded, 100)
    s = apply(s, { type: 'save', id: 'a', state: 'done' })
    assert.equal(s[0]!.state, 'done')
    s = apply(s, { type: 'save', id: 'a', state: 'failed' })
    assert.equal(s[0]!.state, 'done')
    s = apply(s, { type: 'save', id: 'b', state: 'cancelled' })
    assert.equal(s[1]!.state, 'cancelled')
    s = apply([two], { type: 'save', id: 'b', state: 'failed', why: 'gone' })
    assert.deepEqual([s[0]!.state, s[0]!.why], ['failed', 'gone'])
})

test('the service worker answers a save with an attachment, holds nothing, and a cancel ends the stream with an error', () => {
    const sw = readFileSync(new URL('../../public/sw.js', import.meta.url), 'utf8')
    assert.match(sw, /Content-Disposition': `attachment; filename\*=UTF-8''/)
    assert.match(sw, /controller\.error\(/)
    assert.match(sw, /type === 'save-cancel'/)
    assert.match(sw, /url\.searchParams\.has\('save'\)/)
})

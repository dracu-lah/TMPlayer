// The chat title registry: titles wait for the rules, a failed load is tried again by the next title.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createTitleNotes, type TitleSink } from './hintsCore.ts'

const sink = () => {
    const noted: Array<[number, string]> = []
    let forgot = 0
    const s: TitleSink = { noteChat: (id, title) => void noted.push([id, title]), forgetChats: () => void forgot++ }
    return { s, noted, forgot: () => forgot }
}
const tick = () => new Promise((r) => setTimeout(r, 0))

test('titles that arrive before the rules wait, then are noted once', async () => {
    const k = sink()
    let loads = 0
    const notes = createTitleNotes(async () => (loads++, k.s))
    notes.noteChatTitle(1, 'Malayalam Shows')
    notes.noteChatTitle(2, 'Plain')
    await tick()
    assert.deepEqual(k.noted, [[1, 'Malayalam Shows'], [2, 'Plain']])
    notes.noteChatTitle(3, 'Tamil')
    assert.equal(k.noted.length, 3)
    assert.equal(loads, 1)
})

test('a failed load keeps the waiting titles and the next title tries again', async () => {
    const k = sink()
    let loads = 0
    const notes = createTitleNotes(async () => {
        if (++loads === 1) throw new Error('catalog 503')
        return k.s
    })
    notes.noteChatTitle(1, 'Malayalam Shows')
    await tick()
    assert.deepEqual(k.noted, [])
    notes.noteChatTitle(2, 'Tamil Shows')
    await tick()
    assert.equal(loads, 2)
    assert.deepEqual(k.noted, [[1, 'Malayalam Shows'], [2, 'Tamil Shows']])
})

test('no timer and no loop: a load that keeps failing is tried once per new title', async () => {
    let loads = 0
    const notes = createTitleNotes(async () => {
        loads++
        throw new Error('down')
    })
    notes.noteChatTitle(1, 'a')
    await tick()
    await tick()
    assert.equal(loads, 1)
    notes.noteChatTitle(2, 'b')
    await tick()
    assert.equal(loads, 2)
})

test('signing out drops what waits and forgets what was noted; bad input is ignored', async () => {
    const k = sink()
    let release: (s: TitleSink) => void = () => {}
    const notes = createTitleNotes(() => new Promise<TitleSink>((r) => (release = r)))
    notes.noteChatTitle(1, 'Malayalam Shows')
    notes.noteChatTitle(Number.NaN, 'x')
    notes.noteChatTitle(2, 42)
    notes.forgetChatTitles()
    release(k.s)
    await tick()
    assert.deepEqual(k.noted, [], 'a title waiting at sign out is never noted')
    notes.forgetChatTitles()
    assert.equal(k.forgot(), 1)
})

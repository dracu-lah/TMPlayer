import assert from 'node:assert/strict'
import { test } from 'node:test'
import { closeModal, holdClose, isOpen, onlyOne, openModal, type DialogLike } from './confirmCore.ts'

/** A dialog as a browser keeps it, or (modal false) one from before showModal existed (iOS under 15.4). */
function fake(modal: boolean, throws = false) {
    const attrs = new Set<string>()
    const events: string[] = []
    const d: DialogLike = {
        setAttribute: (n) => void attrs.add(n),
        removeAttribute: (n) => void attrs.delete(n),
        hasAttribute: (n) => attrs.has(n),
        dispatchEvent: (e) => (events.push(e.type), true),
    }
    if (modal) {
        d.showModal = () => {
            if (throws) throw new Error('InvalidStateError')
            attrs.add('open')
        }
        d.close = () => {
            if (attrs.delete('open')) events.push('close')
        }
    }
    return { d, events }
}

test('openModal uses showModal where it exists', () => {
    const { d } = fake(true)
    assert.equal(openModal(d), true)
    assert.equal(isOpen(d), true)
})

test('openModal without showModal shows it in place and says it is not modal', () => {
    const { d } = fake(false)
    assert.equal(openModal(d), false)
    assert.equal(isOpen(d), true)
})

test('openModal never throws: a showModal that fails falls back to showing it', () => {
    const { d } = fake(true, true)
    assert.equal(openModal(d), false)
    assert.equal(isOpen(d), true)
})

test('closeModal without close() still ends in a close event, once', () => {
    const { d, events } = fake(false)
    openModal(d)
    closeModal(d)
    closeModal(d)
    assert.equal(isOpen(d), false)
    assert.deepEqual(events, ['close'])
})

test('holdClose ignores close() while the work runs and closes after, native or not', () => {
    for (const modal of [true, false]) {
        const { d, events } = fake(modal)
        let busy = true
        openModal(d)
        holdClose(d, () => busy)
        d.close!()
        assert.equal(isOpen(d), true, `still open while working (modal ${modal})`)
        assert.deepEqual(events, [])
        busy = false
        d.close!()
        assert.equal(isOpen(d), false)
        assert.deepEqual(events, ['close'])
    }
})

test('a second ask while one is open is answered no and never starts', async () => {
    let started = 0
    const start = () => (started++, Promise.resolve(true))
    assert.equal(await onlyOne(true, start), false)
    assert.equal(started, 0)
    assert.equal(await onlyOne(false, start), true)
    assert.equal(started, 1)
})

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readDismissed, saveDismissed, SAFARI_CARD_KEY, showSafariCard } from './safariCard.ts'

test('the card shows for a signed in visitor in an iPhone tab until it is closed', () => {
    assert.equal(showSafariCard({ inTab: true, signedIn: true, dismissed: false }), true)
    assert.equal(showSafariCard({ inTab: true, signedIn: true, dismissed: true }), false)
    assert.equal(showSafariCard({ inTab: false, signedIn: true, dismissed: false }), false)
    assert.equal(showSafariCard({ inTab: true, signedIn: false, dismissed: false }), false)
})

test('closing is remembered; a storage that throws or says anything else shows it again', () => {
    const kept = new Map<string, string>()
    const storage = { getItem: (k: string) => kept.get(k) ?? null, setItem: (k: string, v: string) => void kept.set(k, v) }
    assert.equal(readDismissed(storage), false)
    saveDismissed(storage)
    assert.equal(kept.get(SAFARI_CARD_KEY), '1')
    assert.equal(readDismissed(storage), true)
    kept.set(SAFARI_CARD_KEY, 'true')
    assert.equal(readDismissed(storage), false)
    const broken = {
        getItem: () => {
            throw new Error('blocked')
        },
        setItem: () => {
            throw new Error('blocked')
        },
    }
    assert.equal(readDismissed(broken), false)
    assert.doesNotThrow(() => saveDismissed(broken))
    assert.equal(readDismissed(null), false)
})

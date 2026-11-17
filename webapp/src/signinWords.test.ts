import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canResend, dialChip } from './screens/phone.ts'

test('the country chip is the code with a plus, whatever script it was typed in, and nothing while empty', () => {
    assert.equal(dialChip('91'), '+91')
    assert.equal(dialChip('+91'), '+91')
    assert.equal(dialChip(' 1 242 '), '+1242')
    assert.equal(dialChip('٩١'), '+91')
    assert.equal(dialChip(''), null)
    assert.equal(dialChip('+ -'), null)
    assert.equal(dialChip('x'.repeat(10_000)), null)
})

test('the resend row is a button when there is another route or an email code, else the muted line', () => {
    assert.equal(canResend({ next: 'sms', type: 'app' }), true)
    assert.equal(canResend({ next: 'none', type: 'sms' }), false)
    assert.equal(canResend({ next: 'none', type: 'email' }), true)
    assert.equal(canResend({ next: '', type: '' }), true)
})

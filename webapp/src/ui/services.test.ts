// Every service has a bundled mark or an initial to wear. `node --test src/ui/services.test.ts`
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { pairOf } from './colour.ts'
import { initialOf } from './initial.ts'
import { TMDB_SQUARE } from './serviceLogos.ts'
import { SERVICES, serviceOf } from './services.ts'

test('every service has a bundled mark or an initial in a name colour', () => {
    for (const service of SERVICES) {
        if (service.logo === 'tmdb-square') {
            assert.ok(TMDB_SQUARE.path.startsWith('M'), `${service.id} names a mark that is not bundled`)
        } else {
            assert.equal(service.logo, null, `${service.id} names an unknown mark`)
            assert.notEqual(initialOf(service.label), '', `${service.label} has no initial to draw`)
            assert.equal(pairOf(service.label).length, 2)
        }
    }
})

test('ids and names are unique and the lookup finds each', () => {
    assert.equal(new Set(SERVICES.map((s) => s.id)).size, SERVICES.length)
    assert.equal(new Set(SERVICES.map((s) => s.label.toLowerCase())).size, SERVICES.length)
    for (const service of SERVICES) assert.equal(serviceOf(service.id), service)
})

test('only TMDB wears a logo, the others are initials until their brand rules are read', () => {
    assert.deepEqual(SERVICES.filter((s) => s.logo !== null).map((s) => s.id), ['tmdb'])
})

test('the mark is the official path unchanged: its viewBox and gradient stops', () => {
    assert.equal(TMDB_SQUARE.viewBox, '0 0 190.24 81.52')
    assert.deepEqual(TMDB_SQUARE.stops.map((s) => s[1]), ['#90cea1', '#3cbec9', '#00b3e5'])
})

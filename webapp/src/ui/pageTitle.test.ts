import assert from 'node:assert/strict'
import { test } from 'node:test'
import { focusTarget, namedByContent, pageTitle, routeKey, setPageName, pageName } from './pageTitle.ts'

const say = (k: string) => ({ 'browse.tab_home': 'Home', 'nav.chats': 'Chats', 'common.search': 'Search', 'nav.settings': 'Settings', 'settings.page_about': 'About', 'web.not_found': 'Not found', 'login.sign_in': 'Sign in' })[k] ?? k

test('every route has a name, or says the page names itself', () => {
    assert.equal(routeKey('/'), 'browse.tab_home')
    assert.equal(routeKey('/chats'), 'nav.chats')
    assert.equal(routeKey('/search?q=x'), 'common.search')
    assert.equal(routeKey('/settings/storage'), 'nav.settings')
    assert.equal(routeKey('/history/watched'), 'browse.tab_history_heading')
    assert.equal(routeKey('/no/such/page'), 'web.not_found')
    for (const p of ['/chat/-100', '/chat/-100/info', '/video/-100/5', '/watch/-100/5']) assert.equal(namedByContent(p), true, p)
    assert.equal(namedByContent('/chats'), false)
})

test('titles read "Name - TMPlayer"', () => {
    assert.equal(pageTitle({ path: '/chats', say, name: null }), 'Chats - TMPlayer')
    assert.equal(pageTitle({ path: '/', say, name: null }), 'Home - TMPlayer')
    assert.equal(pageTitle({ path: '/settings/about', say, name: null, settingsPage: 'settings.page_about' }), 'About - TMPlayer')
    assert.equal(pageTitle({ path: '/settings', say, name: null, settingsPage: null }), 'Settings - TMPlayer')
    assert.equal(pageTitle({ path: '/video/-1/2', say, name: 'Lighthouse' }), 'Lighthouse - TMPlayer')
    assert.equal(pageTitle({ path: '/watch/-1/2', say, name: 'Lighthouse' }), 'Lighthouse - TMPlayer')
})

test('a video or chat page with no name yet is the site alone', () => {
    assert.equal(pageTitle({ path: '/video/-1/2', say, name: null }), 'TMPlayer')
})

test('a locked profile never shows a chat or video name', () => {
    assert.equal(pageTitle({ path: '/video/-1/2', say, name: 'Lighthouse', locked: true }), 'TMPlayer')
    assert.equal(pageTitle({ path: '/chat/-1', say, name: 'Family', locked: true }), 'TMPlayer')
    assert.equal(pageTitle({ path: '/chats', say, name: null, locked: true }), 'TMPlayer')
})

test('a name is kept trimmed and cleared with null or blank', () => {
    setPageName('  Lighthouse ')
    assert.equal(pageName(), 'Lighthouse')
    setPageName('   ')
    assert.equal(pageName(), null)
    setPageName(null)
    assert.equal(pageName(), null)
})

const el = (inside: unknown[] = []) => ({ contains: (n: unknown) => inside.includes(n) })

test('focus goes to the first heading, else the page, after a route change', () => {
    const h1 = el()
    const page = el()
    const nav = {}
    assert.equal(focusTarget({ heading: h1, page, active: nav, body: 'body', first: false }), h1)
    assert.equal(focusTarget({ heading: null, page, active: nav, body: 'body', first: false }), page)
    assert.equal(focusTarget({ heading: h1, page, active: 'body', body: 'body', first: false }), h1)
})

test('focus is left alone on the first load and where the page put it on purpose', () => {
    const h1 = el()
    const field = {}
    const page = el([field])
    assert.equal(focusTarget({ heading: h1, page, active: nav(), body: 'body', first: true }), null)
    assert.equal(focusTarget({ heading: h1, page, active: field, body: 'body', first: false }), null)
    function nav() {
        return {}
    }
})

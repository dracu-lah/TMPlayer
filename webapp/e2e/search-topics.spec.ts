// Search by topic name (WB21, U14): "Chat > Topic" rows over the videos, only for the forum chats among the
// added ones, only after typing; a row opens the chat held to that topic. And "Find in other chats" opens Search
// with its words (the first visit of a page load used to drop them). The fake's `forums` case has three groups with topics.
import { expect, test, type Page } from '@playwright/test'
import { countCalls, fakeCase, noSizeFloor, workerCalls } from './helpers'

const SHOTS = new URL('../../design/web-gaps/WB21/', import.meta.url)

/** Only these chats are added (the choice written where the app keeps it, before it starts). */
async function addOnly(page: Page, ids: number[]) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        (ids) =>
            new Promise<void>((resolve, reject) => {
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onerror = () => reject(open.error)
                open.onsuccess = () => {
                    const tx = open.result.transaction('kv', 'readwrite')
                    const kv = tx.objectStore('kv')
                    kv.put('picked', 'addedMode')
                    for (const id of ids) kv.put({ chatId: id, title: `Chat ${id}`, time: 1 }, `added:${id}`)
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
        ids,
    )
}

const rows = (page: Page) => page.locator('.topic-row .topic-label')
const field = (page: Page) => page.locator('.search-field input')

test('Find in other chats opens Search with its words, on the first visit of a page load too (the app navigates there itself)', async ({ page, context }) => {
    await fakeCase(context)
    await noSizeFloor(page)
    await page.goto('/chats')
    await expect(page.locator('.chat-row').first()).toBeVisible()
    // What Detail's "Find in other chats" does: an in-app navigation, which is not the page the load opened on.
    await page.evaluate(() => {
        history.pushState({}, '', '/search?q=S01E04')
        dispatchEvent(new PopStateEvent('popstate'))
    })
    await expect(page).toHaveURL(/\/search\?q=S01E04/)
    await expect(field(page)).toHaveValue('S01E04')
    // A pasted address is a fresh load: no old search comes back.
    await page.goto('/search?q=Clip')
    await expect(field(page)).toHaveValue('')
    await expect(page).not.toHaveURL(/q=/)
    // And a reload of the page the app opened Search on forgets it too.
    await page.evaluate(() => {
        history.pushState({}, '', '/search?q=Clip')
        dispatchEvent(new PopStateEvent('popstate'))
    })
    await expect(field(page)).toHaveValue('Clip')
    await page.reload()
    await expect(field(page)).toHaveValue('')
})

test('nothing about topics is asked before the viewer types, or for words with no letters', async ({ page, context }) => {
    await fakeCase(context, 'forums')
    await noSizeFloor(page)
    await page.goto('/search')
    await expect(field(page)).toBeVisible()
    await page.waitForTimeout(800)
    expect(countCalls(await workerCalls(page), 'getForumTopics')).toBe(0)
    await field(page).fill('🙂')
    await expect(page).toHaveURL(/q=/)
    await page.waitForTimeout(800)
    expect(countCalls(await workerCalls(page), 'getForumTopics')).toBe(0)
    await expect(page.locator('.topic-hits')).toHaveCount(0)
})

test('topics answer the words: "Chat > Topic", accents and emoji folded, a topic with no videos dimmed, a tap opens the chat on it', async ({ page, context }, info) => {
    await fakeCase(context, 'forums')
    await noSizeFloor(page)
    await page.goto('/search')
    await field(page).fill('lessons')
    await expect(rows(page)).toHaveCount(6)
    await expect(page.locator('.topic-hits-title')).toHaveText('Topics')
    const labels = await rows(page).allTextContents()
    expect(labels).toContain('Course Hub > ☕ Café Lessons')
    expect(labels).toContain('Language Club > Lessons week 1')
    expect(labels).toContain('Outside Forum > Lessons outside')
    // The topic that holds no videos is listed, dimmed, and says so; the closed one says so too.
    const archive = page.locator('.topic-row', { hasText: 'Lessons archive' })
    await expect(archive).toHaveClass(/dim/)
    await expect(archive.locator('.topic-facts')).toHaveText('No videos · Closed topic')
    await expect(page.locator('.topic-row', { hasText: 'Café Lessons' })).not.toHaveClass(/dim/)
    // The same words without the accent and the cup find the same topic.
    await field(page).fill('cafe')
    await expect(rows(page)).toHaveCount(1)
    await expect(rows(page)).toHaveText('Course Hub > ☕ Café Lessons')
    await page.screenshot({ path: new URL(`${info.project.name}-search-topics.png`, SHOTS).pathname })
    // A tap opens the chat held to that topic: only its two videos, its name in a chip, and the way back to every topic.
    await page.locator('.topic-row').click()
    await expect(page).toHaveURL(/\/chat\/-1010\?topic=2/)
    await expect(page.locator('.topic-chip')).toHaveText('☕ Café Lessons')
    await expect(page.locator('.card')).toHaveCount(2)
    const calls = await workerCalls(page)
    expect(calls).toContain('thread -1010 2')
    await page.screenshot({ path: new URL(`${info.project.name}-topic-chat.png`, SHOTS).pathname })
    await page.getByRole('button', { name: 'All topics' }).click()
    await expect(page).not.toHaveURL(/topic=/)
    await expect(page.locator('.topic-chip')).toHaveCount(0)
    await expect(page.locator('.card')).toHaveCount(3)
})

test('a topic with only files is opened and says there is nothing to play; Back returns to the results', async ({ page, context }) => {
    await fakeCase(context, 'forums')
    await noSizeFloor(page)
    await page.goto('/search')
    await field(page).fill('notes')
    await expect(rows(page)).toHaveText(['Course Hub > Lessons notes (PDF)'])
    await page.locator('.topic-row').click()
    await expect(page).toHaveURL(/topic=4/)
    await expect(page.locator('.muted', { hasText: 'No playable videos in Lessons notes (PDF).' })).toBeVisible()
    await page.goBack()
    await expect(field(page)).toHaveValue('notes')
    await expect(rows(page)).toHaveCount(1)
})

test('only the added chats are asked', async ({ page, context }) => {
    await fakeCase(context, 'forums')
    await addOnly(page, [-1010, -1001])
    await page.goto('/search')
    await field(page).fill('lessons')
    await expect(rows(page)).toHaveCount(3)
    expect((await rows(page).allTextContents()).every((l) => l.startsWith('Course Hub >'))).toBe(true)
    const calls = await workerCalls(page)
    expect(calls.filter((c) => c.startsWith('getForumTopics'))).toEqual(['getForumTopics -1010 lessons 20'])
    // The one-off "Search my other chats" asks every group, as it does for the videos.
    await page.getByRole('button', { name: /other chats/i }).click()
    await expect(rows(page)).toHaveCount(6)
})

test('a flood wait is waited out by the call that got it while the other groups answer', async ({ page, context }) => {
    await fakeCase(context, 'forums', 'topicflood')
    await noSizeFloor(page)
    await page.goto('/search')
    await field(page).fill('lessons')
    // The other two groups answer at once; Language Club's is told to wait two seconds and then listed.
    await expect(page.locator('.topic-row', { hasText: 'Course Hub' }).first()).toBeVisible()
    await expect(page.locator('.topic-row', { hasText: 'Language Club' })).toHaveCount(0)
    await expect(page.locator('.topic-row', { hasText: 'Language Club' })).toHaveCount(2, { timeout: 8000 })
    expect(countCalls(await workerCalls(page), 'getForumTopics')).toBe(4)
})

test('every group refusing says so with Try again; the videos stay', async ({ page, context }) => {
    await fakeCase(context, 'forums', 'topicsrefuse')
    await noSizeFloor(page)
    await page.goto('/search')
    await field(page).fill('clip')
    await expect(page.getByRole('alert').filter({ hasText: 'Topics could not be searched' })).toBeVisible()
    await expect(page.locator('.card').first()).toBeVisible()
    await context.clearCookies()
    await fakeCase(context, 'forums')
    await page.getByRole('button', { name: 'Try again' }).click()
    await expect(page.getByRole('alert').filter({ hasText: 'Topics could not be searched' })).toHaveCount(0)
})

test('clearing the words stops the asking and takes the topics away', async ({ page, context }) => {
    await fakeCase(context, 'forums', 'topicslow')
    await noSizeFloor(page)
    await page.goto('/search')
    await field(page).fill('lessons')
    await expect.poll(async () => countCalls(await workerCalls(page), 'getForumTopics')).toBeGreaterThan(0)
    await page.getByRole('button', { name: 'Clear search' }).click()
    await page.waitForTimeout(2500)
    await expect(page.locator('.topic-hits')).toHaveCount(0)
})

test('keyboard: Tab from the field reaches the first topic, Enter opens it', async ({ page, context }) => {
    await fakeCase(context, 'forums')
    await noSizeFloor(page)
    await page.goto('/search')
    await field(page).fill('week')
    await expect(rows(page)).toHaveCount(2)
    await field(page).press('Tab')
    // The clear button takes the first Tab while the box holds words; the next stop is the first topic.
    if (!(await page.locator('.topic-row').first().evaluate((el) => el === document.activeElement))) await page.keyboard.press('Tab')
    await expect(page.locator('.topic-row').first()).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/\/chat\/-1011\?topic=/)
})

test('a topic row that has the focus keeps it when the last answer replaces the first', async ({ page, context }) => {
    await fakeCase(context, 'forums', 'topicslow')
    await noSizeFloor(page)
    await page.goto('/search')
    await field(page).fill('week')
    await expect(rows(page)).toHaveCount(2)
    await page.locator('.topic-row').first().focus()
    // The answer lands in two steps (a group, then the counts): the section is busy until the second.
    await expect(page.locator('.topic-hits')).toHaveAttribute('aria-busy', 'false')
    await expect(page.locator('.topic-row').first()).toBeFocused()
})

test.describe('on the TV', () => {
    test.skip(({ browserName }) => browserName !== 'chromium', 'the TV walk runs in Chromium')
    test.use({ viewport: { width: 1920, height: 1080 } })

    test('the remote: OK opens the field, Down reaches the topics, OK opens one, Back returns to it', async ({ page, context }, info) => {
        test.skip(info.project.name !== 'chromium', 'one TV walk')
        await fakeCase(context, 'forums')
        await page.goto('/__e2e/sizes')
        await page.evaluate(() => localStorage.setItem('tmplayer-layout', 'tv'))
        await page.goto('/search')
        await expect(page.locator('html')).toHaveAttribute('data-form', 'tv')
        await expect.poll(() => page.evaluate(() => document.activeElement?.tagName)).toBe('INPUT')
        await page.keyboard.press('Enter')
        await page.waitForTimeout(150)
        await page.keyboard.type('week')
        await expect(page).toHaveURL(/q=week/)
        await expect(rows(page)).toHaveCount(2)
        await page.keyboard.press('ArrowDown')
        await expect(page.locator('.topic-row').first()).toBeFocused()
        await page.screenshot({ path: new URL(`tv-1920-search-topics.png`, SHOTS).pathname })
        await page.keyboard.press('ArrowDown')
        await expect(page.locator('.topic-row').nth(1)).toBeFocused()
        await page.keyboard.press('Enter')
        await expect(page).toHaveURL(/\/chat\/-1011\?topic=2/)
        await expect(page.locator('.topic-chip')).toHaveText('Lessons week 2')
        await page.keyboard.press('Backspace')
        await expect(page).toHaveURL(/\/search/)
        await expect(page.locator('.topic-row').nth(1)).toBeFocused()
    })
})

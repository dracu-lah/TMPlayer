import assert from 'node:assert/strict'
import { test } from 'node:test'
import { inAppBrowser, inAppName, iosMajor, isIos, newInstallSteps } from './inAppBrowser.ts'

// Real user agent strings.
const SAFARI_18 = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1'
const SAFARI_26 = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1'
const SAFARI_17 = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1'
const WKWEBVIEW = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148'
const INSTAGRAM = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 350.0.0.10.100 (iPhone14,5; iOS 18_5; en_US; en; scale=3.00; 1170x2532; 640004213)'
const FACEBOOK = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 [FBAN/FBIOS;FBAV/500.0.0.0.0;FBBV/1;FBDV/iPhone14,5;FBMD/iPhone;FBSN/iOS;FBSV/18.5;FBSS/3;FBID/phone;FBLC/en_US;FBOP/5]'
const CHROME_IOS = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/137.0.7151.79 Mobile/15E148 Safari/604.1'
const IPAD_DESKTOP = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15'
const IPAD_WEBVIEW = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148'
const MAC_SAFARI = IPAD_DESKTOP
const ANDROID_CHROME = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36'

const agent = (ua: string, o: Partial<{ touch: number; standalone: boolean; worker: boolean }> = {}) => ({ ua, touch: 5, standalone: false, worker: true, ...o })

test('Safari on an iPhone is not an in-app browser', () => {
    for (const ua of [SAFARI_18, SAFARI_26, SAFARI_17, CHROME_IOS]) assert.equal(inAppBrowser(agent(ua)), false, ua)
})

test('another app is: a web view string with no Safari token, whoever it is', () => {
    for (const ua of [WKWEBVIEW, INSTAGRAM, FACEBOOK]) assert.equal(inAppBrowser(agent(ua)), true, ua)
})

test('an iPad web view is told from a Mac by its touch screen', () => {
    assert.equal(inAppBrowser(agent(IPAD_WEBVIEW, { touch: 5 })), true)
    assert.equal(inAppBrowser(agent(IPAD_WEBVIEW, { touch: 0 })), false)
    assert.equal(inAppBrowser(agent(IPAD_DESKTOP, { touch: 5 })), false)
    assert.equal(inAppBrowser(agent(MAC_SAFARI, { touch: 0 })), false)
})

test('the installed Home Screen app has no Safari token and is not an in-app browser', () => {
    assert.equal(inAppBrowser(agent(WKWEBVIEW, { standalone: true })), false)
})

test('a Safari string with no service worker is a web view too; other systems never are', () => {
    assert.equal(inAppBrowser(agent(SAFARI_18, { worker: false })), true)
    assert.equal(inAppBrowser(agent(ANDROID_CHROME, { worker: false, touch: 5 })), false)
})

test('the app named in the string is for the log only', () => {
    assert.equal(inAppName(INSTAGRAM), 'instagram')
    assert.equal(inAppName(FACEBOOK), 'facebook')
    assert.equal(inAppName(WKWEBVIEW), 'webview')
})

test('the iOS version comes from the Safari version, as Safari 26 still says iPhone OS 18', () => {
    assert.equal(iosMajor(agent(SAFARI_26)), 26)
    assert.equal(iosMajor(agent(SAFARI_18)), 18)
    assert.equal(iosMajor(agent(SAFARI_17)), 17)
    // a web view has no Version token: the OS one is all there is
    assert.equal(iosMajor(agent(WKWEBVIEW)), 18)
    assert.equal(iosMajor(agent(ANDROID_CHROME)), 0)
    assert.equal(iosMajor(agent('')), 0)
})

test('iOS 26 gets the More button wording, older iOS the Share one', () => {
    assert.equal(newInstallSteps(26), true)
    assert.equal(newInstallSteps(27), true)
    assert.equal(newInstallSteps(18), false)
    assert.equal(newInstallSteps(0), false)
})

test('an iPhone, an iPad and a touch Mac string are iOS; a Mac and a phone of another make are not', () => {
    assert.equal(isIos(agent(SAFARI_18)), true)
    assert.equal(isIos(agent(IPAD_DESKTOP, { touch: 5 })), true)
    assert.equal(isIos(agent(IPAD_DESKTOP, { touch: 0 })), false)
    assert.equal(isIos(agent(ANDROID_CHROME)), false)
})

import { render } from 'solid-js/web'
import { App } from './App'
import { serveStreams, streams } from './stream/reader'
import { startTvService } from './stream/tvservice'
import { markEntry } from './session'
import { registerStreamWorker, registerWorker } from './install'
import { watchForm } from './form'
import { watchCrashes } from './crash'
import { takeMsoneParam } from './msoneFlag'
import { blockBrowserMenu } from './ui/CardMenu'
import { installModalHistory } from './ui/modalHistory'
import { languageFailedToast, startLanguage } from './i18n'
import { names } from './accounts'
import { usePicksDatabase } from './player/subpicks-db'
import './styles.css'

const START_WAIT_MS = 1200

// Before anything else runs, so a failure in what follows still gets its card (HX10).
watchCrashes()
// The remembered online subtitles of the profile this tab runs (HX88).
usePicksDatabase(names.subpicks)

if (location.hostname === 'localhost') void import('./survey')

// index.html has said the browser is too old: nothing more starts.
if (!document.documentElement.dataset.old) {
    markEntry()
    // Before the router starts: its popstate listener must come after this one (WP-54).
    installModalHistory()
    watchForm()
    // The browser's right click menu is the app's own long press menu on a card, and nothing elsewhere.
    blockBrowserMenu()
    takeMsoneParam()
    serveStreams()
    // The webOS .ipk wrapper opens the page with #tvsvc=port.token: the TV's player then reads through its loopback bridge.
    void startTvService((key) => streams.get(key))
    // The TV build has no offline shell, and the site's service worker would answer its page loads with
    // the other build: a TV gets a worker that only streams files to its own player.
    if (import.meta.env.MODE !== 'tv') registerWorker()
    else registerStreamWorker()
    // A report that was confirmed while the line was down is tried once, a minute after start, and never on its own again (WB11).
    setTimeout(() => void import('./ui/feedback').then((m) => m.flushSoon()).catch(() => {}), 60_000)
    const root = document.getElementById('app')!
    const start = () => {
        if (root.dataset.started) return
        root.dataset.started = '1'
        root.textContent = ''
        render(() => <App />, root)
    }
    // The viewer's language first (WB12), so the page does not paint in English and then change: from
    // the service worker's copy that takes a moment. A slow line gets the page in English after
    // START_WAIT_MS, and the words change in place once the language comes in.
    void startLanguage().then((ok) => {
        start()
        if (!ok) languageFailedToast()
    })
    setTimeout(start, START_WAIT_MS)
}

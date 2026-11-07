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
import './styles.css'

// Before anything else runs, so a failure in what follows still gets its card (HX10).
watchCrashes()

if (location.hostname === 'localhost') void import('./survey')

// index.html has said the browser is too old: nothing more starts.
if (!document.documentElement.dataset.old) {
    markEntry()
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
    const root = document.getElementById('app')!
    root.textContent = ''
    render(() => <App />, root)
}

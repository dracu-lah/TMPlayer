import { render } from 'solid-js/web'
import { App } from './App'
import { serveStreams } from './stream/reader'
import { markEntry } from './session'
import { registerWorker } from './install'
import { watchForm } from './form'
import { watchCrashes } from './crash'
import { takeMsoneParam } from './msoneFlag'
import './styles.css'

// Before anything else runs, so a failure in what follows still gets its card (HX10).
watchCrashes()

if (location.hostname === 'localhost') void import('./survey')

// index.html has said the browser is too old: nothing more starts.
if (!document.documentElement.dataset.old) {
    markEntry()
    watchForm()
    takeMsoneParam()
    serveStreams()
    // The TV build has no offline shell, and the site's service worker would answer its page loads with the other build.
    if (import.meta.env.MODE !== 'tv') registerWorker()
    const root = document.getElementById('app')!
    root.textContent = ''
    render(() => <App />, root)
}

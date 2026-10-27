// The TV build's polyfills (vite.config.ts, tvLegacy), run as a classic script before the page and
// at the start of the Telegram worker. core-js fills the language; the AbortSignal helpers mtcute
// and the app use came after the TVs' engines (throwIfAborted and timeout in Chromium 100 and 103,
// any in 116) and are not in core-js.
import 'core-js/stable'

const proto = AbortSignal.prototype as AbortSignal & { throwIfAborted?: () => void }
if (!proto.throwIfAborted) {
    proto.throwIfAborted = function (this: AbortSignal) {
        if (this.aborted) throw (this as { reason?: unknown }).reason ?? new DOMException('This operation was aborted', 'AbortError')
    }
}

const statics = AbortSignal as unknown as {
    timeout?: (ms: number) => AbortSignal
    any?: (signals: AbortSignal[]) => AbortSignal
}
if (!statics.timeout) {
    statics.timeout = (ms) => {
        const c = new AbortController()
        setTimeout(() => c.abort(new DOMException('The operation timed out.', 'TimeoutError')), ms)
        return c.signal
    }
}
if (!statics.any) {
    statics.any = (signals) => {
        const c = new AbortController()
        for (const s of signals) {
            if (s.aborted) {
                c.abort((s as { reason?: unknown }).reason)
                break
            }
            s.addEventListener('abort', () => c.abort((s as { reason?: unknown }).reason), { once: true })
        }
        return c.signal
    }
}

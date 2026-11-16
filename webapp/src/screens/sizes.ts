// The size switch's numbers and words as the page shows them, from the app's own SizeFilter in the
// worker, fetched once: what "Hide small clips" hides below and what Home leaves out.
import { createResource, createRoot } from 'solid-js'
import { api } from '../tg/rpc'

const limits = createRoot(() => createResource(() => api.sizeLimits())[0])
export { limits as sizeLimits }

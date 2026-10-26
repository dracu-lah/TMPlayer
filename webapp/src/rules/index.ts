// The app's own rules, the same Kotlin the phone, TV and desktop run (core's WebRules.kt), compiled
// to JS by `pnpm rules` (:core:webRules) into ./core. Loaded in the Telegram worker only: lists are
// grouped, sorted and filtered there, and the page gets the answers.
import * as core from './core/tmplayer-rules.mjs'
import catalogUrl from '../../../core/src/commonMain/resources/i18n/en.json?url'

// Kotlin marks its exported interfaces as not for implementing outside Kotlin, but the rules read
// and return plain objects only (WebRulesTest checks that), so the marker is dropped here.
type Plain<T> = T extends readonly (infer E)[]
    ? Plain<E>[]
    : T extends object
      ? { [K in keyof T as K extends '__doNotUseOrImplementIt' ? never : K]: Plain<T[K]> }
      : T

export type Rules = {
    [K in keyof typeof core]: (typeof core)[K] extends (...args: infer A) => infer R
        ? (...args: { [I in keyof A]: Plain<A[I]> }) => Plain<R>
        : never
}
export type RawVideo = Plain<core.RawVideo>
export type Video = Plain<core.Video>
export type Shelf = Plain<core.Shelf>
export type ShelfTile = Plain<core.ShelfTile>
export type Steps = Plain<core.Steps>
export type Parsed = Plain<core.Parsed>
export type PosterAsk = Plain<core.PosterAsk>
export type SizeLimits = Plain<core.SizeLimits>
export type WebResume = Plain<core.WebResume>
export type WebHomeRow = Plain<core.WebHomeRow>

let loading: Promise<Rules> | null = null

/**
 * The rules, once the English catalog is in: a few of them name things in the app's words (an
 * untitled file, a trailer). The catalog is a separate hashed file, fetched once and cached, so
 * it never weighs on the page's first load.
 */
export function rules(): Promise<Rules> {
    loading ??= fetch(catalogUrl)
        .then((r) => {
            if (!r.ok) throw new Error(`catalog ${r.status}`)
            return r.text()
        })
        .then((json) => {
            core.provideCatalog('en', json)
            return core as unknown as Rules
        })
        .catch((e) => {
            loading = null
            throw e
        })
    return loading
}

declare const __TG_API_ID__: number
declare const __TG_API_HASH__: string
/** The key for TMPlayer's subtitle gateway; empty and MSone is not offered. */
declare const __MSONE_GATEWAY_KEY__: string

interface Window {
    ManagedMediaSource?: typeof MediaSource
}

/** The catalog strings the page shows, picked out of en.json by vite.config.ts (strings.config.ts). */
declare module 'virtual:strings' {
    type Catalog = typeof import('../../core/src/commonMain/resources/i18n/en.json')
    type Entry = (typeof import('../strings.config'))['PAGE_STRINGS'][number]
    type Keys<S extends string, E = Entry> = E extends `${S}.${infer K}` ? K : never
    type Whole = { [S in Extract<Entry, keyof Catalog>]: Catalog[S] }
    type Parts = { [S in keyof Catalog as [Keys<S & string>] extends [never] ? never : S]: Pick<Catalog[S], Keys<S & string> & keyof Catalog[S]> }
    const strings: Whole & Parts
    export default strings
}

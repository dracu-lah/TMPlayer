declare const __TG_API_ID__: number
declare const __TG_API_HASH__: string
/** The key for TMPlayer's subtitle gateway; empty and MSone is not offered. */
declare const __MSONE_GATEWAY_KEY__: string
/** The log inbox's address (LOG_UPLOAD_URL, the one the apps carry); empty and the feedback form is not offered, only GitHub and e-mail. */
declare const __LOG_UPLOAD_URL__: string
/** The commit this build came from, 7 characters (`dev` when unknown), and the UTC day it was built (vite.config.ts): the version line's build (ui/versionLine.ts). */
declare const __APP_BUILD__: string
declare const __APP_BUILT__: string
/** The release number without a suffix ("1.28.7"), or '' when the build knows none: the compact version row (ui/versionLine.ts compactVersion). */
declare const __APP_RELEASE__: string

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

/** The other languages' page strings (vite.config.ts, pageCatalogs): each tag's file address, fetched by src/i18n.ts. */
declare module 'virtual:catalogs' {
    const urls: Record<string, string>
    export default urls
}

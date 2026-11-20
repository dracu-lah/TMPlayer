// The outside services the web player names on a screen (HX93), in one list, as the apps'
// ui/.../components/Service.kt has them: keep the two alike. A service has a bundled mark only where
// its own press or brand page offers one and its rules allow an app like this to show it (TMDB
// today, see docs/brand-assets.md); every other service wears its initial in its name colour,
// never a mark copied or redrawn from memory.

export type ServiceId =
    | 'opensubtitles'
    | 'subdl'
    | 'msone'
    | 'submaker'
    | 'tmdb'
    | 'tvmaze'
    | 'anilist'
    | 'omdb'
    | 'wikidata'
    | 'youtube'
    | 'telegram'
    | 'sentry'
    | 'theintrodb'
    | 'aniskip'
    | 'animeskip'
    | 'idmapping'

export interface Service {
    readonly id: ServiceId
    /** The name as the service writes it. */
    readonly label: string
    /** The bundled official mark's key (see serviceLogos.ts), or null where the badge is an initial. */
    readonly logo: 'tmdb-square' | null
}

export const SERVICES: readonly Service[] = [
    { id: 'opensubtitles', label: 'OpenSubtitles', logo: null },
    { id: 'subdl', label: 'SubDL', logo: null },
    { id: 'msone', label: 'MSone', logo: null },
    { id: 'submaker', label: 'SubMaker', logo: null },
    { id: 'tmdb', label: 'TMDB', logo: 'tmdb-square' },
    { id: 'tvmaze', label: 'TVmaze', logo: null },
    { id: 'anilist', label: 'AniList', logo: null },
    { id: 'omdb', label: 'OMDb', logo: null },
    { id: 'wikidata', label: 'Wikidata', logo: null },
    { id: 'youtube', label: 'YouTube', logo: null },
    { id: 'telegram', label: 'Telegram', logo: null },
    { id: 'sentry', label: 'Sentry', logo: null },
    { id: 'theintrodb', label: 'TheIntroDB', logo: null },
    { id: 'aniskip', label: 'AniSkip', logo: null },
    { id: 'animeskip', label: 'Anime-Skip', logo: null },
    { id: 'idmapping', label: 'arm.haglund.dev', logo: null },
]

export function serviceOf(id: ServiceId): Service {
    return SERVICES.find((s) => s.id === id) ?? SERVICES[0]
}

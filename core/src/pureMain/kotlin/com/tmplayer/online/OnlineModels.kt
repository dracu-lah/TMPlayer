package com.tmplayer.online

/** What Settings says about TMDB. Shows still come from TVmaze and AniList in every state. */
enum class TmdbState {
    /** The build's key. */
    AppKey,

    /** The viewer's own key. */
    OwnKey,

    /** No key at all: a fork or a CI build without the secret, and no key of the viewer's own. */
    NoKey,

    /** TMDB refused the build's key, so films go without until an update or a key of the viewer's own. */
    AppKeyRefused,

    /** TMDB refused the viewer's own key. */
    OwnKeyRefused,
}

/** What Settings says about OMDb, which adds IMDb's score. Wikidata's scores come in every state. */
enum class OmdbState {
    /** The build's key, shared by every install, so it may run out for the day. */
    AppKey,

    /** The viewer's own key. */
    OwnKey,

    /** No key: Rotten Tomatoes and Metacritic come from Wikidata, and there is no IMDb score. */
    NoKey,

    /** OMDb refused the viewer's own key. */
    OwnKeyRefused,
}

/**
 * The poster and overview switches as this device knows them.
 *
 * [enabled] is on unless the viewer turns it off (the user's call on 2026-10-13: posters are what make
 * the grid readable). A lookup sends only a video's cleaned name to TMDB, TVmaze or AniList, and the
 * privacy page says so. [ownKey] is a TMDB key of the
 * viewer's own, used instead of the build's. [refusedKey] is a fingerprint of a key TMDB refused,
 * so TMDB stays off for that key and comes back by itself when an update brings another.
 */
data class MetaSettings(
    val enabled: Boolean = true,
    val ownKey: String = "",
    val refusedKey: String = "",
    val refusedAt: Long = 0,
    /** An OMDb key of the viewer's own, used instead of the build's for IMDb's score. */
    val omdbKey: String = "",
    /** A fingerprint of an own OMDb key that OMDb refused, so it is not asked again until it changes. */
    val omdbRefusedKey: String = "",
    /** Skip intro, recap and credits buttons in the player, from TheIntroDB and AniSkip. */
    val skipTimes: Boolean = true,
    /**
     * A detail page's trailer plays in TMPlayer, in YouTube's embedded player (TrailerEmbed),
     * rather than in the YouTube app or the browser. On by default; off, or whenever it cannot
     * play here (TrailerRules), the trailer opens outside as before.
     */
    val trailersInApp: Boolean = true,
    /** What each kind of skip does when the playhead reaches it: Ask (the default), Skip by itself, or Off. Local, not in a backup. */
    val skipModes: SkipModes = SkipModes(),
    /**
     * Whether [enabled] was turned off by this version or a later one (Settings, the first-run
     * switch, the grid's note, a restore). False with [enabled] off means the off came from an
     * older version, when off was the default, so the viewer is asked once (see [MetaNotice]).
     */
    val offByChoice: Boolean = false,
)

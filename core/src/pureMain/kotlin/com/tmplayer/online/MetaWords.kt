package com.tmplayer.online

import com.tmplayer.i18n.L

/** The sentences posters and overviews show, the same on phone, TV and desktop. */
object MetaWords {

    /** Under "TMDB key" in Settings. */
    fun key(state: TmdbState): String = when (state) {
        TmdbState.AppKey -> L.metadataKeyApp
        TmdbState.OwnKey -> L.metadataKeyOwn
        TmdbState.NoKey -> L.metadataKeyNone
        TmdbState.AppKeyRefused -> L.metadataKeyAppRefused
        TmdbState.OwnKeyRefused -> L.metadataKeyOwnRefused
    }

    /** Under "OMDb key" in Settings. */
    fun omdbKey(state: OmdbState): String = when (state) {
        OmdbState.AppKey -> L.metadataOmdbKeyApp
        OmdbState.OwnKey -> L.metadataKeyOwn
        OmdbState.NoKey -> L.metadataOmdbKeyNone
        OmdbState.OwnKeyRefused -> L.metadataOmdbKeyOwnRefused
    }

    /** Above the OMDb key field, worded for whether the build has a key. */
    fun omdbKeyMessage(buildHasKey: Boolean): String = if (buildHasKey) L.metadataOmdbKeyMessage else L.metadataOmdbKeyMessageNone

    /** Above the key field: why a key of one's own, worded for whether the build has one. */
    fun keyMessage(buildHasKey: Boolean): String = if (buildHasKey) L.metadataKeyMessage else L.metadataKeyMessageNone

    /** "From Kitsu": whose words an anime episode's title and overview are ([MetaEpisode.source]). */
    fun creditFrom(source: String): String = L.metadataCredit(source)

    /** The credit under an overview. TVmaze's words are CC BY-SA, which asks for the licence by name. */
    fun credit(provider: MetaProvider): String = when (provider) {
        MetaProvider.TvMaze -> L.metadataCreditTvmaze
        else -> L.metadataCredit(provider.label)
    }
}

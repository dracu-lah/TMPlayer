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

    /** Above the key field: why a key of one's own, worded for whether the build has one. */
    fun keyMessage(buildHasKey: Boolean): String = if (buildHasKey) L.metadataKeyMessage else L.metadataKeyMessageNone

    /** The credit under an overview. TVmaze's words are CC BY-SA, which asks for the licence by name. */
    fun credit(provider: MetaProvider): String = when (provider) {
        MetaProvider.TvMaze -> L.metadataCreditTvmaze
        else -> L.metadataCredit(provider.label)
    }
}

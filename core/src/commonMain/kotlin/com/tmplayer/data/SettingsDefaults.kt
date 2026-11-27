package com.tmplayer.data

/** Defaults the platform sets at start, where one device differs from another. */
object SettingsDefaults {
    /**
     * Even out volume until the viewer chooses: off on a television, where processing every
     * sample of the sound costs a weak stick's cores while it decodes the picture.
     */
    @Volatile
    var evenVolume: Boolean = true
}

/** [SettingsStore.saveDownloadQueue]'s default: the marker of the store's own profile's database. */
const val DATABASE_OF_STORE = "@store"

/** What a chat's search turns the developer options on with, on a phone or a television. */
const val DEVELOPER_PHRASE = "/namecheck"

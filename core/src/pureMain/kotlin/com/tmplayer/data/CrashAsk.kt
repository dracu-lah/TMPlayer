package com.tmplayer.data

/**
 * Where crash reports stand: consent, off until somebody turns them on (S1).
 *
 * The question is asked in one place only, the "Send crash reports" row of Settings (Account), which
 * says what is sent, and after a crash (`CrashQuestion`). It is not asked in the setup after sign in
 * (R3: one question there, which chats) and there is no card after Skip: the one-time "Help fix
 * crashes?" card was removed. A stored answer or a stored "asked" mark stays readable
 * (`SettingsStore.crashReportsAsked`).
 */
object CrashAsk {

    /**
     * The documented alternative to consent (S1): crash reports on by default with an easy way to
     * turn them off. NOT enabled, and nothing reads it as true: switching it on is a change of the
     * legal basis, and docs/distribution/crash-consent.md lists everything that would have to
     * change with it (the privacy page's basis, the first-run notice, Play's Data safety form,
     * F-Droid's build kept off). Not legal advice.
     */
    const val OPT_OUT_DEFAULT_ON: Boolean = false
}

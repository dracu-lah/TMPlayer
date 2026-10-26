package com.tmplayer.data

import com.tmplayer.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * app/build.gradle.kts works the versionCode out of the version name itself, outside :core; it
 * must agree with [ReleaseVersion.androidCode], which the release workflow's script is checked
 * against (RL1).
 */
class VersionCodeTest {

    @Test
    fun `this build's versionCode is the release numbering's`() {
        assertEquals(ReleaseVersion.androidCode(BuildConfig.VERSION_NAME), BuildConfig.VERSION_CODE)
    }
}

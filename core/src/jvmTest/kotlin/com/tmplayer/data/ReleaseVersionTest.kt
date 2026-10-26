package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release numbering (RL1): every test version sits between the release before it and its own
 * release, on Android, in the MSI and in the order the updaters use, and nothing it makes falls
 * below a code the old scheme already shipped.
 */
class ReleaseVersionTest {

    private data class Case(val version: String, val code: Int?, val installer: String?)

    /** The table `scripts/release-version.py --check` reads too. */
    private val cases: List<Case> by lazy {
        val text = javaClass.classLoader.getResource("release-versions.txt")!!.readText()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val (version, code, installer) = line.split(Regex("\\s+"))
                Case(version, code.takeIf { it != "-" }?.toInt(), installer.takeIf { it != "-" })
            }
            .toList()
    }

    @Test
    fun `every case in the shared table`() {
        assertTrue(cases.size > 20)
        for (case in cases) {
            assertEquals(case.version, case.code, ReleaseVersion.androidCode(case.version))
            assertEquals(case.version, case.installer, ReleaseVersion.installerVersion(case.version))
        }
    }

    /** The order releases and test versions go out in, oldest first. */
    private val sequence = listOf(
        "1.28.2-beta.1", "1.28.2-beta.2", "1.28.2-beta.9", "1.28.2-beta.10", "1.28.2-beta.98", "1.28.2",
        "1.28.3-beta.1", "1.28.3", "1.29.0-beta.1", "1.29.0-beta.2", "1.29.0", "1.99.99", "2.0.0-beta.1", "2.0.0",
    )

    @Test
    fun `codes, installer versions and the updater's order all rise together`() {
        for ((older, newer) in sequence.zipWithNext()) {
            assertTrue("$older < $newer (code)", ReleaseVersion.androidCode(older)!! < ReleaseVersion.androidCode(newer)!!)
            assertTrue("$older < $newer (msi)", msi(older) < msi(newer))
            assertTrue("$older < $newer (updater)", UpdateFeed.isNewer(newer, older))
            assertFalse("$newer > $older (updater)", UpdateFeed.isNewer(older, newer))
        }
    }

    @Test
    fun `every new code is above every code the old scheme shipped`() {
        // major*10000 + minor*100 + patch: 1.28.1 was 12801, and the old scheme could not reach a
        // million before version 100.
        val oldLatest = 1 * 10_000 + 28 * 100 + 1
        assertEquals(12801, oldLatest)
        val lowestNew = ReleaseVersion.androidCode("1.0.0-beta.1")!!
        assertTrue(lowestNew > 99 * 10_000 + 99 * 100 + 99)
        assertTrue(ReleaseVersion.androidCode("1.28.2-beta.1")!! > oldLatest)
    }

    @Test
    fun `codes fit an int and Google Play's ceiling`() {
        val highest = ReleaseVersion.androidCode("2099.99.99")
        assertNotNull(highest)
        assertTrue(highest!! <= ReleaseVersion.MAX_CODE)
        assertTrue(ReleaseVersion.MAX_CODE < Int.MAX_VALUE)
        assertEquals(null, ReleaseVersion.androidCode("2100.0.0"))
    }

    @Test
    fun `installer versions stay inside what Windows Installer reads`() {
        for (version in sequence) {
            val (major, minor, build) = ReleaseVersion.installerVersion(version)!!.split('.').map(String::toInt)
            assertTrue(version, major <= 255 && minor <= 255 && build <= 65_535)
        }
    }

    @Test
    fun `only a beta suffix is a test version`() {
        assertTrue(ReleaseVersion.isTest("1.28.2-beta.1"))
        assertTrue(ReleaseVersion.isTest("v1.28.2-beta.98"))
        assertFalse(ReleaseVersion.isTest("1.28.2"))
        assertFalse(ReleaseVersion.isTest("1.28.2-dev"))
        assertFalse(ReleaseVersion.isTest("1.28.2-beta.0"))
        assertFalse(ReleaseVersion.isTest("1.28.2-beta.99"))
        assertFalse(ReleaseVersion.isTest("nonsense"))
    }

    /** An MSI version as a number Windows Installer would order the same way. */
    private fun msi(version: String): Long {
        val (major, minor, build) = ReleaseVersion.installerVersion(version)!!.split('.').map(String::toLong)
        return (major shl 32) or (minor shl 16) or build
    }
}

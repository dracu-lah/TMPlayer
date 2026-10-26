package com.tmplayer.data

/**
 * How TMPlayer numbers its builds: the tag, Android's versionCode and the desktop installers'
 * version (RL1, docs/RELEASING.md).
 *
 * A release is tagged `vX.Y.Z`. Before it, the same code goes out as one or more test versions,
 * GitHub pre-releases tagged `vX.Y.Z-beta.N`. Each build gets a stage: test version N is N (1 to
 * [MAX_BETA]) and the release itself is [FINAL_STAGE], so every test version of X.Y.Z sorts after
 * X.Y.(Z-1) and before X.Y.Z, and a test version can never block the release that follows it.
 *
 * - Android: `major*1000000 + minor*10000 + patch*100 + stage`. 1.28.2-beta.1 is 1280201 and
 *   1.28.2 is 1280299, both above every code of the old scheme (`major*10000 + minor*100 +
 *   patch`, 12801 for 1.28.1), so the switch is an ordinary upgrade.
 * - The desktop installers (the MSI's ProductVersion, macOS's CFBundleVersion):
 *   `major.minor.(patch*100 + stage)`, 1.28.201 and 1.28.299. Windows Installer compares only
 *   the first three fields, the third up to 65535, so the stage has to live inside the third.
 *
 * Anything else after the dash (a development build's `-dev`, CI's `-ci.N`, a test build's
 * `-test.N`) is stage 0: below the first test version of the same X.Y.Z.
 *
 * The same rules are written three more times, where this object cannot be reached: in
 * `scripts/release-version.py` (the release workflow), `app/build.gradle.kts` and
 * `desktop/build.gradle.kts`. The cases in `core/src/jvmTest/resources/release-versions.tsv` are
 * checked against this object and the script, and the builds' own tests check theirs against it.
 */
object ReleaseVersion {

    /** The stage of a release, after all of its test versions. */
    const val FINAL_STAGE = 99

    /** The highest N a `-beta.N` may have. */
    const val MAX_BETA = 98

    /** Minor and patch each have two digits of the code. */
    const val MAX_MINOR = 99
    const val MAX_PATCH = 99

    /** Google Play's ceiling for a versionCode, below Int.MAX_VALUE. */
    const val MAX_CODE = 2_100_000_000

    /** A version split into its numbers; [stage] as described on the object. */
    data class Parts(val major: Int, val minor: Int, val patch: Int, val stage: Int) {
        val isTest: Boolean get() = stage in 1..MAX_BETA
    }

    private val BETA = Regex("""beta\.(\d+)""")

    /**
     * Any version string ("1.28.2", "v1.28.2-beta.3", "1.0.1-ci.7"), or null when its first
     * three fields are not numbers.
     */
    fun parse(version: String): Parts? {
        val clean = version.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = clean.substringBefore('-')
        val suffix = clean.substringAfter('-', "")
        val fields = core.split('.')
        if (fields.size != 3) return null
        val numbers = fields.map { field -> field.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull() ?: return null }
        val stage = when {
            suffix.isEmpty() -> FINAL_STAGE
            else -> BETA.matchEntire(suffix)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..MAX_BETA } ?: 0
        }
        return Parts(numbers[0], numbers[1], numbers[2], stage)
    }

    /** Whether [version] is a test version (`-beta.N`), the kind a GitHub pre-release carries. */
    fun isTest(version: String): Boolean = parse(version)?.isTest == true

    /**
     * Android's versionCode for [version], or null when it does not fit the scheme (a minor or
     * patch above 99, a major that would pass Play's ceiling).
     */
    fun androidCode(version: String): Int? {
        val p = parse(version) ?: return null
        if (p.major < 1 || p.minor > MAX_MINOR || p.patch > MAX_PATCH) return null
        val code = p.major.toLong() * 1_000_000 + p.minor * 10_000L + p.patch * 100L + p.stage
        return code.takeIf { it <= MAX_CODE }?.toInt()
    }

    /**
     * The x.y.z the desktop installers carry: the MSI's ProductVersion and macOS's
     * CFBundleVersion. Null when Windows Installer could not take it (major or minor above 255,
     * the third field above 65535).
     */
    fun installerVersion(version: String): String? {
        val p = parse(version) ?: return null
        val third = p.patch * 100 + p.stage
        if (p.major > 255 || p.minor > 255 || third > 65_535) return null
        return "${p.major}.${p.minor}.$third"
    }
}

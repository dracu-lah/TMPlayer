package com.tmplayer.desktop.trailer

import java.util.Locale

/**
 * One OS and processor's Chromium, as jcefmaven publishes it on Maven Central: a jar holding a
 * single `.tar.gz` that unpacks to the folder JCEF loads from.
 *
 * [sha256] and [bytes] are pinned here from the published file (2026-11-17), so a download that
 * is cut short, swapped or changed on the way is refused before a byte of it is unpacked.
 * [unpackedBytes] is the sum of the tarball's file sizes, for the free space check.
 */
data class CefNative(
    /** jcefmaven's platform name: `linux-amd64`, `windows-arm64`, `macosx-arm64` and so on. */
    val platform: String,
    val bytes: Long,
    val unpackedBytes: Long,
    val sha256: String,
) {
    /** Where it can be fetched from, in order: Maven Central, then jcefmaven's own GitHub release. */
    val urls: List<String>
        get() = listOf(
            "https://repo.maven.apache.org/maven2/me/friwi/jcef-natives-$platform/$TAG_IN_URL/jcef-natives-$platform-$TAG_IN_URL.jar",
            "https://github.com/jcefmaven/jcefmaven/releases/download/${CefNatives.MAVEN_VERSION}/jcef-natives-$platform-$TAG_IN_URL.jar",
        )

    private companion object {
        val TAG_IN_URL = CefNatives.RELEASE_TAG.replace("+", "%2B")
    }
}

/**
 * Which Chromium this computer can have for in-app trailers (private/TRAILERS-PLAN.md, T4).
 *
 * TMPlayer ships JCEF's Java side only ([MAVEN_VERSION], libs.versions.toml); the natives must be
 * the build that Java side was made with ([RELEASE_TAG]), so a version bump there needs new pins
 * here (the `measure` steps are in docs/BUILDING.md, "In-app trailers").
 *
 * Every OS and processor jcefmaven builds for that TMPlayer could run on is listed: Linux, Windows
 * and macOS, each on x64 and arm64. 32 bit systems are left out (TMPlayer's runtime is 64 bit), so
 * anything else answers null and the trailer opens in YouTube.
 */
object CefNatives {

    /** jcefmaven's version, its GitHub release's name. */
    const val MAVEN_VERSION = "146.0.10"

    /** The JCEF, CEF and Chromium build, as `build_meta.json` inside the natives names it. */
    const val RELEASE_TAG = "jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179"

    /** The Chromium version, for the notices and the log. */
    const val CHROMIUM = "146.0.7680.179"

    val all: List<CefNative> = listOf(
        CefNative("linux-amd64", 147_903_463L, 372_907_318L, "b089370c3bf8f853183ec822817e1b58348d90d1236bb6a26e921f513449dac2"),
        CefNative("linux-arm64", 165_191_345L, 477_328_990L, "ad9fa0818b43d9e11fb5622b76fd5af8eadbcd33f11ff1ba106d64c52ad63c09"),
        CefNative("windows-amd64", 162_120_546L, 369_438_217L, "e9a2d4ef3c33b372c24655ee4cdaf9ef23fe2de5f7e57410576569977299c4b4"),
        CefNative("windows-arm64", 160_379_728L, 375_651_887L, "735b039c27fb9a79904938be6348fd0db6db9c395e79ded6a48dfb0e18e8ed22"),
        CefNative("macosx-amd64", 145_034_271L, 330_217_542L, "4c05aae6c80841bf763a9d0dcdc3c3332af57abe0ab76091fb7bb9ef128e1bad"),
        CefNative("macosx-arm64", 135_870_260L, 319_625_844L, "b9c8ad6809372db9b6597911b7d3f12f0f6b9b23ef1506261b5518f78332642b"),
    )

    /**
     * The natives for a JVM reporting [osName] and [osArch] (`os.name`, `os.arch`), or null when
     * there are none. Windows on arm64 running TMPlayer's x64 build reports `amd64` and gets the x64
     * natives, which it runs the same way it runs the rest of the app.
     */
    fun forSystem(osName: String? = System.getProperty("os.name"), osArch: String? = System.getProperty("os.arch")): CefNative? {
        val os = osName.orEmpty().lowercase(Locale.ROOT)
        val family = when {
            os.startsWith("linux") -> "linux"
            os.startsWith("windows") -> "windows"
            os.startsWith("mac") || os.startsWith("darwin") -> "macosx"
            else -> return null
        }
        val arch = when (osArch.orEmpty().lowercase(Locale.ROOT)) {
            "amd64", "x86_64", "x64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return all.firstOrNull { it.platform == "$family-$arch" }
    }
}

package com.tmplayer.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.platform.Logger
import java.io.File

/**
 * Gives libass a font for every subtitle script on a Linux desktop that has none for it.
 *
 * libass draws a line with the system's fonts, and a bare Linux (the AppImage on a fresh install)
 * often has nothing for Malayalam, Sinhala, Thai, Amharic or the other scripts OpenSubtitles, SubDL
 * and MSone serve, so the line is boxes. mpv's `sub-fonts-dir` does not help there: libass looks
 * for a fallback only through the system's font provider, which never sees that folder (tried with
 * mpv 0.40). So the fonts are unpacked to [dir] and fontconfig, which the bundled libass links, is
 * pointed at a config that takes the system's own and adds that folder. The system's fonts still
 * come first: fontconfig ranks the families its own rules alias to sans-serif above these.
 *
 * Windows (DirectWrite) and macOS (CoreText) have a font for each of these scripts, so nothing is
 * done there. The files are cut by ui/fonts/subtitle-fonts.py and ui/fonts/subset-noto.py (the
 * desktop UI's own subsets, reused here); SIL Open Font License 1.1, in THIRD_PARTY_NOTICES.md.
 */
object SubtitleFonts {

    /** Cut for subtitles only, in desktop/src/main/resources/subfonts. */
    private val SUBTITLE_ONLY = listOf(
        "Tamil", "Telugu", "Kannada", "Sinhala", "Gujarati", "Gurmukhi", "Oriya", "Thai", "Lao",
        "Myanmar", "Khmer", "Ethiopic", "Georgian", "Armenian", "Hebrew",
    ).map { "/subfonts/NotoSans$it-Regular.ttf" }

    /** The UI's own fallbacks (FallbackFonts.kt), which cover the rest, CJK by its common set. */
    private val FROM_UI = listOf(
        "Malayalam", "Devanagari", "Bengali", "ArabicUI", "SC", "TC", "JP", "KR",
    ).map { "/fonts/NotoSans$it-Subset.ttf" }

    private val dir: File get() = File(DesktopPaths.dataDir, "subfonts")

    private interface CLib : Library {
        fun setenv(name: String, value: String, overwrite: Int): Int
    }

    /**
     * Unpacks what is missing and sets `FONTCONFIG_FILE`. Runs before libmpv starts, since libass
     * reads the config when it first draws a subtitle. Any failure leaves the system's fonts alone.
     */
    fun prepare() {
        if (!OsInfo.isLinux) return
        runCatching {
            dir.mkdirs()
            for (resource in SUBTITLE_ONLY + FROM_UI) unpack(resource)
            val config = File(dir, "fonts.conf")
            val text = config(base = systemConfig(), fonts = dir, cache = File(DesktopPaths.cacheDir, "fontconfig"))
            if (!config.isFile || config.readText() != text) config.writeText(text)
            Native.load("c", CLib::class.java).setenv("FONTCONFIG_FILE", config.absolutePath, 1)
        }.onFailure { Logger.w("SubtitleFonts", "Subtitles keep the system's fonts", it) }
    }

    /**
     * A file is written again only when the app's copy differs in size, so an upgrade refreshes it.
     * The size comes from the jar's entry, so a launch with every file in place reads none of them.
     */
    private fun unpack(resource: String) {
        val url = SubtitleFonts::class.java.getResource(resource) ?: return
        val file = File(dir, resource.substringAfterLast('/'))
        val size = runCatching { url.openConnection().contentLengthLong }.getOrDefault(-1L)
        if (file.isFile && file.length() == size) return
        val part = File(dir, "${file.name}.part")
        url.openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        if (!part.renameTo(file)) {
            file.delete()
            part.renameTo(file)
        }
    }

    /** The config fontconfig would read without us: the user's own choice first, else the system's. */
    internal fun systemConfig(
        file: String? = System.getenv("FONTCONFIG_FILE"),
        path: String? = System.getenv("FONTCONFIG_PATH"),
    ): String = file?.takeIf { it.isNotBlank() }
        ?: path?.takeIf { it.isNotBlank() }?.let { "${it.substringBefore(':').trimEnd('/')}/fonts.conf" }
        ?: "/etc/fonts/fonts.conf"

    internal fun config(base: String, fonts: File, cache: File): String = """
        |<?xml version="1.0"?>
        |<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
        |<fontconfig>
        |  <include ignore_missing="yes">${xml(base)}</include>
        |  <dir>${xml(fonts.absolutePath)}</dir>
        |  <cachedir>${xml(cache.absolutePath)}</cachedir>
        |</fontconfig>
        |""".trimMargin()

    private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

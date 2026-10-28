package com.tmplayer.desktop.player

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class SubtitleFontsTest {

    @Test
    fun everyFontItUnpacksIsOnTheClasspath() {
        val names = listOf(
            "/subfonts/NotoSansTamil-Regular.ttf", "/subfonts/NotoSansSinhala-Regular.ttf",
            "/subfonts/NotoSansThai-Regular.ttf", "/subfonts/NotoSansEthiopic-Regular.ttf",
            "/subfonts/NotoSansMyanmar-Regular.ttf", "/subfonts/NotoSansKhmer-Regular.ttf",
            "/fonts/NotoSansMalayalam-Subset.ttf", "/fonts/NotoSansArabicUI-Subset.ttf",
        )
        for (name in names) assertNotNull(name, SubtitleFonts::class.java.getResource(name))
    }

    @Test
    fun theConfigTakesTheSystemsOwnFirst() {
        val text = SubtitleFonts.config("/etc/fonts/fonts.conf", File("/home/a&b/subfonts"), File("/home/a/cache"))
        assertTrue("<include ignore_missing=\"yes\">/etc/fonts/fonts.conf</include>" in text)
        assertTrue("<dir>/home/a&amp;b/subfonts</dir>" in text)
        assertTrue(text.indexOf("<include") < text.indexOf("<dir>"))
    }

    @Test
    fun theSystemConfigFollowsTheUsersOwnSetting() {
        assertEquals("/etc/fonts/fonts.conf", SubtitleFonts.systemConfig(file = null, path = null))
        assertEquals("/opt/my.conf", SubtitleFonts.systemConfig(file = "/opt/my.conf", path = "/x"))
        assertEquals("/usr/etc/fonts/fonts.conf", SubtitleFonts.systemConfig(file = " ", path = "/usr/etc/fonts/:/y"))
    }
}

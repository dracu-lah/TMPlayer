package com.tmplayer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SelfUpdateTest {

    private val assets = listOf(
        "SHA256SUMS-1.19.0.txt",
        "TMPlayer-1.19.0-universal.apk",
        "TMPlayer-1.19.0-linux-x64.tar.gz",
        "TMPlayer-1.19.0-windows-x64-portable.zip",
        "TMPlayer-1.19.0-windows-x64.msi",
        "TMPlayer-1.19.0-x86_64.AppImage",
        "TMPlayer-1.19.0.flatpak",
        "tmplayer-1.19.0.x86_64.rpm",
        "tmplayer_1.19.0_amd64.deb",
    )

    private fun detect(
        os: String,
        env: Map<String, String> = emptyMap(),
        launcher: String? = null,
        portable: Boolean = false,
        flatpak: Boolean = false,
        dpkg: Boolean = false,
        rpm: Boolean = false,
    ) = SelfUpdate.detect(os, env, launcher?.let(::File), portable, flatpak, dpkg) { rpm }

    @Test
    fun `tells the installs apart`() {
        assertEquals(InstallKind.WindowsMsi, detect("windows", launcher = "C:/Users/a/AppData/Local/TMPlayer/TMPlayer.exe"))
        assertEquals(InstallKind.WindowsPortable, detect("windows", launcher = "D:/apps/TMPlayer/TMPlayer.exe", portable = true))
        assertEquals(InstallKind.Manual, detect("windows"))
        assertEquals(InstallKind.Flatpak, detect("linux", flatpak = true, env = mapOf("APPIMAGE" to "/x")))
        assertEquals(InstallKind.AppImage, detect("linux", env = mapOf("APPIMAGE" to "/home/a/TMPlayer.AppImage")))
        assertEquals(InstallKind.Deb, detect("linux", launcher = "/opt/tmplayer/bin/TMPlayer", dpkg = true))
        assertEquals(InstallKind.Rpm, detect("linux", launcher = "/opt/tmplayer/bin/TMPlayer", rpm = true))
        // The tarball unpacked anywhere, or a development run: the release page.
        assertEquals(InstallKind.Manual, detect("linux", launcher = "/home/a/TMPlayer/bin/TMPlayer", dpkg = true))
        assertEquals(InstallKind.Manual, detect("linux"))
        assertEquals(InstallKind.Manual, detect("macos", launcher = "/Applications/TMPlayer.app"))
    }

    @Test
    fun `picks the asset each install updates from`() {
        assertEquals("TMPlayer-1.19.0-windows-x64.msi", SelfUpdate.assetFor(InstallKind.WindowsMsi, assets))
        assertEquals("TMPlayer-1.19.0-windows-x64-portable.zip", SelfUpdate.assetFor(InstallKind.WindowsPortable, assets))
        assertEquals("TMPlayer-1.19.0-x86_64.AppImage", SelfUpdate.assetFor(InstallKind.AppImage, assets))
        assertEquals("tmplayer_1.19.0_amd64.deb", SelfUpdate.assetFor(InstallKind.Deb, assets))
        assertEquals("tmplayer-1.19.0.x86_64.rpm", SelfUpdate.assetFor(InstallKind.Rpm, assets))
        assertNull(SelfUpdate.assetFor(InstallKind.Flatpak, assets))
        assertNull(SelfUpdate.assetFor(InstallKind.Manual, assets))
        assertNull(SelfUpdate.assetFor(InstallKind.Deb, listOf("TMPlayer-1.19.0-universal.apk")))
        assertEquals("SHA256SUMS-1.19.0.txt", SelfUpdate.checksumsName(assets))
    }

    @Test
    fun `offers an update only with a package and a checksum list`() {
        val release = LatestRelease("1.19.0", "page", assets)
        assertTrue(SelfUpdate(InstallKind.AppImage).canUpdateTo(release))
        assertFalse(SelfUpdate(InstallKind.Flatpak).canUpdateTo(release))
        assertFalse(SelfUpdate(InstallKind.AppImage).canUpdateTo(release.copy(assetNames = assets - "SHA256SUMS-1.19.0.txt")))
    }

    @Test
    fun `reads sha256sum output`() {
        val hash = "a".repeat(64)
        val other = "b".repeat(64)
        val sums = "$other  TMPlayer-1.19.0-universal.apk\n$hash  tmplayer_1.19.0_amd64.deb\n$other *TMPlayer-1.19.0-windows-x64.msi\n"
        assertEquals(hash, SelfUpdate.expectedSha256(sums, "tmplayer_1.19.0_amd64.deb"))
        assertEquals(other, SelfUpdate.expectedSha256(sums, "TMPlayer-1.19.0-windows-x64.msi"))
        assertNull(SelfUpdate.expectedSha256(sums, "TMPlayer-1.19.0.flatpak"))
    }

    @Test
    fun `hashes a file`() {
        val f = Files.createTempFile("tm", ".bin").toFile()
        f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SelfUpdate.sha256(f))
    }

    @Test
    fun `the release answer keeps each asset's link`() {
        val json = """{"tag_name":"v1.19.0","assets":[{"browser_download_url":"https://github.com/dracu-lah/TMPlayer/releases/download/v1.19.0/tmplayer_1.19.0_amd64.deb"}]}"""
        val parsed = DesktopUpdates.parse(json)!!
        assertEquals(
            "https://github.com/dracu-lah/TMPlayer/releases/download/v1.19.0/tmplayer_1.19.0_amd64.deb",
            parsed.assetUrls["tmplayer_1.19.0_amd64.deb"],
        )
    }

    @Test
    fun `quotes paths for powershell`() {
        assertEquals("C:\\Users\\O''Brien\\TMPlayer", SelfUpdate.psQuote("C:\\Users\\O'Brien\\TMPlayer"))
    }
}

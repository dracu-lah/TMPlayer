package com.tmplayer.desktop

import com.tmplayer.data.Release
import com.tmplayer.data.ReleaseAsset
import com.tmplayer.data.UpdateFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SelfUpdateTest {

    private val names = listOf(
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

    /** The release as the GitHub fallback reads it: no hashes, a checksum file instead. */
    private val release = Release(
        version = "1.19.0",
        pageUrl = "page",
        assets = names.mapNotNull { name -> UpdateFeed.keyFor(name)?.let { it to ReleaseAsset("https://x/$name") } }.toMap(),
        checksumsUrl = "https://x/SHA256SUMS-1.19.0.txt",
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
        fun pick(kind: InstallKind) = SelfUpdate.assetFor(kind)?.let(release.assets::get)?.name
        assertEquals("TMPlayer-1.19.0-windows-x64.msi", pick(InstallKind.WindowsMsi))
        assertEquals("TMPlayer-1.19.0-windows-x64-portable.zip", pick(InstallKind.WindowsPortable))
        assertEquals("TMPlayer-1.19.0-x86_64.AppImage", pick(InstallKind.AppImage))
        assertEquals("tmplayer_1.19.0_amd64.deb", pick(InstallKind.Deb))
        assertEquals("tmplayer-1.19.0.x86_64.rpm", pick(InstallKind.Rpm))
        assertNull(pick(InstallKind.Flatpak))
        assertNull(pick(InstallKind.Manual))
    }

    @Test
    fun `offers an update only with a package and a hash to check it by`() {
        assertTrue(SelfUpdate(InstallKind.AppImage).canUpdateTo(release))
        assertFalse(SelfUpdate(InstallKind.Flatpak).canUpdateTo(release))
        assertFalse(SelfUpdate(InstallKind.AppImage).canUpdateTo(release.copy(checksumsUrl = null)))
        // The feed's own hash is enough without the checksum file.
        val hashed = release.copy(
            checksumsUrl = null,
            assets = mapOf("linux-x64-appimage" to ReleaseAsset("https://x/a.AppImage", sha256 = "a".repeat(64))),
        )
        assertTrue(SelfUpdate(InstallKind.AppImage).canUpdateTo(hashed))
        assertFalse(SelfUpdate(InstallKind.Deb).canUpdateTo(hashed))
    }

    /** A release as CI publishes them now: the MSI, the AppImage and the tarball, nothing else. */
    private val minimal = Release(
        version = "1.22.0",
        pageUrl = "page",
        assets = listOf(
            "TMPlayer-1.22.0-universal.apk",
            "TMPlayer-1.22.0-windows-x64.msi",
            "TMPlayer-1.22.0-x86_64.AppImage",
            "TMPlayer-1.22.0-linux-x64.tar.xz",
        ).associate { name -> UpdateFeed.keyFor(name)!! to ReleaseAsset("https://x/$name", sha256 = "a".repeat(64)) },
    )

    @Test
    fun `the retired formats are told where to go instead of failing`() {
        for (kind in listOf(InstallKind.WindowsPortable, InstallKind.Deb, InstallKind.Rpm)) {
            assertFalse(kind.name, SelfUpdate(kind).canUpdateTo(minimal))
        }
        val portable = SelfUpdate.retiredLine(InstallKind.WindowsPortable, minimal)!!
        assertTrue(portable, "portable zip is no longer published" in portable && "Windows installer" in portable)
        assertTrue("carry over" in portable)
        for (kind in listOf(InstallKind.Deb, InstallKind.Rpm)) {
            val line = SelfUpdate.retiredLine(kind, minimal)!!
            assertTrue(line, "${kind.label} is no longer published" in line && "AppImage" in line && "carry over" in line)
        }
        val flatpak = SelfUpdate.retiredLine(InstallKind.Flatpak, minimal)!!
        assertTrue(flatpak, "Flatpak is no longer published" in flatpak && "AppImage" in flatpak && "sign in again" in flatpak)
        // The popup's line is the same sentence, with the release page button under it.
        assertEquals(portable, com.tmplayer.desktop.ui.updateLine(InstallKind.WindowsPortable, false, UpdateProgress.Idle, minimal))
    }

    @Test
    fun `the formats still published say nothing about moving`() {
        assertTrue(SelfUpdate(InstallKind.WindowsMsi).canUpdateTo(minimal))
        assertTrue(SelfUpdate(InstallKind.AppImage).canUpdateTo(minimal))
        for (kind in listOf(InstallKind.WindowsMsi, InstallKind.AppImage, InstallKind.Manual)) {
            assertNull(kind.name, SelfUpdate.retiredLine(kind, minimal))
        }
        // An older release that still carries them: no notice, the old update path.
        val old = release.copy(assets = release.assets + ("linux-x64-flatpak" to ReleaseAsset("https://x/TMPlayer-1.19.0.flatpak")))
        for (kind in InstallKind.entries) assertNull(kind.name, SelfUpdate.retiredLine(kind, old))
        assertTrue(SelfUpdate(InstallKind.Deb).canUpdateTo(release))
        assertTrue(SelfUpdate(InstallKind.WindowsPortable).canUpdateTo(release))
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
    fun `quotes paths for powershell`() {
        assertEquals("C:\\Users\\O''Brien\\TMPlayer", SelfUpdate.psQuote("C:\\Users\\O'Brien\\TMPlayer"))
    }
}

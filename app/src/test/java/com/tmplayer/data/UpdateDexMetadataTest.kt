package com.tmplayer.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The update's baseline profile, read out of the downloaded APK for the install session (HX73).
 * The APK is outside data: whatever is wrong with it, the answer is no profile, never a crash.
 */
class UpdateDexMetadataTest {

    @get:Rule
    val dir = TemporaryFolder()

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, data) ->
                out.putNextEntry(ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val dm = zip(mapOf("primary.prof" to ByteArray(64) { it.toByte() }, "primary.profm" to ByteArray(16)))

    private fun apk(entries: Map<String, ByteArray>): File = dir.newFile().apply { writeBytes(zip(entries)) }

    @Test
    fun `the profile the build packed is read out whole`() {
        val file = apk(mapOf("classes.dex" to ByteArray(10), UpdateDexMetadata.ENTRY to dm))
        assertArrayEquals(dm, UpdateDexMetadata.of(file))
    }

    @Test
    fun `an APK without one installs as before`() {
        assertNull(UpdateDexMetadata.of(apk(mapOf("classes.dex" to ByteArray(10)))))
    }

    @Test
    fun `garbage gives no profile`() {
        // Not a zip at all, cut short, empty, an entry that is not a .dm, one far too large.
        assertNull(UpdateDexMetadata.of(dir.newFile().apply { writeBytes(ByteArray(300) { (it * 7).toByte() }) }))
        assertNull(UpdateDexMetadata.of(dir.newFile().apply { writeBytes(zip(mapOf(UpdateDexMetadata.ENTRY to dm)).copyOf(40)) }))
        assertNull(UpdateDexMetadata.of(dir.newFile()))
        assertNull(UpdateDexMetadata.of(File(dir.root, "gone.apk")))
        assertNull(UpdateDexMetadata.of(apk(mapOf(UpdateDexMetadata.ENTRY to "not a zip".toByteArray()))))
        assertNull(UpdateDexMetadata.of(apk(mapOf(UpdateDexMetadata.ENTRY to ByteArray(0)))))
        assertNull(UpdateDexMetadata.of(apk(mapOf(UpdateDexMetadata.ENTRY to dm + ByteArray((UpdateDexMetadata.MAX_BYTES + 1).toInt())))))
    }

    /**
     * A silent install with the profile fails, the retry without it is refused (Android wants to
     * ask), and the question's session that follows must go without the profile too, or the
     * viewer's Install ends on "install failed" for the same bad profile.
     */
    @Test
    fun `once dropped the profile stays out of the question that follows`() {
        val afterFailure = UpdateDexMetadata.next(dm, SessionSteps.Step.Failed)
        assertNull("the retry goes without it", afterFailure)
        assertNull("so does the question after a refused retry", UpdateDexMetadata.next(afterFailure, SessionSteps.Step.SilentRefused))
        // A first session refused for want of a question keeps it: the profile was not the trouble.
        assertTrue(UpdateDexMetadata.next(dm, SessionSteps.Step.SilentRefused) === dm)
        assertTrue(UpdateDexMetadata.next(dm, SessionSteps.Step.Installed) === dm)
        assertNull(UpdateDexMetadata.next(null, SessionSteps.Step.Failed))
    }

    @Test
    fun `only a plain failure is tried again without the profile`() {
        assertTrue(UpdateDexMetadata.retryWithout(SessionSteps.Step.Failed))
        SessionSteps.Step.entries.filter { it != SessionSteps.Step.Failed }.forEach { step ->
            assertFalse("$step", UpdateDexMetadata.retryWithout(step))
        }
        assertEquals("base.dm", UpdateDexMetadata.SESSION_NAME)
    }
}

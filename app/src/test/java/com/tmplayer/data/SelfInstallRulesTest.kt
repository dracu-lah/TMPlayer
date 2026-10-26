package com.tmplayer.data

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules behind TMPlayer installing its own update (HX5). */
class SelfInstallRulesTest {

    private val self = "com.tmplayer"

    private fun allows(
        sdk: Int,
        target: Int? = 35,
        apkPackage: String? = self,
        owner: String? = null,
        mayInstall: Boolean = true,
        refusedOn: Long = 0L,
    ) = SilentUpdateRule.allows(sdk, self, apkPackage, target, owner, mayInstall, refusedOn)

    @Test
    fun `Android 6 to 11 always ask`() {
        for (sdk in 23..30) {
            assertNull(SilentUpdateRule.minTargetSdk(sdk))
            assertFalse("API $sdk", allows(sdk))
        }
    }

    @Test
    fun `Android 12 and later install the app's own update without asking`() {
        for (sdk in 31..37) assertTrue("API $sdk", allows(sdk))
    }

    @Test
    fun `the update's target SDK has to keep up with the platform`() {
        assertEquals(29, SilentUpdateRule.minTargetSdk(31))
        assertEquals(29, SilentUpdateRule.minTargetSdk(32))
        assertEquals(30, SilentUpdateRule.minTargetSdk(33))
        assertEquals(31, SilentUpdateRule.minTargetSdk(34))
        assertEquals(33, SilentUpdateRule.minTargetSdk(35))
        assertEquals(34, SilentUpdateRule.minTargetSdk(36))
        assertTrue(allows(31, target = 29))
        assertFalse(allows(31, target = 28))
        assertTrue(allows(35, target = 33))
        assertFalse(allows(35, target = 32))
        // TMPlayer targets 35: fine up to Android 17, after which the target has to move.
        assertTrue(allows(37, target = 35))
        assertFalse(allows(38, target = 35))
        assertFalse(allows(33, target = null))
    }

    @Test
    fun `only its own package`() {
        assertFalse(allows(34, apkPackage = "com.tmplayer.test"))
        assertFalse(allows(34, apkPackage = null))
    }

    @Test
    fun `on Android 14 another update owner makes it ask`() {
        assertTrue(allows(34, owner = null))
        assertTrue(allows(34, owner = ""))
        assertTrue(allows(34, owner = self))
        assertFalse(allows(34, owner = "com.android.vending"))
        assertFalse(allows(36, owner = "org.fdroid.fdroid"))
        // Android 12 and 13 have no update owner; whatever is passed is not one.
        assertTrue(allows(33, owner = "com.android.vending"))
    }

    @Test
    fun `installs from TMPlayer have to be allowed`() {
        assertFalse(allows(34, mayInstall = false))
    }

    @Test
    fun `a refusal asks straight away on that Android release and is tried again on the next`() {
        assertFalse(allows(34, refusedOn = 34))
        assertFalse(allows(33, refusedOn = 34))
        assertTrue(allows(35, refusedOn = 34))
    }

    // ---- the APK -----------------------------------------------------------------------------

    private val installed = ApkFacts(self, 1280199, setOf("aa"), targetSdk = 35)

    @Test
    fun `the same package, the same key and a higher code install`() {
        assertEquals(ApkCheck.Verdict.Ok, ApkCheck.verify(installed.copy(versionCode = 1290099), installed))
    }

    @Test
    fun `anything else is refused`() {
        assertEquals(ApkCheck.Verdict.Unreadable, ApkCheck.verify(null, installed))
        assertEquals(ApkCheck.Verdict.WrongPackage, ApkCheck.verify(installed.copy(packageName = "com.other", versionCode = 1290099), installed))
        assertEquals(ApkCheck.Verdict.WrongSigner, ApkCheck.verify(installed.copy(signers = setOf("bb"), versionCode = 1290099), installed))
        assertEquals(ApkCheck.Verdict.NotNewer, ApkCheck.verify(installed, installed))
        assertEquals(ApkCheck.Verdict.NotNewer, ApkCheck.verify(installed.copy(versionCode = 1280101), installed))
    }

    @Test
    fun `unknown signers never match`() {
        val unsigned = installed.copy(signers = emptySet(), versionCode = 1290099)
        assertEquals(ApkCheck.Verdict.WrongSigner, ApkCheck.verify(unsigned, installed))
        assertEquals(ApkCheck.Verdict.WrongSigner, ApkCheck.verify(installed.copy(versionCode = 1290099), installed.copy(signers = emptySet())))
    }

    @Test
    fun `a key rotated from the installed one is the same signer`() {
        val rotated = installed.copy(signers = setOf("bb"), pastSigners = setOf("aa", "bb"), versionCode = 1290099)
        assertEquals(ApkCheck.Verdict.Ok, ApkCheck.verify(rotated, installed))
    }

    // ---- the session's answers ---------------------------------------------------------------

    private fun step(status: Int, silent: Boolean = true, shown: Boolean = false, message: String? = null) =
        SessionSteps.step(status, silent, shown, message)

    @Test
    fun `every status the installer sends`() {
        assertEquals(SessionSteps.Step.Installed, step(PackageInstaller.STATUS_SUCCESS))
        assertEquals(SessionSteps.Step.Confirm, step(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(SessionSteps.Step.Conflict, step(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(SessionSteps.Step.Storage, step(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(SessionSteps.Step.Incompatible, step(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
        assertEquals(SessionSteps.Step.Failed, step(PackageInstaller.STATUS_FAILURE))
        assertEquals(SessionSteps.Step.Failed, step(PackageInstaller.STATUS_FAILURE_BLOCKED))
        assertEquals(SessionSteps.Step.Failed, step(PackageInstaller.STATUS_FAILURE_INVALID, message = "INSTALL_PARSE_FAILED"))
        assertEquals(SessionSteps.Step.Dead, step(PackageInstaller.STATUS_FAILURE, message = "Session dead"))
        assertEquals(SessionSteps.Step.Dead, step(PackageInstaller.STATUS_FAILURE, message = "Session was abandoned"))
    }

    @Test
    fun `aborted is the viewer saying no once asked, the platform refusing before`() {
        assertEquals(SessionSteps.Step.Cancelled, step(PackageInstaller.STATUS_FAILURE_ABORTED, silent = true, shown = true))
        assertEquals(SessionSteps.Step.Cancelled, step(PackageInstaller.STATUS_FAILURE_ABORTED, silent = false, shown = true))
        assertEquals(SessionSteps.Step.SilentRefused, step(PackageInstaller.STATUS_FAILURE_ABORTED, silent = true, shown = false))
        assertEquals(SessionSteps.Step.Cancelled, step(PackageInstaller.STATUS_FAILURE_ABORTED, silent = false, shown = false))
    }

    @Test
    fun `a refusal falls back to the question for the viewer, and stops in the background`() {
        assertEquals(SessionSteps.Next.AskInstead, SessionSteps.next(SessionSteps.Step.SilentRefused, foreground = true))
        assertEquals(SessionSteps.Next.Finish, SessionSteps.next(SessionSteps.Step.SilentRefused, foreground = false))
        assertTrue(SessionSteps.remembersRefusal(SessionSteps.Step.SilentRefused))
        assertFalse(SessionSteps.remembersRefusal(SessionSteps.Step.Cancelled))
        assertEquals(SessionSteps.Ending.NeedsViewer, SessionSteps.result(SessionSteps.Step.SilentRefused))
        // A question in the background is given up, never shown.
        assertEquals(SessionSteps.Ending.NeedsViewer, SessionSteps.result(SessionSteps.Step.Confirm))
    }

    @Test
    fun `a dead session goes to the system installer only for the viewer`() {
        assertEquals(SessionSteps.Next.SystemInstaller, SessionSteps.next(SessionSteps.Step.Dead, foreground = true))
        assertEquals(SessionSteps.Next.Finish, SessionSteps.next(SessionSteps.Step.Dead, foreground = false))
    }

    @Test
    fun `how each install ends`() {
        assertEquals(SessionSteps.Ending.Installed, SessionSteps.result(SessionSteps.Step.Installed))
        assertEquals(SessionSteps.Ending.Cancelled, SessionSteps.result(SessionSteps.Step.Cancelled))
        assertEquals(SessionSteps.Ending.Conflict, SessionSteps.result(SessionSteps.Step.Conflict))
        assertEquals(SessionSteps.Ending.Storage, SessionSteps.result(SessionSteps.Step.Storage))
        assertEquals(SessionSteps.Ending.Incompatible, SessionSteps.result(SessionSteps.Step.Incompatible))
        assertEquals(SessionSteps.Ending.Failed, SessionSteps.result(SessionSteps.Step.Failed))
        for (step in SessionSteps.Step.entries) {
            if (step != SessionSteps.Step.SilentRefused && step != SessionSteps.Step.Dead) {
                assertEquals(SessionSteps.Next.Finish, SessionSteps.next(step, foreground = true))
            }
        }
    }
}

package com.tmplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every preference [SettingsStore] writes is decided for HX88: the account's (kept per profile) or
 * the device's (shared). A new key in neither list fails here, as BackupCoverageTest does for backups.
 */
class AccountKeysTest {

    private val source = listOf(
        File("src/commonMain/kotlin/com/tmplayer/data/SettingsStore.kt"),
        File("core/src/commonMain/kotlin/com/tmplayer/data/SettingsStore.kt"),
    ).first { it.isFile }.readText()

    /** Every name the store writes: a fixed name whole, a name built from ids by its prefix. */
    private val keys: Set<String> = Regex("""PreferencesKey\("([^"]*)"""").findAll(source)
        .map { it.groupValues[1] }
        .map { name -> if (name.startsWith("\$CACHED_PREFIX")) "wc_" else name.substringBefore('$') }
        .filter { it.isNotEmpty() }
        .toSet()

    @Test
    fun `every settings key is the account's or the device's on purpose`() {
        assertTrue("found too few keys to trust the scan: $keys", keys.size > 50)
        val undecided = keys.filterNot { key ->
            key in AccountKeys.ACCOUNT || key in AccountKeys.DEVICE || key in AccountKeys.ACCOUNT_PREFIXES
        }
        assertTrue("Add these to AccountKeys.ACCOUNT, ACCOUNT_PREFIXES or DEVICE: $undecided", undecided.isEmpty())
    }

    @Test
    fun `no name is both`() {
        val both = AccountKeys.ACCOUNT.intersect(AccountKeys.DEVICE) + AccountKeys.ACCOUNT_PREFIXES.intersect(AccountKeys.DEVICE)
        assertTrue("both the account's and the device's: $both", both.isEmpty())
    }

    @Test
    fun `a device key that starts like an account prefix stays the device's`() {
        assertFalse(AccountKeys.isAccount("audio_passthrough"))
        assertFalse(AccountKeys.isAccount("rewind_on_resume_ms"))
        assertTrue(AccountKeys.isAccount("resume_12_34"))
        assertTrue(AccountKeys.isAccount("resume_sync_file"))
        assertTrue(AccountKeys.isAccount("vsort_-1001"))
        assertFalse("an unknown name is the device's", AccountKeys.isAccount("something_new"))
    }
}

package com.tmplayer.data

import java.io.File
import java.util.zip.ZipFile

/**
 * The baseline profile a downloaded update carries for Android 12 and later, as dex metadata
 * (`assets/updates/base.dm`, packed by the app's build from AGP's own `.dm`), which [SelfInstaller]
 * writes into its session beside the APK so Android compiles the app as it installs it (HX73).
 * Without it a sideloaded update runs uncompiled until the idle job gets to it, which on a TV that
 * is never idle can be days, and an uncompiled Home took three times the CPU on the Mi TV.
 *
 * The APK is outside data: an entry that is missing, empty, absurdly large or not a zip of its
 * own gives null, and the update installs as it always did.
 */
object UpdateDexMetadata {

    /** Where the build puts it inside the APK. */
    const val ENTRY = "assets/updates/base.dm"

    /** The name it takes in the session: the APK's own name with `.dm`, as Android pairs them. */
    const val SESSION_NAME = "base.dm"

    /** Far over the 25 KB it is; anything larger is not ours. */
    const val MAX_BYTES = 4L * 1024 * 1024

    /** The dex metadata in [apk], or null when there is none to use. */
    fun of(apk: File): ByteArray? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(ENTRY) ?: return null
            if (entry.isDirectory) return null
            // The size a zip states is not trusted: the read stops past the cap either way.
            if (entry.size > MAX_BYTES) return null
            val bytes = zip.getInputStream(entry).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            bytes.takeIf { looksLikeZip(it) }
        }
    }.getOrNull()

    /** A `.dm` is a zip (of `primary.prof` and `primary.profm`): it starts with a local file header. */
    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /**
     * Whether a session that carried the profile and ended in [step] with [message] is tried once
     * more without it: only a failure that names the profile (a bad one is
     * INSTALL_FAILED_BAD_DEX_METADATA, reported as a plain failure with that in its message). Not
     * after the viewer said no, a question nobody can answer, a conflict, no room or an APK this
     * device cannot run, and not after a plain failure that has nothing to do with the profile: no
     * session, no question to show, or the 20 minute wait ending with the install prompt ignored,
     * where a second session would only put a second prompt in front of the viewer.
     */
    fun retryWithout(step: SessionSteps.Step, message: String?): Boolean =
        step == SessionSteps.Step.Failed && namesProfile(message)

    /** Whether Android's [message] points at the dex metadata (`BAD_DEX_METADATA`, "dex metadata", `base.dm`). */
    fun namesProfile(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return text.contains("dex_metadata") || text.contains("dex metadata") || text.contains("dexmetadata") || text.contains(SESSION_NAME)
    }

    /**
     * The profile the install's next session carries after one carrying [profile] ended in [step]
     * with [message]: none once a session with it failed because of it ([retryWithout]), for the
     * retry and for the question that may follow it, else the same.
     */
    fun next(profile: ByteArray?, step: SessionSteps.Step, message: String?): ByteArray? =
        if (profile != null && retryWithout(step, message)) null else profile
}

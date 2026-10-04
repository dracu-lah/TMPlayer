package com.tmplayer.desktop.os

import com.tmplayer.platform.TransferNotifier.Kind
import com.tmplayer.platform.TransferNotifier.OpenTarget
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNotificationTextTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun progressBodySaysPercentSizesAndSpeed() {
        val body = LinuxNotificationText.progressBody(done = gb, total = 2 * gb, bytesPerSecond = 3L * 1024 * 1024)
        assertTrue(body, body.startsWith("50 %, "))
        assertTrue(body, body.contains(" of "))
        assertEquals(3, body.split(", ").size)
    }

    @Test
    fun progressBodyWithoutSizeIsJustBytes() {
        val body = LinuxNotificationText.progressBody(done = gb, total = null, bytesPerSecond = null)
        assertFalse(body.contains("%"))
        assertNull(LinuxNotificationText.percent(5, 0))
        assertEquals(100, LinuxNotificationText.percent(9, 5))
    }

    @Test
    fun progressHintsCarryValueAndLowUrgency() {
        val hints = LinuxNotificationText.hints(LinuxNotificationText.CATEGORY_PROGRESS, urgency = 0, percent = 42)
        assertEquals(42, hints.getValue("value").value)
        assertEquals(0.toByte(), hints.getValue("urgency").value)
        assertEquals("transfer", hints.getValue("category").value)
        assertEquals("io.github.dracu_lah.TMPlayer", hints.getValue("desktop-entry").value)
        assertEquals(true, hints.getValue("transient").value)
    }

    @Test
    fun completionHintsHaveNoValueAndAreNotTransient() {
        val hints = LinuxNotificationText.hints(LinuxNotificationText.CATEGORY_COMPLETE, urgency = 1, percent = null)
        assertFalse("value" in hints)
        assertFalse("transient" in hints)
        assertEquals("transfer.complete", hints.getValue("category").value)
    }

    @Test
    fun actionsPairKeysWithLabels() {
        assertEquals(
            listOf("default", "Open folder", "open", "Open folder"),
            LinuxNotificationText.actions(OpenTarget.Folder("/tmp")),
        )
    }

    @Test
    fun summariesNameTheKind() {
        assertEquals("Downloading Film", LinuxNotificationText.summary(Kind.Download, "Film"))
        assertEquals("Moving Film into Downloads", LinuxNotificationText.summary(Kind.MoveToDownloads, "Film"))
        assertEquals("Moving 12 downloads", LinuxNotificationText.summary(Kind.Migrate, "Moving 12 downloads"))
    }

    @Test
    fun launcherPropertiesHideTheBarWhenIdle() {
        assertEquals(mapOf("progress-visible" to false), LinuxNotificationText.launcherProperties(null).mapValues { it.value.value })
        val moving = LinuxNotificationText.launcherProperties(0.25f)
        assertEquals(0.25, moving.getValue("progress").value as Double, 1e-6)
        assertEquals(true, moving.getValue("progress-visible").value)
    }

    @Test
    fun notifySendArgsEndOptionsBeforeTheText() {
        val args = LinuxNotificationText.notifySendArgs("-x", "body", "transfer", "low", 7, replaces = 12, printId = true)
        assertEquals(listOf("-h", "int:value:7"), args.subList(args.indexOf("int:value:7") - 1, args.indexOf("int:value:7") + 1))
        assertTrue("-p" in args)
        assertEquals("12", args[args.indexOf("-r") + 1])
        assertEquals(listOf("--", "-x", "body"), args.takeLast(3))
        val plain = LinuxNotificationText.notifySendArgs("t", "", "transfer.complete", "normal", null, replaces = null, printId = false)
        assertFalse("-p" in plain || "-r" in plain)
        assertEquals(listOf("--", "t"), plain.takeLast(2))
    }

    @Test
    fun dbusSignalsMarshalWithoutABus() {
        // Building the message is where dbus-java works out the signature; a wrong one throws here.
        UnityLauncherEntry.Update(
            LinuxNotifications.LAUNCHER_PATH,
            LinuxNotificationText.LAUNCHER_APP_URI,
            LinuxNotificationText.launcherProperties(0.5f),
        )
        UnityLauncherEntry.Update(
            LinuxNotifications.LAUNCHER_PATH,
            LinuxNotificationText.LAUNCHER_APP_URI,
            mapOf("progress-visible" to Variant(false)),
        )
    }

    @Test
    fun powershellStringsDoubleEveryKindOfQuote() {
        assertEquals("'it''s'", WindowsToast.ps("it's"))
        assertEquals("'a’’b'", WindowsToast.ps("a’b"))
        assertEquals("'one two'", WindowsToast.ps("one\ntwo"))
        assertEquals("'\$env:x `n'", WindowsToast.ps("\$env:x `n"))
    }

    @Test
    fun xmlEscapesMarkupAndQuotes() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;", WindowsToast.xml("a & b <c> \"d\" 'e'"))
    }

    @Test
    fun doneToastOpensTheFolderThroughItsFileUri() {
        val uri = WindowsToast.launchUri(OpenTarget.File("/videos/Film.mkv"))!!
        assertTrue(uri, uri.startsWith("file:") && uri.trimEnd('/').endsWith("/videos"))
        assertNull(WindowsToast.launchUri(OpenTarget.DownloadsScreen))
        val xml = WindowsToast.doneXml("Downloaded", "Tom & Jerry", uri)
        assertTrue(xml, xml.startsWith("<toast activationType=\"protocol\" launch=\"file:"))
        assertTrue(xml.contains("<text>Tom &amp; Jerry</text>"))
        assertFalse(WindowsToast.doneXml("x", "", null).contains("activationType"))
    }

    @Test
    fun progressLinesAreOneLineWithInvariantNumbers() {
        val show = WindowsToast.showProgress("t1", WindowsToast.progressXml("Film's cut"), "Downloading", 0.5, "")
        val update = WindowsToast.updateProgress("t1", 3, null, "1 GB")
        listOf(show, update).forEach { assertFalse(it, it.contains('\n')) }
        assertTrue(show, show.contains("'0.500'"))
        assertTrue(show, show.contains("Film&apos;s cut"))
        assertTrue(update, update.contains("SequenceNumber = 3") && update.contains("'indeterminate'"))
    }
}

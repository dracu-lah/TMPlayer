package com.tmplayer.ui.audit

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Generic invariants over every screen of the phone audit (user, 2026-11-25), at font 1.0, 1.3 and 2.0
 * in the windows a phone and a tablet come in. They read the semantics tree, not a picture, so they
 * hold for a screen added tomorrow once it is in [allAuditScreens]:
 *
 *  - no text is cut by the window's side, by a box shorter than its lines, or by a line limit with no
 *    ellipsis ("text-off-window", "text-box-short", "text-line-cut", "text-cut-no-ellipsis");
 *  - a button's or tab's words stand in its middle within 2 px ("label-off-centre");
 *  - nothing pressable or readable sits under the account's picture, a fixed header or the status bar
 *    ("under-avatar", "under-header", "under-status-bar");
 *  - every pressable is a 48 dp target, a control inside a bigger pressable counted with it
 *    ("small-target").
 *
 * What is not listed in [Known] fails the build; [Known] names, with the reason, what the audit left
 * as it is (design/phone-audit/index.md has the same list).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-port-night-440dpi")
internal class PhoneInvariantsPortrait : PhoneAuditBase("invariants-393x873") {
    @Test fun `no screen breaks an invariant`() = runAll(allAuditScreens(), failOn = AuditChecks.INVARIANTS, known = Known.all, pictures = false)

    private fun clears(scale: Float) {
        val under = endOfLists(barPages(), scale)
        org.junit.Assert.assertTrue("${under.size} texts under the bottom bar at the end of their list:\n" + under.joinToString("\n"), under.isEmpty())
    }

    @Test fun `the last item of every bar page clears the bottom bar at font 1_0`() = clears(1f)

    @Test fun `the last item of every bar page clears the bottom bar at font 1_3`() = clears(1.3f)

    @Test fun `the last item of every bar page clears the bottom bar at font 2_0`() = clears(2f)
}

/** The pages that stand under the floating bar and scroll. */
internal fun barPages(): List<AuditScreen> = allAuditScreens().filter {
    (it.name.startsWith("home-") || it.name.startsWith("browse-") || it.name.startsWith("history-")) && it.name != "home-loading"
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
internal class PhoneInvariantsSmall : PhoneAuditBase("invariants-320x640") {
    @Test fun `no screen breaks an invariant`() = runAll(allAuditScreens(), failOn = AuditChecks.INVARIANTS, known = Known.small, pictures = false)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w873dp-h393dp-land-night-440dpi")
internal class PhoneInvariantsLandscape : PhoneAuditBase("invariants-873x393") {
    @Test fun `no screen breaks an invariant`() = runAll(allAuditScreens(), failOn = AuditChecks.INVARIANTS, known = Known.all, pictures = false)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-night-xhdpi")
internal class PhoneInvariantsTablet : PhoneAuditBase("invariants-800x1280") {
    @Test fun `no screen breaks an invariant`() = runAll(allAuditScreens(), failOn = AuditChecks.INVARIANTS, known = Known.all, pictures = false)
}

/** What the audit looked at and left: "screen/check" with the reason it is allowed. */
internal object Known {
    /** On 320 dp at twice the font a single long word is wider than the line: it breaks, as the system's own text does. */
    val small: Set<String> get() = all + setOf(
        "settings-notifications/word-broken", "settings-troubleshooting/word-broken", "settings-appearance/word-broken",
    )

    val all: Set<String> = setOf(
        // The grey digit of a generated cover (a picture of a number, drawn big on purpose and cut by the tile).
        "history-watched/text-box-short", "history-watched/text-line-cut",
        // Home's loader is one node over the whole page; the account's picture floats over its corner by design.
        "home-loading/under-avatar",
        // A row's heading just under the window's foot in a short window: its lazy item is measured, not drawn (no cut seen).
        "home-rows/text-box-short", "home-rows/text-line-cut", "home-rows-partial/text-box-short", "home-rows-partial/text-line-cut",
        "home-rows-stalled/text-box-short", "home-rows-stalled/text-line-cut",
    )
}

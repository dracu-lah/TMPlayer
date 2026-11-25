package com.tmplayer.ui.audit

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone audit's renders (user, 2026-11-25): every screen of [auditScreens] at font 1.0, 1.3 and
 * 2.0 in each window the phone and tablet come in, pictures under `build/phone-audit/<window>/` and
 * the invariants' findings beside them. They only render and collect, so a run is cheap to repeat
 * after a fix; the checks that fail a build are in [PhoneInvariantsTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-port-night-440dpi")
internal class PhoneAuditPortrait : PhoneAuditBase("phone-393x873") {
    @Test fun render() = runAll(allAuditScreens())
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-port-night-xhdpi")
internal class PhoneAuditSmall : PhoneAuditBase("phone-320x640") {
    @Test fun render() = runAll(allAuditScreens())
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w873dp-h393dp-land-night-440dpi")
internal class PhoneAuditLandscape : PhoneAuditBase("phone-873x393") {
    @Test fun render() = runAll(allAuditScreens())
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-night-xhdpi")
internal class PhoneAuditTabletPortrait : PhoneAuditBase("tablet-800x1280") {
    @Test fun render() = runAll(allAuditScreens())
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night-xhdpi")
internal class PhoneAuditTabletLandscape : PhoneAuditBase("tablet-1280x800") {
    @Test fun render() = runAll(allAuditScreens())
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-port-notnight-440dpi")
internal class PhoneAuditLight : PhoneAuditBase("phone-393x873-light") {
    @Test fun render() = runAll(allAuditScreens(), scales = listOf(1f))
}

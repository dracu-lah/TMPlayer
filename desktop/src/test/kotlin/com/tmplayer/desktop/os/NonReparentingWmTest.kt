package com.tmplayer.desktop.os

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NonReparentingWmTest {

    @Test
    fun `sway and its kind are recognised`() {
        assertTrue(NonReparentingWm.needed(mapOf("XDG_CURRENT_DESKTOP" to "sway")))
        assertTrue(NonReparentingWm.needed(mapOf("XDG_CURRENT_DESKTOP" to "Hyprland")))
        assertTrue(NonReparentingWm.needed(mapOf("XDG_SESSION_DESKTOP" to "niri")))
        assertTrue(NonReparentingWm.needed(mapOf("XDG_CURRENT_DESKTOP" to "sway:wlroots")))
        assertTrue(NonReparentingWm.needed(mapOf("SWAYSOCK" to "/run/user/1000/sway-ipc.sock")))
    }

    @Test
    fun `reparenting desktops are left alone`() {
        assertFalse(NonReparentingWm.needed(mapOf("XDG_CURRENT_DESKTOP" to "GNOME")))
        assertFalse(NonReparentingWm.needed(mapOf("XDG_CURRENT_DESKTOP" to "KDE")))
        assertFalse(NonReparentingWm.needed(mapOf("XDG_CURRENT_DESKTOP" to "XFCE")))
        assertFalse(NonReparentingWm.needed(emptyMap()))
    }

    @Test
    fun `a value the user set wins`() {
        assertFalse(NonReparentingWm.needed(mapOf("XDG_CURRENT_DESKTOP" to "sway", "_JAVA_AWT_WM_NONREPARENTING" to "0")))
    }
}

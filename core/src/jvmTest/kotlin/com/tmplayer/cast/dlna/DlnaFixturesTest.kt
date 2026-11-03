package com.tmplayer.cast.dlna

import com.tmplayer.cast.Container
import com.tmplayer.cast.ReceiverState
import com.tmplayer.cast.Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parsers against what real renderers sent (gmrender-resurrect and Rygel, captured 2026-11-03). */
class DlnaFixturesTest {

    @Test
    fun searchAnswersAreRecognisedWhateverTheHeaderCase() {
        for (r in DlnaFixtures.renderers) {
            val messages = DlnaFixtures.datagrams(r, "ssdp-replies.txt").map { Ssdp.parse(it) }
            assertTrue("$r: every answer parses", messages.all { it != null })
            for (m in messages.filterNotNull()) {
                assertEquals(SsdpMessage.Kind.SearchAnswer, m.kind)
                assertTrue(m.isRendererType)
                assertEquals("uuid:00000000-0000-0000-0000-000000000001", m.udn)
                assertNotNull(m.location)
            }
        }
        assertEquals(100, Ssdp.parse(DlnaFixtures.datagrams("gmrender", "ssdp-replies.txt").first())?.maxAgeSeconds)
        assertEquals(1800, Ssdp.parse(DlnaFixtures.datagrams("rygel", "ssdp-replies.txt").first())?.maxAgeSeconds)
    }

    @Test
    fun notifiesGiveAliveAndByeBye() {
        for (r in DlnaFixtures.renderers) {
            val kinds = DlnaFixtures.datagrams(r, "ssdp-notify.txt").mapNotNull { Ssdp.parse(it)?.kind }.toSet()
            assertTrue("$r alive", SsdpMessage.Kind.Alive in kinds)
            assertTrue("$r byebye", SsdpMessage.Kind.ByeBye in kinds)
        }
    }

    @Test
    fun descriptionsGiveTheThreeServices() {
        for (r in DlnaFixtures.renderers) {
            val d = DlnaFixtures.description(r)
            assertNotNull("$r description", d)
            d!!
            assertEquals("uuid:00000000-0000-0000-0000-000000000001", d.udn)
            assertTrue(d.friendlyName.isNotBlank())
            assertTrue("$r AVTransport ${d.avTransport.controlUrl}", d.avTransport.controlUrl.startsWith("http://192.0.2."))
            assertTrue(d.avTransport.type.contains(":service:AVTransport:"))
            assertNotNull("$r RenderingControl", d.renderingControl)
            assertNotNull("$r ConnectionManager", d.connectionManager)
        }
    }

    @Test
    fun gmrenderSinkListIsLongAndSaysMatroskaAndHevc() {
        val sink = (DlnaFixtures.soap("gmrender", "GetProtocolInfo") as SoapResult.Ok)["Sink"]
        val caps = SinkCaps.parse(sink)
        assertTrue(caps.declared && caps.takesVideo)
        assertTrue(Container.Matroska in caps.containers && Container.WebM in caps.containers && Container.MpegTs in caps.containers)
        assertEquals(Support.Yes, caps.caps.matroska)
        assertEquals(Support.Yes, caps.caps.hevc)
        assertEquals(Support.Yes, caps.caps.vp9)
    }

    @Test
    fun rygelSinkListLeadsWithOneProfileAndNamesMoreFurtherDown() {
        val sink = (DlnaFixtures.soap("rygel", "GetProtocolInfo") as SoapResult.Ok)["Sink"]
        val caps = SinkCaps.parse(sink)
        assertTrue("containers ${caps.containers}", Container.Mp4 in caps.containers && Container.MpegTs in caps.containers)
        assertEquals(Support.Yes, caps.caps.matroska)
        assertEquals(Support.Maybe, caps.caps.hevc)
        assertEquals(Support.Maybe, caps.caps.uhd)
        assertTrue("takes video: $caps", caps.takesVideo)
    }

    @Test
    fun stateAndPositionAnswersParse() {
        for (r in DlnaFixtures.renderers) {
            val transport = DlnaFixtures.soap(r, "Playing-GetTransportInfo") as SoapResult.Ok
            assertEquals("PLAYING", transport["CurrentTransportState"])
            val position = DlnaFixtures.soap(r, "Playing-GetPositionInfo") as SoapResult.Ok
            assertEquals("$r duration", 30_000L, UpnpTime.parseMs(position["TrackDuration"]))
            assertNotNull("$r position", UpnpTime.parseMs(position["RelTime"]))
        }
        // The quirk: Rygel, paused, says STOPPED in its state and Play, Seek in its actions (gmrender's 30 second file had played out by then).
        assertEquals("STOPPED", (DlnaFixtures.soap("rygel", "Paused-GetTransportInfo") as SoapResult.Ok)["CurrentTransportState"])
    }

    @Test
    fun transportActionsOfBothStylesAllowSeek() {
        for (r in DlnaFixtures.renderers) {
            val actions = TransportActions.parse((DlnaFixtures.soap(r, "Playing-GetCurrentTransportActions") as SoapResult.Ok)["Actions"])
            assertNotNull(actions)
            assertTrue("$r seek", actions!!.seek)
            assertTrue("$r pause", actions.pause)
            assertTrue("$r stop", actions.stop)
            assertTrue("$r not play while playing", !actions.play)
        }
        val paused = TransportActions.parse((DlnaFixtures.soap("rygel", "Paused-GetCurrentTransportActions") as SoapResult.Ok)["Actions"])!!
        assertTrue(paused.play && !paused.pause)
    }

    @Test
    fun faultsAreFaultsAndGmrenderAnswersABadSeekWithNoErrorAtAll() {
        for (r in DlnaFixtures.renderers) {
            val fault = DlnaFixtures.soap(r, "Fault-NoSuchAction")
            assertTrue("$r no such action: $fault", fault is SoapResult.Fault && fault.httpStatus == 500 && fault.code != null)
        }
        val rygel = DlnaFixtures.soap("rygel", "Fault-SeekBadTarget") as SoapResult.Fault
        assertEquals(711, rygel.code)
        assertEquals(718, (DlnaFixtures.soap("rygel", "Fault-BadInstance") as SoapResult.Fault).code)
        // The quirk worth knowing: a 200 and an empty response where the spec asks for an error.
        assertTrue(DlnaFixtures.soap("gmrender", "Fault-SeekBadTarget") is SoapResult.Ok)
    }

    @Test
    fun noMediaAnswersAreNotMistakenForPlaying() {
        val playback = DlnaPlayback()
        val stopped = DlnaFixtures.soap("gmrender", "Stopped-GetTransportInfo") as SoapResult.Ok
        val m = playback.update(stopped["CurrentTransportState"], stopped["CurrentTransportStatus"], null, null, 1000)
        assertEquals(ReceiverState.Idle, m.state)
        assertNull(m.idleReason)
    }
}

package com.tmplayer.cast.dlna

import com.tmplayer.cast.dlna.ManualAddress.Parsed
import com.tmplayer.cast.dlna.ManualAddress.Problem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address validator behind "Add a TV by its address", and resolving one against a fake TV. */
class ManualAddressTest {

    private val fake = FakeRenderer()

    @After
    fun tearDown() = fake.close()

    private fun target(input: String, loopback: Boolean = false) = ManualAddress.parse(input, loopback) as Parsed.Target

    private fun problem(input: String, loopback: Boolean = false) = (ManualAddress.parse(input, loopback) as Parsed.Refused).problem

    @Test
    fun anIpv4AddressIsAccepted() {
        val t = target("192.168.1.20")
        assertEquals("192.168.1.20", t.hostText)
        assertNull(t.port)
        assertNull(t.path)
        assertEquals("10.0.0.5", target("  10.0.0.5  ").hostText)
        assertEquals("172.16.9.9", target("172.16.9.9").hostText)
    }

    @Test
    fun aPortAndADescriptionLinkAreKept() {
        assertEquals(49152, target("192.168.1.20:49152").port)
        val t = target("http://192.168.1.20:49152/description.xml")
        assertEquals(49152, t.port)
        assertEquals("/description.xml", t.path)
        assertEquals("http://192.168.1.20:49152/description.xml", ManualAddress.candidates(t).single())
        assertEquals("/d.xml", target("HTTP://192.168.1.20/d.xml").path)
        assertNull("a bare slash is no path", target("http://192.168.1.20/").path)
    }

    @Test
    fun linkLocalAndSharedRangesAreAccepted() {
        assertEquals("169.254.10.1", target("169.254.10.1").hostText)
        assertEquals("100.64.1.1", target("100.64.1.1:8080").hostText)
    }

    @Test
    fun anAddressWithNoPortGetsTheGuessesAndAPortAlonePathsToTry() {
        val bare = ManualAddress.candidates(target("192.168.1.20"))
        assertTrue(bare.isNotEmpty() && bare.size <= 12)
        assertTrue(bare.all { it.startsWith("http://192.168.1.20:") })
        val ported = ManualAddress.candidates(target("192.168.1.20:8200"))
        assertTrue(ported.size > 1 && ported.all { it.startsWith("http://192.168.1.20:8200/") })
    }

    @Test
    fun anAddressOnThePublicInternetIsRefused() {
        assertEquals(Problem.NotLocal, problem("8.8.8.8"))
        assertEquals(Problem.NotLocal, problem("http://8.8.8.8/description.xml"))
        assertEquals(Problem.NotLocal, problem("172.32.0.1"))
        assertEquals(Problem.NotLocal, problem("192.169.1.1"))
        assertEquals(Problem.NotLocal, problem("100.128.0.1"))
        assertEquals(Problem.NotLocal, problem("0.0.0.0"))
        assertEquals(Problem.NotLocal, problem("224.0.0.251"))
        assertEquals(Problem.NotLocal, problem("255.255.255.255"))
    }

    @Test
    fun loopbackIsRefusedUnlessATestAllowsIt() {
        assertEquals(Problem.NotLocal, problem("127.0.0.1"))
        assertEquals("127.0.0.1", target("127.0.0.1", loopback = true).hostText)
    }

    @Test
    fun ipv6IsRefusedLinkLocalIncluded() {
        assertEquals(Problem.Ipv6, problem("fe80::1"))
        assertEquals(Problem.Ipv6, problem("[fe80::1%wlan0]:49152"))
        assertEquals(Problem.Ipv6, problem("http://[fe80::1]/d.xml"))
        assertEquals(Problem.Ipv6, problem("::1"))
    }

    @Test
    fun aHostNameIsRefusedAndNeverResolved() {
        assertEquals(Problem.Hostname, problem("tv.local"))
        assertEquals(Problem.Hostname, problem("http://livingroom-tv:49152/d.xml"))
        assertEquals(Problem.Hostname, problem("example.com"))
    }

    @Test
    fun aBadPortIsRefused() {
        assertEquals(Problem.Port, problem("192.168.1.20:0"))
        assertEquals(Problem.Port, problem("192.168.1.20:65536"))
        assertEquals(Problem.Port, problem("192.168.1.20:99999999"))
        assertEquals(Problem.Port, problem("192.168.1.20:"))
        assertEquals(Problem.Port, problem("192.168.1.20:abc"))
    }

    @Test
    fun garbageIsRefusedWithoutThrowing() {
        assertEquals(Problem.Empty, problem(""))
        assertEquals(Problem.Empty, problem("   "))
        assertEquals(Problem.Scheme, problem("https://192.168.1.20/d.xml"))
        assertEquals(Problem.Scheme, problem("ftp://192.168.1.20/"))
        assertEquals(Problem.Scheme, problem("file:///etc/passwd"))
        assertEquals(Problem.Credentials, problem("http://user:pw@192.168.1.20/d.xml"))
        assertEquals(Problem.Garbage, problem("999.1.1.1"))
        assertEquals(Problem.Garbage, problem("1.2.3"))
        assertEquals(Problem.Garbage, problem("192.168.1.20 and more"))
        assertEquals(Problem.Garbage, problem("http://192.168.1.20\r\n.evil/x"))
        assertEquals(Problem.TooLong, problem("1".repeat(400)))
        assertEquals(Problem.TooLong, problem("http://192.168.1.20/" + "a".repeat(250)))
        assertEquals(Problem.Garbage, problem("http://"))
    }

    @Test
    fun aLocationInAnAnswerMustBeOnTheTvItself() {
        assertEquals("http://192.168.1.20:80/d.xml", ManualAddress.locationOn("192.168.1.20", "http://192.168.1.20:80/d.xml"))
        assertNull(ManualAddress.locationOn("192.168.1.20", "http://192.168.1.21:80/d.xml"))
        assertNull(ManualAddress.locationOn("192.168.1.20", "http://8.8.8.8/d.xml"))
        assertNull(ManualAddress.locationOn("192.168.1.20", "http://192.168.1.20"))
        assertNull(ManualAddress.locationOn("192.168.1.20", null))
    }

    // Resolving against a fake TV on loopback

    private fun resolve(input: String, fetchTimeoutMs: Int = 1_000) =
        ManualAddress.resolve(input, timeoutMs = fetchTimeoutMs, allowLoopback = true, ssdpPort = fake.udpPort)

    @Test
    fun aDescriptionLinkIsFetchedThroughTheParser() {
        val found = resolve(fake.location) as ManualAddress.Outcome.Found
        assertEquals("Living room TV", found.device.name)
        assertTrue(found.device.manual)
        assertEquals(fake.location, found.device.location)
    }

    @Test
    fun anAddressWithAPortFindsTheDescriptionByTheCommonPaths() {
        val found = resolve("127.0.0.1:${fake.httpPort}") as ManualAddress.Outcome.Found
        assertEquals(fake.udn, found.device.udn)
    }

    @Test
    fun aBareAddressAsksTheTvItsLocationByUnicastSearch() {
        // Port 1 on loopback is where the guesses go and nothing is there: the unicast answer is the only way.
        val found = resolve("127.0.0.1") as ManualAddress.Outcome.Found
        assertEquals(fake.location, found.device.location)
        assertTrue(fake.searchTargets.isNotEmpty())
    }

    @Test
    fun anAddressNothingAnswersIsSilent() {
        fake.close()
        val outcome = resolve("http://127.0.0.1:${fake.httpPort}/description.xml", fetchTimeoutMs = 500)
        assertTrue(outcome.toString(), outcome is ManualAddress.Outcome.Silent)
    }

    @Test
    fun aBrokenDescriptionIsSaidNotThrown() {
        fake.descriptionBody = "<root><device><deviceType>"
        assertTrue(resolve(fake.location) is ManualAddress.Outcome.Broken)
        fake.descriptionBody = "not xml at all"
        assertTrue(resolve(fake.location) is ManualAddress.Outcome.Broken)
        fake.descriptionBody = "<root/>"
        assertTrue(resolve(fake.location) is ManualAddress.Outcome.Broken)
        fake.descriptionBody = "x".repeat(Xml.DESCRIPTION_MAX + 10)
        assertTrue(resolve(fake.location) is ManualAddress.Outcome.Broken)
    }

    @Test
    fun aRedirectOffTheLanIsNotFollowed() {
        fake.redirectTo = "http://8.8.8.8/description.xml"
        assertTrue(resolve(fake.location) is ManualAddress.Outcome.Broken)
    }

    @Test
    fun aPublicAddressNeverReachesTheNetwork() {
        var fetched = 0
        val outcome = ManualAddress.resolve("http://8.8.8.8/description.xml", fetch = { _, _ -> fetched++; DlnaDiscovery.Fetch.Failed })
        assertEquals(ManualAddress.Outcome.Invalid(Problem.NotLocal), outcome)
        assertEquals(0, fetched)
    }
}

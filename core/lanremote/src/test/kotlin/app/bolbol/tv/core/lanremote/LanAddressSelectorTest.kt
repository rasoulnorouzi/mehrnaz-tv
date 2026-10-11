package app.bolbol.tv.core.lanremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanAddressSelectorTest {
    private fun pick(vararg candidates: Pair<String?, String>) =
        LanAddressSelector.select(candidates.map { LanCandidate(it.first, it.second) })

    @Test
    fun picksTheWifiAddress() {
        assertEquals("192.168.1.23", pick("wlan0" to "192.168.1.23"))
    }

    @Test
    fun picksTheEthernetAddress() {
        assertEquals("10.0.0.7", pick("eth0" to "10.0.0.7"))
    }

    @Test
    fun noCandidatesMeansOffline() {
        assertNull(pick())
    }

    @Test
    fun loopbackLinkLocalAndUnspecifiedAreNeverChosen() {
        assertNull(pick("lo" to "127.0.0.1"))
        assertNull(pick("wlan0" to "127.0.0.1"))
        assertNull(pick("wlan0" to "127.1.2.3"))
        assertNull(pick("wlan0" to "169.254.10.20"))
        assertNull(pick("wlan0" to "0.0.0.0"))
        assertNull(pick("wlan0" to "224.0.0.1"))
        assertNull(pick("wlan0" to "255.255.255.255"))
    }

    @Test
    fun ipv6AndMalformedAddressesAreSkipped() {
        assertNull(pick("wlan0" to "fe80::1"))
        assertNull(pick("wlan0" to "fe80::1%wlan0"))
        assertNull(pick("wlan0" to "::1"))
        assertNull(pick("wlan0" to "192.168.1"))
        assertNull(pick("wlan0" to "192.168.1.256"))
        assertNull(pick("wlan0" to "192.168.1.1.1"))
        assertNull(pick("wlan0" to "192.168.1.x"))
        assertNull(pick("wlan0" to "192.168..1"))
        assertNull(pick("wlan0" to ""))
        assertNull(pick("wlan0" to "-1.2.3.4"))
        assertNull(pick("wlan0" to "1234.1.1.1"))
        assertEquals("192.168.1.9", pick("wlan0" to "fe80::1", "wlan0" to "bad", "wlan0" to "192.168.1.9"))
    }

    @Test
    fun cellularTunnelAndVirtualInterfacesAreSkipped() {
        assertNull(pick("rmnet_data0" to "10.20.30.40"))
        assertNull(pick("ccmni1" to "10.20.30.40"))
        assertNull(pick("tun0" to "10.8.0.2"))
        assertNull(pick("ppp0" to "10.64.64.64"))
        assertNull(pick("p2p-wlan0-0" to "192.168.49.1"))
        assertNull(pick("rndis0" to "192.168.42.129"))
        assertNull(pick("docker0" to "172.17.0.1"))
        assertNull(pick("dummy0" to "192.168.5.5"))
        assertEquals("192.168.1.5", pick("rmnet_data0" to "10.20.30.40", "tun0" to "10.8.0.2", "wlan0" to "192.168.1.5"))
    }

    @Test
    fun interfaceNamesAreCaseInsensitive() {
        assertNull(pick("TUN0" to "10.8.0.2"))
    }

    @Test
    fun firstPrivateCandidateWins() {
        assertEquals("192.168.1.5", pick("wlan0" to "192.168.1.5", "eth0" to "10.0.0.2"))
        assertEquals("10.0.0.2", pick("eth0" to "10.0.0.2", "wlan0" to "192.168.1.5"))
    }

    @Test
    fun privateRangesBeatOtherAddresses() {
        assertEquals("192.168.0.7", pick("wlan0" to "100.64.0.9", "eth0" to "192.168.0.7"))
        assertEquals("172.16.0.3", pick("eth0" to "8.8.8.8", "wlan0" to "172.16.0.3"))
    }

    @Test
    fun anAddressOutsideThePrivateRangesIsTheFallback() {
        assertEquals("100.64.0.9", pick("wlan0" to "100.64.0.9"))
        assertEquals("100.64.0.9", pick("wlan0" to "100.64.0.9", "eth0" to "127.0.0.1"))
        assertEquals("172.32.0.1", pick("wlan0" to "172.32.0.1"))
        assertEquals("172.15.0.1", pick("wlan0" to "172.15.0.1"))
    }

    @Test
    fun the172PrivateBlockCoversSixteenToThirtyOne() {
        assertEquals("172.16.0.1", pick("wlan0" to "100.64.0.1", "eth0" to "172.16.0.1"))
        assertEquals("172.31.255.1", pick("wlan0" to "100.64.0.1", "eth0" to "172.31.255.1"))
        assertEquals("100.64.0.1", pick("wlan0" to "100.64.0.1", "eth0" to "172.32.0.1"))
    }

    @Test
    fun unknownInterfaceNamesAreAccepted() {
        assertEquals("192.168.1.8", pick(null to "192.168.1.8"))
        assertEquals("192.168.1.8", pick("wlan1" to "192.168.1.8"))
    }

    @Test
    fun parseIpv4IsStrict() {
        assertEquals(listOf(1, 2, 3, 4), LanAddressSelector.parseIpv4("1.2.3.4")?.toList())
        assertEquals(listOf(255, 0, 0, 255), LanAddressSelector.parseIpv4("255.0.0.255")?.toList())
        assertNull(LanAddressSelector.parseIpv4("1.2.3"))
        assertNull(LanAddressSelector.parseIpv4(" 1.2.3.4"))
        assertNull(LanAddressSelector.parseIpv4("1.2.3.4 "))
        assertNull(LanAddressSelector.parseIpv4("+1.2.3.4"))
    }
}

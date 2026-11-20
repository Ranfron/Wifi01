package github.leavesczy.wifip2p.transfer

import github.leavesczy.wifip2p.band.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The band, host capability and nonce in the QR code. Codes from before them still parse. */
class ConnectCodeBandTest {

    @Test
    fun bandCapabilityAndNonceSurviveTheRoundTrip() {
        val code = ConnectCode("DIRECT-ab-WifiP2P-cdef", "secret-pass", "192.168.49.1", 1995, "Phone",
            Band.TWO_POINT_FOUR_GHZ, hostFiveGhz = true, nonce = "k7qm2wz9ab")
        assertEquals(code, ConnectCode.parse(code.encode()))
    }

    @Test
    fun aCodeWithoutTheNewFieldsIsAutomaticAndHasNoNonce() {
        val legacy = "wifip2p://c?v=1&s=DIRECT-ab-x&p=12345678&a=192.168.49.1&o=1995&n=Phone"
        val parsed = ConnectCode.parse(legacy)!!
        assertEquals(Band.AUTO, parsed.band)
        assertEquals("", parsed.nonce)
    }

    @Test
    fun anUnknownBandIsRefused() {
        val bad = "wifip2p://c?v=1&s=DIRECT-ab-x&p=12345678&a=192.168.49.1&o=1995&n=Phone&b=9"
        assertNull(ConnectCode.parse(bad))
    }

    @Test
    fun aMalformedNonceIsRefused() {
        val bad = "wifip2p://c?v=1&s=DIRECT-ab-x&p=12345678&a=192.168.49.1&o=1995&n=Phone&b=5&c=5&x=A!"
        assertNull(ConnectCode.parse(bad))
    }
}

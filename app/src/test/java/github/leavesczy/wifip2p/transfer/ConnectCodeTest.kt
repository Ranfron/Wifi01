package github.leavesczy.wifip2p.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectCodeTest {

    private val typical = ConnectCode("DIRECT-ab-WifiP2P-x7Kq", "pa55W0rdXyZ9", "192.168.49.1", 1995, "Pixel 8")

    @Test
    fun roundTrip() {
        assertEquals(typical, ConnectCode.parse(typical.encode()))
    }

    @Test
    fun specialCharactersSurvive() {
        val code = ConnectCode("DIRECT-q&=-+ x \u0939\u093F", "p&ss=wo+rd %20", "10.0.0.7", 8080, "Ravi's phone & co=1+1")
        val parsed = ConnectCode.parse(code.encode())
        assertNotNull(parsed)
        assertEquals(code, parsed)
    }

    @Test
    fun nonAsciiPhoneNamesAreReducedToAsciiToKeepTheCodeSmall() {
        val name = "Ravi\u2019s \u0939\u093F\u0928\u094D\u0926\u0940 \uD83D\uDE00 phone"
        val parsed = ConnectCode.parse(ConnectCode("DIRECT-a", "12345678", "10.0.0.1", 1995, name).encode())!!
        assertEquals("Ravis   phone", parsed.deviceName)
        // a purely non-ASCII name leaves an empty name: the real one is exchanged after connecting
        val empty = ConnectCode.parse(ConnectCode("DIRECT-a", "12345678", "10.0.0.1", 1995, "\u0939\u093F").encode())!!
        assertEquals("", empty.deviceName)
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(typical, ConnectCode.parse("  \n" + typical.encode() + "\r\n "))
    }

    @Test
    fun foreignAndBrokenInputIsRejected() {
        val rejected = listOf(
            null, "", " ", "hello", "https://example.com/?a=b", "WIFI:S:DIRECT-ab;T:WPA;P:12345678;;",
            "wifip2p://", "wifip2p://c?", "wifip2p://c?v=1", "wifip2p://x?v=1&s=a&p=12345678&a=10.0.0.1&o=1&n=x",
            "wifip2p://c?v=1&s&p=12345678&a=10.0.0.1&o=1995&n=x", "wifip2p://c?v=1&s=%ZZ&p=12345678&a=10.0.0.1&o=1995&n=x"
        )
        for (text in rejected) {
            assertNull("must reject: $text", ConnectCode.parse(text))
        }
    }

    private fun code(
        v: String = "1", s: String = "DIRECT-ab-test", p: String = "12345678", a: String = "192.168.49.1",
        o: String = "1995", n: String = "Phone", extra: String = ""
    ) = "wifip2p://c?v=$v&s=$s&p=$p&a=$a&o=$o&n=$n$extra"

    @Test
    fun fieldValidation() {
        assertNotNull(ConnectCode.parse(code()))
        assertNotNull("v2 is now supported", ConnectCode.parse(code(v = "2")))
        assertNull("future version", ConnectCode.parse(code(v = "3")))
        assertNull("short passphrase", ConnectCode.parse(code(p = "1234567")))
        assertNull("long passphrase", ConnectCode.parse(code(p = "x".repeat(64))))
        assertNotNull(ConnectCode.parse(code(p = "x".repeat(63))))
        assertNull("non-ascii passphrase", ConnectCode.parse(code(p = "p%C3%A4ssword1")))
        assertNull("empty ssid", ConnectCode.parse(code(s = "")))
        assertNull("ssid over 32 bytes", ConnectCode.parse(code(s = "x".repeat(33))))
        assertNotNull(ConnectCode.parse(code(s = "x".repeat(32))))
        assertNull("control char in ssid", ConnectCode.parse(code(s = "ab%00cd")))
        assertNull("port 0", ConnectCode.parse(code(o = "0")))
        assertNull("port too big", ConnectCode.parse(code(o = "70000")))
        assertNull("port text", ConnectCode.parse(code(o = "abc")))
        assertNull("duplicate key", ConnectCode.parse(code(extra = "&p=87654321")))
        assertNull("too long", ConnectCode.parse(code(n = "x".repeat(500))))
    }

    @Test
    fun onlyLocalNetworkAddressesAreAccepted() {
        for (ok in listOf("192.168.49.1", "192.168.0.254", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.9.9", "169.254.10.10")) {
            assertTrue(ok, ConnectCode.isPrivateIpv4(ok))
            assertNotNull(ok, ConnectCode.parse(code(a = ok)))
        }
        for (bad in listOf("8.8.8.8", "127.0.0.1", "0.0.0.0", "172.15.0.1", "172.32.0.1", "192.169.0.1", "255.255.255.255",
            "300.1.1.1", "192.168.1", "192.168.1.1.1", "a.b.c.d", "", "192.168.-1.1", "224.0.0.1")) {
            assertFalse(bad, ConnectCode.isPrivateIpv4(bad))
            assertNull(bad, ConnectCode.parse(code(a = bad)))
        }
    }

    @Test
    fun controlCharactersInTheNameAreStripped() {
        val parsed = ConnectCode.parse(code(n = "Ph%00o%0Ane%7F"))!!
        assertEquals("Phone", parsed.deviceName)
    }

    @Test
    fun longNamesAreShortened() {
        val parsed = ConnectCode.parse(ConnectCode("DIRECT-a", "12345678", "10.0.0.1", 1995, "n".repeat(200)).encode())!!
        assertEquals(24, parsed.deviceName.length)
    }

    @Test
    fun parserNeverThrowsOnGarbage() {
        val random = java.util.Random(99)
        val alphabet = "wifip2p://c?v=1&s=p=a=o=n=%+-.0123456789abcdefABCDEF\u0939\uD83D\uDE00 \n"
        repeat(5000) {
            val text = buildString { repeat(random.nextInt(120)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val result = ConnectCode.parse(text)
            if (result != null) {
                // anything accepted must satisfy every rule
                assertTrue(ConnectCode.isPrivateIpv4(result.address))
                assertTrue(result.passphrase.length in 8..63)
            }
        }
        repeat(2000) {
            val text = ConnectCode.PREFIX + buildString { repeat(random.nextInt(100)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            ConnectCode.parse(text)
        }
    }
}

package github.leavesczy.wifip2p.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

/** The HELLO carries the QR token of the guest. */
class HelloTokenTest {

    @Test
    fun theTokenSurvivesTheHelloFrame() {
        val hello = Hello(Protocol.ROLE_SENDER, "Phone", token = "k7qm2wz9ab")
        assertEquals(hello, Hello.parse(hello.toFrame()))
    }

    @Test
    fun aHelloWithoutATokenHasAnEmptyOne() {
        val hello = Hello(Protocol.ROLE_RECEIVER, "Phone")
        assertEquals("", Hello.parse(hello.toFrame()).token)
    }
}

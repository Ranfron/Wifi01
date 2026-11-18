package github.leavesczy.wifip2p.transfer

import java.io.IOException
import java.net.Socket

/**
 * The HELLO exchange at the start of every connection. Each connection carries data in ONE
 * direction; the guest (the side that connected) says which one, the host answers with the
 * complementary role. A link uses two connections, so both phones can send and receive.
 */
object Handshake {

    /** Guest: announce [myRole] and verify that the host took the other one. Returns the host's HELLO. */
    @Throws(IOException::class)
    fun asGuest(socket: Socket, myRole: Int, deviceName: String, timeoutMs: Int): Hello {
        val expected = Protocol.complementOf(myRole) ?: throw ProtocolException("Invalid role")
        socket.soTimeout = timeoutMs
        FrameIO.write(socket.getOutputStream(), Hello(myRole, deviceName).toFrame())
        val host = Hello.parse(FrameIO.read(socket.getInputStream()))
        if (host.role != expected) {
            throw ProtocolException("The other phone answered with an unexpected role")
        }
        socket.soTimeout = 0
        return host
    }

    /**
     * Host: read the guest's HELLO first (its role decides ours), ask [admit] whether this guest may
     * connect at all, then answer. Returns the guest's HELLO.
     */
    @Throws(IOException::class)
    fun asHost(socket: Socket, deviceName: String, timeoutMs: Int, admit: () -> Boolean): Hello {
        socket.soTimeout = timeoutMs
        val guest = Hello.parse(FrameIO.read(socket.getInputStream()))
        val myRole = Protocol.complementOf(guest.role) ?: throw ProtocolException("Invalid role")
        if (!admit()) {
            throw ProtocolException("Another phone is already connected")
        }
        FrameIO.write(socket.getOutputStream(), Hello(myRole, deviceName).toFrame())
        socket.soTimeout = 0
        return guest
    }
}

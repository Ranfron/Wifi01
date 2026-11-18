package github.leavesczy.wifip2p.transfer

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps a two-way transfer link alive on top of an existing Wi-Fi network between two phones.
 *
 * A link consists of TWO independent TCP connections, one per direction:
 *  - the SEND channel carries the files this phone sends,
 *  - the RECEIVE channel carries the files this phone receives.
 * Both phones can therefore send and receive at the same time, and cancelling one direction does
 * not disturb the other.
 *
 * Which phone listens ([startHost]) and which one connects ([startGuest]) is only a matter of the
 * network (the Wi-Fi Direct group owner listens); it has nothing to do with who sends. The guest
 * opens both connections and tells the host in the HELLO which direction each one is for.
 *
 * The link heals itself: whenever a channel ends (cancel, error, peer left) the guest connects it
 * again and the host accepts again, until [stop] is called.
 */
class DuplexLink(
    private val deviceName: String,
    private val tuning: Tuning,
    private val sinks: SinkFactory,
    outRaw: SessionEvents,
    inRaw: SessionEvents,
    private val listener: LinkListener
) {
    // The app's callbacks are guarded: an exception in the UI is reported, never allowed to break the link.
    private val outEvents = GuardedEvents(outRaw, ::reportInternal)
    private val inEvents = GuardedEvents(inRaw, ::reportInternal)

    private class Slot {
        val socket = AtomicReference<Socket?>(null)
        val generation = AtomicInteger(0)

        @Volatile
        var cancel = CancelToken()
    }

    private val stopped = AtomicBoolean(false)
    private val sendSlot = Slot()
    private val receiveSlot = Slot()
    private val sender = AtomicReference<SenderSession?>(null)
    private val admission = Admission(tuning.admissionIdleMs)

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wfp2p-send").also { it.isDaemon = true }
    }

    /** Group owner side: wait for the other phone. */
    fun startHost(port: Int = Protocol.PORT) {
        startThread("wfp2p-host") { hostLoop(port) }
    }

    /** Client side: connect both channels to the host at [address]. */
    fun startGuest(address: String, port: Int = Protocol.PORT) {
        startThread("wfp2p-guest-send") { guestLoop(ChannelDirection.SEND, address, port) }
        startThread("wfp2p-guest-receive") { guestLoop(ChannelDirection.RECEIVE, address, port) }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) {
            return
        }
        sendSlot.cancel.cancel()
        receiveSlot.cancel.cancel()
        closeQuietly(serverSocket)
        closeQuietly(sendSlot.socket.get())
        closeQuietly(receiveSlot.socket.get())
        sendExecutor.shutdownNow()
    }

    /**
     * Queues [sources] for sending to the other phone. Returns false when the send channel is not
     * ready. Batches are executed one after the other.
     */
    fun send(sources: List<SendSource>): Boolean {
        if (stopped.get()) {
            return false
        }
        val session = sender.get() ?: return false
        if (session.isClosed) {
            return false
        }
        val cancel = sendSlot.cancel
        try {
            sendExecutor.execute {
                if (!session.isClosed && !cancel.cancelled) {
                    session.sendBatch(sources, cancel)
                }
            }
        } catch (_: RejectedExecutionException) {
            return false
        }
        return true
    }

    /** Stops everything this phone is sending. The channel reconnects a moment later. */
    fun cancelSending() {
        sendSlot.cancel.cancel()
        closeQuietly(sendSlot.socket.get())
    }

    /** Stops everything this phone is receiving. The channel reconnects a moment later. */
    fun cancelReceiving() {
        receiveSlot.cancel.cancel()
        closeQuietly(receiveSlot.socket.get())
    }

    // ---- host ------------------------------------------------------------------------------------

    private fun hostLoop(port: Int) {
        val server = ServerSocket()
        try {
            server.reuseAddress = true // must be set BEFORE bind() to have any effect
            if (tuning.socketBufferSize > 0) {
                server.receiveBufferSize = tuning.socketBufferSize
            }
            bindWithRetry(server, port)
            serverSocket = server
            if (stopped.get()) {
                return
            }
            tell { onListening() }
            while (!stopped.get()) {
                val client = try {
                    server.accept()
                } catch (e: SocketException) {
                    if (stopped.get()) {
                        break
                    }
                    throw e
                }
                spawn("wfp2p-session") { handleIncoming(client) }
            }
        } finally {
            closeQuietly(server)
        }
    }

    private fun bindWithRetry(server: ServerSocket, port: Int) {
        var attempt = 0
        while (true) {
            try {
                server.bind(InetSocketAddress(port))
                return
            } catch (e: IOException) {
                attempt++
                if (attempt >= 6 || stopped.get()) {
                    throw IOException("Cannot listen on port $port: ${e.message}", e)
                }
                sleepQuietly(300)
            }
        }
    }

    private fun handleIncoming(socket: Socket) {
        try {
            SocketOptions.apply(socket, tuning)
            val guest = Handshake.asHost(socket, deviceName, tuning.handshakeTimeoutMs) {
                admission.admit(socket.inetAddress)
            }
            // The guest sends on its SENDER connection: that is where this phone receives, and vice versa.
            if (guest.role == Protocol.ROLE_SENDER) {
                runReceive(socket, guest.deviceName)
            } else {
                runSend(socket, guest.deviceName)
            }
        } catch (_: Throwable) {
            // not admitted, bad HELLO, timeout, or the channel simply ended: drop the connection
        } finally {
            closeQuietly(socket)
        }
    }

    // ---- guest -----------------------------------------------------------------------------------

    private fun guestLoop(direction: ChannelDirection, address: String, port: Int) {
        var attempts = 0
        while (!stopped.get()) {
            val socket = Socket()
            val host: Hello
            try {
                socket.connect(InetSocketAddress(address, port), tuning.connectTimeoutMs)
                SocketOptions.apply(socket, tuning)
                val myRole = if (direction == ChannelDirection.SEND) Protocol.ROLE_SENDER else Protocol.ROLE_RECEIVER
                host = Handshake.asGuest(socket, myRole, deviceName, tuning.handshakeTimeoutMs)
                attempts = 0
            } catch (_: IOException) {
                closeQuietly(socket)
                attempts++
                if (direction == ChannelDirection.SEND && attempts == tuning.unreachableAfterAttempts) {
                    tell { onHostUnreachable() }
                }
                // The host's DHCP lease / listener may need a moment: retry quickly at first.
                sleepQuietly(if (attempts < 10) 600 else 1_500)
                continue
            }
            try {
                if (direction == ChannelDirection.SEND) {
                    runSend(socket, host.deviceName)
                } else {
                    runReceive(socket, host.deviceName)
                }
            } finally {
                closeQuietly(socket)
            }
            if (!stopped.get()) {
                sleepQuietly(800)
            }
        }
    }

    // ---- channels (same code on both sides) -------------------------------------------------------

    /** This phone SENDS on [socket]. Blocks until the connection is gone. */
    private fun runSend(socket: Socket, peerName: String) {
        val slot = sendSlot
        val generation = slot.generation.incrementAndGet()
        fun current() = slot.generation.get() == generation && !stopped.get()

        closeQuietly(slot.socket.getAndSet(socket)) // the newest connection wins
        val cancel = CancelToken()
        slot.cancel = cancel
        val session = SenderSession(socket, tuning, outEvents)
        admission.opened()
        try {
            session.start()
            sender.set(session)
            if (current()) {
                tell { onChannel(ChannelDirection.SEND, true, peerName, null) }
            }
            session.awaitClosed()
            session.awaitIdle(2_000) // let a running batch report why its files failed first
            if (current()) {
                tell { onChannel(ChannelDirection.SEND, false, peerName, "Connection closed") }
            }
        } catch (t: Throwable) {
            if (current()) {
                tell { onChannel(ChannelDirection.SEND, false, peerName, describe(t)) }
            }
        } finally {
            admission.closed()
            sender.compareAndSet(session, null)
            slot.socket.compareAndSet(socket, null)
            closeQuietly(socket)
        }
    }

    /** This phone RECEIVES on [socket]. Blocks until the connection is gone. */
    private fun runReceive(socket: Socket, peerName: String) {
        val slot = receiveSlot
        val generation = slot.generation.incrementAndGet()
        fun current() = slot.generation.get() == generation && !stopped.get()

        closeQuietly(slot.socket.getAndSet(socket))
        val cancel = CancelToken()
        slot.cancel = cancel
        val session = ReceiverSession(socket, sinks, tuning, inEvents)
        admission.opened()
        try {
            if (current()) {
                tell { onChannel(ChannelDirection.RECEIVE, true, peerName, null) }
            }
            session.serve(cancel)
        } catch (t: Throwable) {
            if (current()) {
                tell { onChannel(ChannelDirection.RECEIVE, false, peerName, describe(t)) }
            }
        } finally {
            admission.closed()
            slot.socket.compareAndSet(socket, null)
            closeQuietly(socket)
        }
    }

    private fun describe(t: Throwable): String = if (t is EOFException) "Connection closed" else describeError(t)

    /**
     * Once a phone is connected, a different phone (a bystander who photographed the QR code, for
     * instance) cannot take the link over: connections from another address are refused until the
     * first phone has been gone for [idleMs].
     */
    private class Admission(private val idleMs: Long) {
        private var address: String? = null
        private var open = 0
        private var lastClosedAt = 0L

        @Synchronized
        fun admit(peer: InetAddress?): Boolean {
            val candidate = peer?.hostAddress ?: return false
            val now = System.nanoTime() / 1_000_000
            if (address == null || address == candidate || (open == 0 && now - lastClosedAt > idleMs)) {
                address = candidate
                return true
            }
            return false
        }

        @Synchronized
        fun opened() {
            open++
        }

        @Synchronized
        fun closed() {
            open--
            lastClosedAt = System.nanoTime() / 1_000_000
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Reports a bug that was caught (the connection itself is kept). */
    private fun reportInternal(error: Throwable) {
        try {
            listener.onInternalError(error)
        } catch (_: Throwable) {
        }
    }

    /** Calls the app's link listener; an exception there must not tear down the connection. */
    private inline fun tell(block: LinkListener.() -> Unit) {
        try {
            listener.block()
        } catch (t: Throwable) {
            reportInternal(t)
        }
    }

    private fun startThread(name: String, body: () -> Unit) {
        val thread = Thread({
            try {
                body()
            } catch (t: Throwable) {
                if (!stopped.get()) {
                    tell { onFailed(describeError(t)) }
                }
            }
        }, name)
        thread.isDaemon = true
        thread.start()
    }

    private fun spawn(name: String, body: () -> Unit) {
        val thread = Thread({
            try {
                body()
            } catch (_: Throwable) {
            }
        }, name)
        thread.isDaemon = true
        thread.start()
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

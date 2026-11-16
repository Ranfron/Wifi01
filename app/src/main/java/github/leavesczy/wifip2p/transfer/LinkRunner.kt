package github.leavesczy.wifip2p.transfer

import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps one transfer link alive on top of an existing Wi-Fi Direct group.
 *
 * TCP roles and transfer roles are independent:
 *  - the **group owner** listens ([startHost]) and the other device connects ([startGuest]),
 *  - what the two apps do over that connection (send / receive) is decided by [role] and checked
 *    in the HELLO handshake.
 *
 * So it does not matter which phone Android picked as group owner - the old app only worked
 * when the receiver happened to be the group owner.
 *
 * The link heals itself: whenever the connection ends (cancel, error, peer left) a guest reconnects
 * and a host accepts again, until [stop] is called.
 */
class LinkRunner(
    private val role: Int,
    private val deviceName: String,
    private val tuning: Tuning,
    private val sinks: SinkFactory?,
    private val events: LinkEvents
) {
    private val stopped = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    // Atomic so that a finishing old session can never clobber the references of a newer one.
    private val activeSocket = AtomicReference<Socket?>(null)
    private val senderSession = AtomicReference<SenderSession?>(null)

    @Volatile
    private var connectionCancel = CancelToken()

    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wfp2p-send").also { it.isDaemon = true }
    }

    init {
        require(role == Protocol.ROLE_SENDER || role == Protocol.ROLE_RECEIVER)
        require(role != Protocol.ROLE_RECEIVER || sinks != null) { "A receiver needs a SinkFactory" }
    }

    /** Group owner side: wait for the other device. */
    fun startHost(port: Int = Protocol.PORT) {
        startThread("wfp2p-host") { hostLoop(port) }
    }

    /** Client side: connect to the group owner at [address]. */
    fun startGuest(address: String, port: Int = Protocol.PORT) {
        startThread("wfp2p-guest") { guestLoop(address, port) }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) {
            return
        }
        connectionCancel.cancel()
        closeQuietly(serverSocket)
        closeQuietly(activeSocket.get())
        sendExecutor.shutdownNow()
    }

    /**
     * Queues [sources] for sending. Returns false when this link is not a ready sender.
     * Batches are executed one after the other.
     */
    fun send(sources: List<SendSource>): Boolean {
        if (role != Protocol.ROLE_SENDER || stopped.get()) {
            return false
        }
        val session = senderSession.get() ?: return false
        if (session.isClosed) {
            return false
        }
        val cancel = connectionCancel
        try {
            sendExecutor.execute {
                if (!session.isClosed && !cancel.cancelled) {
                    session.sendBatch(sources, cancel)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return false
        }
        return true
    }

    /**
     * Stops whatever is being transferred right now. The connection is closed (the receiver
     * deletes the partial file) and re-established automatically a moment later.
     */
    fun cancelTransfers() {
        connectionCancel.cancel()
        closeQuietly(activeSocket.get())
    }

    // ---------------------------------------------------------------------------------------------

    private fun startThread(name: String, body: () -> Unit) {
        val thread = Thread({
            try {
                body()
            } catch (t: Throwable) {
                if (!stopped.get()) {
                    events.onLinkStatus(LinkStatus.FAILED, describeError(t))
                }
            }
        }, name)
        thread.isDaemon = true
        thread.start()
    }

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
            events.onLinkStatus(LinkStatus.LISTENING, null)
            var previous: Socket? = null
            while (!stopped.get()) {
                val client = try {
                    server.accept()
                } catch (e: SocketException) {
                    if (stopped.get()) {
                        break
                    }
                    throw e
                }
                // Latest connection wins: a half-open old connection must not block a reconnect.
                val myGeneration = generation.incrementAndGet()
                closeQuietly(previous)
                previous = client
                val thread = Thread({ handleConnection(client, myGeneration) }, "wfp2p-session")
                thread.isDaemon = true
                thread.start()
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

    private fun guestLoop(address: String, port: Int) {
        var attempts = 0
        while (!stopped.get()) {
            val socket = Socket()
            try {
                if (attempts == 0) {
                    events.onLinkStatus(LinkStatus.CONNECTING, null)
                }
                socket.connect(InetSocketAddress(address, port), tuning.connectTimeoutMs)
                attempts = 0
            } catch (e: IOException) {
                closeQuietly(socket)
                attempts++
                if (attempts == UNREACHABLE_AFTER_ATTEMPTS) {
                    events.onLinkStatus(LinkStatus.CONNECTING, UNREACHABLE)
                }
                // The group owner's DHCP lease / listener may need a moment: retry quickly at first.
                sleepQuietly(if (attempts < 10) 600 else 1_500)
                continue
            }
            handleConnection(socket, generation.incrementAndGet())
            if (!stopped.get()) {
                sleepQuietly(800)
            }
        }
    }

    private fun handleConnection(socket: Socket, myGeneration: Int) {
        fun current() = generation.get() == myGeneration && !stopped.get()

        if (!current()) {
            closeQuietly(socket) // a newer connection already took over
            return
        }
        activeSocket.set(socket)
        val cancel = CancelToken()
        connectionCancel = cancel
        var session: SenderSession? = null
        try {
            SocketOptions.apply(socket, tuning)
            if (current()) {
                events.onLinkStatus(LinkStatus.HANDSHAKE, null)
            }
            if (role == Protocol.ROLE_SENDER) {
                val sender = SenderSession(socket, deviceName, tuning, events)
                session = sender
                val peer = sender.handshake()
                senderSession.set(sender)
                if (current()) {
                    events.onLinkStatus(LinkStatus.READY, peer.deviceName)
                }
                sender.awaitClosed()
                sender.awaitIdle(2_000) // let a running batch report why its files failed first
            } else {
                val receiver = ReceiverSession(socket, deviceName, sinks!!, tuning, events)
                val peer = receiver.handshake()
                if (current()) {
                    events.onLinkStatus(LinkStatus.READY, peer.deviceName)
                }
                receiver.serve(cancel)
            }
            if (current()) {
                events.onLinkStatus(LinkStatus.DISCONNECTED, "Connection closed")
            }
        } catch (t: Throwable) {
            if (current()) {
                events.onLinkStatus(
                    LinkStatus.DISCONNECTED,
                    if (t is EOFException) "Connection closed" else describeError(t)
                )
            }
        } finally {
            closeQuietly(socket)
            session?.let { senderSession.compareAndSet(it, null) }
            activeSocket.compareAndSet(socket, null)
        }
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
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

    companion object {
        /**
         * Detail of a [LinkStatus.CONNECTING] event: the group exists but the app on the other
         * side does not answer (usually it is not on its Receive / Send screen).
         */
        const val UNREACHABLE = "unreachable"
        private const val UNREACHABLE_AFTER_ATTEMPTS = 12
    }
}

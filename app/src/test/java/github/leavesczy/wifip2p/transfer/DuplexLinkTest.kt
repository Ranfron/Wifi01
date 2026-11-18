package github.leavesczy.wifip2p.transfer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs the complete two-way link (handshake, both channels, batches, CRC, resume, heartbeats,
 * reconnects) over real TCP sockets.
 */
class DuplexLinkTest {

    private val tuning = Tuning(
        chunkSize = 64 * 1024,
        queueDepth = 4,
        progressIntervalMs = 5,
        stallTimeoutMs = 5_000,
        admissionIdleMs = 600,
        unreachableAfterAttempts = 2,
        heartbeatIntervalMs = 200,
        deadAfterMs = 1_500,
        reconnectMinMs = 100,
        reconnectMaxMs = 500
    )
    private val root: File = java.nio.file.Files.createTempDirectory("wfp2p-test").toFile()
    private val cleanup = ArrayList<() -> Unit>()

    @After
    fun tearDown() {
        cleanup.forEach { it() }
        root.deleteRecursively()
    }

    private fun phone(name: String): Phone {
        val dir = File(root, name).also { it.mkdirs() }
        val p = Phone(name, dir, tuning)
        cleanup.add { p.duplex.stop() }
        return p
    }

    private fun waitFullyConnected(vararg phones: Phone) {
        for (p in phones) {
            p.link.awaitReady(ChannelDirection.SEND)
            p.link.awaitReady(ChannelDirection.RECEIVE)
        }
    }

    /** host = group owner (listens), guest connects. Returns once both channels of both phones are up. */
    private fun connected(): Pair<Phone, Phone> {
        val port = freePort()
        val host = phone("Host-Phone")
        val guest = phone("Guest-Phone")
        host.duplex.startHost(port)
        host.link.await("host listening") { host.link.listening }
        guest.duplex.startGuest("127.0.0.1", port)
        waitFullyConnected(host, guest)
        return host to guest
    }

    private fun sampleFiles(): List<RandomSource> = listOf(
        RandomSource("empty.bin", 0),
        RandomSource("tiny.bin", 1),
        RandomSource("exact-chunk.bin", 64 * 1024),
        RandomSource("odd size.bin", 64 * 1024 * 3 + 17),
        RandomSource("photo.jpg", 5_000_000),
        RandomSource("\u0939\u093F\u0928\u094D\u0926\u0940 \u092B\u093E\u0907\u0932.txt", 12_345)
    )

    private fun verifyDelivered(from: Phone, to: Phone, files: List<RandomSource>) {
        from.sent.awaitFinished(files.size)
        to.received.awaitFinished(files.size)
        for (f in files) {
            val sent = from.sent.finished.first { it.name == f.name }
            assertEquals("sender state of ${f.name}: ${sent.message}", FileState.DONE, sent.state)
            val got = to.received.finished.first { it.name == f.name }
            assertEquals("receiver state of ${f.name}: ${got.message}", FileState.DONE, got.state)
            val stored = File(got.location!!)
            assertEquals("size of ${f.name}", f.size, stored.length())
            assertEquals("content of ${f.name}", f.sha256(), sha256(stored))
        }
    }

    // ---- both phones can send and receive --------------------------------------------------------

    @Test
    fun guestSendsToHost_bitPerfect() {
        val (host, guest) = connected()
        val files = sampleFiles()
        assertTrue(guest.duplex.send(files))
        verifyDelivered(guest, host, files)
        guest.sent.await("batch end", 5_000) { guest.sent.batchesFinished == 1 && host.received.batchesFinished == 1 }
        val last = guest.sent.progress.last()
        assertEquals(last.batchTotal, last.batchBytes)
    }

    @Test
    fun hostSendsToGuest_bitPerfect() {
        val (host, guest) = connected()
        val files = sampleFiles()
        assertTrue(host.duplex.send(files))
        verifyDelivered(host, guest, files)
    }

    @Test
    fun bothPhonesSendAtTheSameTime() {
        val (host, guest) = connected()
        val fromHost = listOf(RandomSource("from-host-1.bin", 6_000_000), RandomSource("from-host-2.bin", 777_777))
        val fromGuest = listOf(RandomSource("from-guest-1.bin", 5_000_000), RandomSource("from-guest-2.bin", 123_456))
        assertTrue(host.duplex.send(fromHost))
        assertTrue(guest.duplex.send(fromGuest))
        verifyDelivered(host, guest, fromHost)
        verifyDelivered(guest, host, fromGuest)
        assertEquals(1, host.link.readyCount(ChannelDirection.SEND))
        assertEquals(1, host.link.readyCount(ChannelDirection.RECEIVE))
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))
        assertEquals(1, guest.link.readyCount(ChannelDirection.RECEIVE))
    }

    @Test
    fun phonesKnowEachOthersNames() {
        val (host, guest) = connected()
        assertEquals("Guest-Phone", host.link.peerName(ChannelDirection.SEND))
        assertEquals("Guest-Phone", host.link.peerName(ChannelDirection.RECEIVE))
        assertEquals("Host-Phone", guest.link.peerName(ChannelDirection.SEND))
        assertEquals("Host-Phone", guest.link.peerName(ChannelDirection.RECEIVE))
    }

    @Test
    fun severalBatchesOnTheSameConnection() {
        val (host, guest) = connected()
        assertTrue(guest.duplex.send(listOf(RandomSource("one.bin", 300_000))))
        assertTrue(guest.duplex.send(listOf(RandomSource("two.bin", 200_000), RandomSource("three.bin", 100_000))))
        guest.sent.awaitFinished(3)
        host.received.awaitFinished(3)
        assertTrue(guest.sent.finished.all { it.state == FileState.DONE })
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))
    }

    // ---- cancelling one direction does not disturb the other -------------------------------------

    @Test
    fun cancelSendingKeepsTheOtherDirectionAlive() {
        val (host, guest) = connected()
        val slowUp = RandomSource("guest-to-host.bin", 200_000_000, throttleBytesPerSecond = 40_000_000)
        val downloads = listOf(RandomSource("host-to-guest.bin", 30_000_000, throttleBytesPerSecond = 30_000_000))
        guest.duplex.send(listOf(slowUp, RandomSource("never-sent.bin", 1000)))
        host.duplex.send(downloads)
        guest.sent.await("upload progress", 10_000) { guest.sent.progress.any { it.fileBytes > 3_000_000 } }
        guest.duplex.cancelSending()

        guest.sent.awaitFinished(2, 10_000)
        assertTrue(guest.sent.finished.all { it.state == FileState.CANCELLED })
        host.received.await("partial upload kept", 10_000) { host.store.partials().isNotEmpty() }
        assertTrue("nothing is published before the file is complete", host.dir.listFiles()!!.isEmpty())

        verifyDelivered(host, guest, downloads)
        assertEquals("the receive channel must not have reconnected", 1, guest.link.readyCount(ChannelDirection.RECEIVE))

        guest.link.awaitReady(ChannelDirection.SEND, count = 2)
        val small = RandomSource("after-cancel.bin", 250_000)
        assertTrue(guest.duplex.send(listOf(small)))
        host.received.await("new file", 10_000) { host.received.finished.any { it.name == "after-cancel.bin" } }
        assertEquals(small.sha256(), sha256(File(host.dir, "after-cancel.bin")))
    }

    @Test
    fun cancelReceivingOnlyStopsThatDirection() {
        val (host, guest) = connected()
        host.duplex.send(listOf(RandomSource("huge.bin", 200_000_000, throttleBytesPerSecond = 40_000_000)))
        guest.received.await("download progress", 10_000) { guest.received.progress.any { it.fileBytes > 3_000_000 } }
        guest.duplex.cancelReceiving()

        guest.received.await("receiver noticed", 10_000) { guest.received.finished.isNotEmpty() }
        assertEquals(FileState.CANCELLED, guest.received.finished.first().state)
        guest.received.await("partial download kept", 5_000) { guest.store.partials().isNotEmpty() }
        assertTrue(guest.dir.listFiles()!!.isEmpty())
        host.sent.await("sender noticed", 10_000) { host.sent.finished.isNotEmpty() }
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))

        guest.link.awaitReady(ChannelDirection.RECEIVE, count = 2)
        val small = RandomSource("again.bin", 100_000)
        assertTrue(host.duplex.send(listOf(small)))
        guest.received.await("new file", 10_000) { guest.received.finished.any { it.name == "again.bin" } }
        assertEquals(small.sha256(), sha256(File(guest.dir, "again.bin")))
    }

    // ---- resume: an interrupted file continues where it stopped ----------------------------------

    @Test
    fun cutLinkMidFileResumesFromTheStoredBytes() {
        val port = freePort()
        val host = phone("Host-Phone")
        host.duplex.startHost(port)
        host.link.await("host listening") { host.link.listening }
        val proxy = CuttableProxy(port)
        cleanup.add { proxy.close() }
        val guest = phone("Guest-Phone")
        guest.duplex.startGuest("127.0.0.1", proxy.port)
        waitFullyConnected(host, guest)

        val file = RandomSource("big.bin", 12_000_000, throttleBytesPerSecond = 8_000_000, key = "big-key")
        guest.duplex.send(listOf(file))
        host.received.await("first part stored", 15_000) { host.store.partials().any { it.stored > 2_000_000 } }

        proxy.cutAll() // the Wi-Fi link breaks, for example during a call
        guest.sent.awaitState("big.bin", FileState.INTERRUPTED)
        host.received.await("partial kept after the break", 10_000) { host.store.partials().isNotEmpty() }
        val stored = host.store.partials().single().stored
        assertTrue("partial data must be partial", stored > 0 && stored < file.size)

        // the phones meet again by themselves
        guest.link.awaitReady(ChannelDirection.SEND, count = 2)
        host.link.awaitReady(ChannelDirection.RECEIVE, count = 2)
        val before = guest.sent.progress.size
        assertTrue(guest.duplex.send(listOf(file)))
        guest.sent.awaitFinished(2)
        host.received.awaitFinished(2)
        assertEquals(FileState.DONE, guest.sent.finished.last().state)

        // the second attempt started above the stored bytes: the first part was not sent again
        val firstOfResume = guest.sent.progress.subList(before, guest.sent.progress.size).first()
        assertTrue("resumed at ${firstOfResume.fileBytes}, stored $stored", firstOfResume.fileBytes >= stored)
        assertEquals(file.sha256(), sha256(File(host.dir, "big.bin")))
        assertTrue("no partial data is left behind", host.store.partials().isEmpty())
    }

    @Test
    fun storageFailureKeepsThePartialFileAndTheConnection() {
        val (host, guest) = connected()
        host.store.writeFailuresLeft = 1
        host.store.writeFailAfter = 100_000
        guest.duplex.send(listOf(RandomSource("big.bin", 1_000_000), RandomSource("small.bin", 50_000)))
        guest.sent.awaitFinished(2)
        host.received.awaitFinished(2)
        val big = guest.sent.finished.first { it.name == "big.bin" }
        assertEquals(FileState.FAILED, big.state)
        assertTrue(big.message!!.contains("No space left"))
        assertTrue("the stored part is kept, so the file can resume", host.store.partials().any { it.name == "big.bin" })
        assertEquals(FileState.DONE, guest.sent.finished.first { it.name == "small.bin" }.state)
        assertEquals(FileState.DONE, host.received.finished.first { it.name == "small.bin" }.state)
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))
    }

    // ---- connection health -----------------------------------------------------------------------

    @Test
    fun heartbeatsKeepAnIdleLinkAlive() {
        val (host, guest) = connected()
        Thread.sleep(3_500) // more than twice the dead-peer timeout
        assertTrue(guest.link.channels.none { !it.ready })
        assertTrue(host.link.channels.none { !it.ready })
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))
        val files = listOf(RandomSource("after-idle.bin", 300_000))
        assertTrue(guest.duplex.send(files))
        verifyDelivered(guest, host, files)
    }

    @Test
    fun silentPeerIsDetectedByHeartbeatAndTheLinkIsDropped() {
        val port = freePort()
        val silent = ServerSocket(port)
        cleanup.add { silent.close() }
        Thread {
            try {
                while (true) {
                    val s = silent.accept()
                    Thread {
                        try {
                            val guestHello = Hello.parse(FrameIO.read(s.getInputStream()))
                            FrameIO.write(
                                s.getOutputStream(),
                                Hello(Protocol.complementOf(guestHello.role)!!, "Silent").toFrame()
                            )
                            Thread.sleep(60_000) // answers the handshake, then says nothing at all
                        } catch (_: Exception) {
                        }
                    }.also { it.isDaemon = true }.start()
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()

        val guest = phone("Guest-Phone")
        guest.duplex.startGuest("127.0.0.1", port)
        guest.link.awaitReady(ChannelDirection.SEND)
        guest.link.await("silent peer detected", 10_000) {
            guest.link.channels.any { !it.ready && it.direction == ChannelDirection.SEND }
        }
    }

    // ---- integrity and failures ------------------------------------------------------------------

    @Test
    fun corruptionInTransitIsDetectedAndOnlyThatFileFails() {
        val port = freePort()
        val host = phone("Host")
        val guest = phone("Guest")
        val proxy = CorruptingProxy(port, flipAtByte = 100_000) // lands inside the first uploaded file
        cleanup.add { proxy.close() }
        host.duplex.startHost(port)
        host.link.await("host listening") { host.link.listening }
        guest.duplex.startGuest("127.0.0.1", proxy.port)
        waitFullyConnected(host, guest)

        val a = RandomSource("damaged.bin", 500_000)
        val b = RandomSource("healthy.bin", 400_000)
        guest.duplex.send(listOf(a, b))
        guest.sent.awaitFinished(2)
        host.received.awaitFinished(2)

        val sa = guest.sent.finished.first { it.name == "damaged.bin" }
        assertEquals(FileState.FAILED, sa.state)
        assertTrue(sa.message!!.contains("Checksum"))
        assertEquals(FileState.DONE, guest.sent.finished.first { it.name == "healthy.bin" }.state)
        assertFalse("damaged data must not be kept", host.store.partials().any { it.name == "damaged.bin" })
        assertFalse(File(host.dir, "damaged.bin").exists())
        assertEquals(b.sha256(), sha256(File(host.dir, "healthy.bin")))
    }

    @Test
    fun duplicateNamesAreNotOverwritten() {
        val (host, guest) = connected()
        guest.duplex.send(listOf(RandomSource("same.txt", 1000, seed = 1), RandomSource("same.txt", 1000, seed = 2)))
        host.received.awaitFinished(2)
        assertEquals(listOf("same (1).txt", "same.txt"), host.store.publisher.committed.map { it.name }.sorted())
    }

    @Test
    fun maliciousFileNameCannotEscapeTheTargetDirectory() {
        val (host, guest) = connected()
        guest.duplex.send(listOf(MemorySource("../../../tmp/pwned.txt", "hi".toByteArray())))
        host.received.awaitFinished(1)
        val stored = host.store.publisher.committed.single()
        assertEquals("pwned.txt", stored.name)
        assertEquals(host.dir.canonicalPath, stored.parentFile.canonicalPath)
    }

    @Test
    fun batchLargerThanFreeSpaceIsRefusedWithoutBreakingTheConnection() {
        val (host, guest) = connected()
        host.store.available = 10_000
        guest.duplex.send(listOf(RandomSource("big.bin", 1_000_000)))
        guest.sent.awaitFinished(1)
        val r = guest.sent.finished.single()
        assertEquals(FileState.FAILED, r.state)
        assertTrue(r.message!!.contains("Not enough storage"))
        assertTrue(host.dir.listFiles()!!.isEmpty())

        host.store.available = Long.MAX_VALUE
        guest.duplex.send(listOf(RandomSource("fits.bin", 5000)))
        guest.sent.awaitFinished(2)
        assertEquals(FileState.DONE, guest.sent.finished.last().state)
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))
    }

    @Test
    fun unreadableSourceOnlyFailsThatFile() {
        val (_, guest) = connected()
        val files = listOf(
            RandomSource("ok1.bin", 5000),
            RandomSource("locked.bin", 5000, failOpen = true),
            RandomSource("ok2.bin", 5000)
        )
        guest.duplex.send(files)
        guest.sent.awaitFinished(3)
        assertEquals(FileState.DONE, guest.sent.finished.first { it.name == "ok1.bin" }.state)
        val locked = guest.sent.finished.first { it.name == "locked.bin" }
        assertEquals(FileState.FAILED, locked.state)
        assertTrue(locked.message!!.contains("permission denied"))
        assertEquals(FileState.DONE, guest.sent.finished.first { it.name == "ok2.bin" }.state)
    }

    @Test
    fun receiverThatCannotCreateAFileKeepsTheConnectionForTheNextOne() {
        val (host, guest) = connected()
        host.store.failOpen = "disk full"
        guest.duplex.send(listOf(RandomSource("x.bin", 300_000), RandomSource("y.bin", 100)))
        guest.sent.awaitFinished(2)
        assertTrue(guest.sent.finished.all { it.state == FileState.FAILED })
        assertTrue(guest.sent.finished.first().message!!.contains("disk full"))
        host.store.failOpen = null
        guest.duplex.send(listOf(RandomSource("z.bin", 1000)))
        guest.sent.awaitFinished(3)
        assertEquals(FileState.DONE, guest.sent.finished.last().state)
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))
    }

    @Test
    fun manySmallFilesAllArrive() {
        val (host, guest) = connected()
        val files = (1..300).map { RandomSource("small-$it.bin", 500L + it * 7, seed = it.toLong()) }
        val started = System.nanoTime()
        assertTrue(guest.duplex.send(files))
        guest.sent.awaitFinished(files.size, 60_000)
        host.received.awaitFinished(files.size, 60_000)
        val seconds = (System.nanoTime() - started) / 1e9
        println("      300 small files in %.2f s (%.0f files/s)".format(seconds, files.size / seconds))
        assertTrue(guest.sent.finished.all { it.state == FileState.DONE })
        assertEquals(files.size, host.store.publisher.committed.size)
        for (i in listOf(0, 99, 299)) {
            assertEquals(files[i].sha256(), sha256(File(host.dir, files[i].name)))
        }
    }

    // ---- connection behaviour --------------------------------------------------------------------

    @Test
    fun sendWithoutConnectionIsRejectedImmediately() {
        val lonely = phone("Lonely")
        assertFalse(lonely.duplex.send(listOf(MemorySource("a", ByteArray(1)))))
    }

    @Test
    fun guestKeepsRetryingUntilTheHostAppears() {
        val port = freePort()
        val guest = phone("Guest")
        guest.duplex.startGuest("127.0.0.1", port) // nobody is listening yet
        Thread.sleep(1500)
        assertEquals(0, guest.link.readyCount(ChannelDirection.SEND))
        val host = phone("Host")
        host.duplex.startHost(port)
        waitFullyConnected(host, guest)
    }

    @Test
    fun unreachableHostIsReportedToTheUser() {
        val guest = phone("Guest")
        guest.duplex.startGuest("127.0.0.1", freePort()) // closed port
        guest.link.await("unreachable hint", 10_000) { guest.link.unreachable >= 1 }
        assertEquals(0, guest.link.readyCount(ChannelDirection.SEND))
    }

    @Test
    fun secondPhoneCannotTakeOverWhileTheFirstIsConnected() {
        val port = freePort()
        val host = phone("Host-Phone")
        host.duplex.startHost(port)
        host.link.await("host listening") { host.link.listening }
        val guest = phone("Guest-Phone")
        guest.duplex.startGuest("127.0.0.1", port)
        waitFullyConnected(host, guest)

        // a bystander on another address (the loopback range has more than one address)
        val intruder = Socket()
        intruder.bind(InetSocketAddress("127.0.0.2", 0))
        intruder.connect(InetSocketAddress("127.0.0.1", port), 3_000)
        intruder.soTimeout = 5_000
        FrameIO.write(intruder.getOutputStream(), Hello(Protocol.ROLE_SENDER, "Intruder").toFrame())
        val dropped = try {
            FrameIO.read(intruder.getInputStream())
            false
        } catch (_: IOException) {
            true
        }
        assertTrue("the host must not answer a second phone", dropped)
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))
        assertEquals(1, guest.link.readyCount(ChannelDirection.RECEIVE))
        intruder.close()

        guest.duplex.stop()
        Thread.sleep(1200)
        val second = Socket()
        second.bind(InetSocketAddress("127.0.0.2", 0))
        second.connect(InetSocketAddress("127.0.0.1", port), 3_000)
        val answer = Handshake.asGuest(second, Protocol.ROLE_SENDER, "Second", 5_000)
        assertEquals("Host-Phone", answer.deviceName)
        second.close()
    }

    // ---- hostile or buggy peers ------------------------------------------------------------------

    private class RogueGuest(port: Int, role: Int = Protocol.ROLE_SENDER) : java.io.Closeable {
        val socket = Socket("127.0.0.1", port)
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        init {
            socket.soTimeout = 5_000
            FrameIO.write(out, Hello(role, "rogue").toFrame())
        }

        fun readHello(): Hello = Hello.parse(FrameIO.read(input))

        fun isDroppedByHost(): Boolean {
            return try {
                while (true) {
                    if (input.read() < 0) return true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } catch (_: IOException) {
                true
            }
        }

        override fun close() = socket.close()
    }

    private fun startHost(): Pair<Phone, Int> {
        val port = freePort()
        val host = phone("R")
        host.duplex.startHost(port)
        host.link.await("listening") { host.link.listening }
        return host to port
    }

    @Test
    fun fileWithoutAnnouncedBatchIsRejected() {
        val (host, port) = startHost()
        RogueGuest(port).use { rogue ->
            rogue.readHello()
            FrameIO.write(rogue.out, FileHeader(0, "x.bin", 10, "x", 0).toFrame())
            assertTrue(rogue.isDroppedByHost())
        }
        host.link.await("receive channel closed", 5_000) {
            host.link.channels.any { it.direction == ChannelDirection.RECEIVE && !it.ready && it.detail!!.contains("announced") }
        }
        assertTrue(host.dir.listFiles()!!.isEmpty())
    }

    @Test
    fun fileBiggerThanTheAnnouncedBatchIsRejected() {
        val (host, port) = startHost()
        RogueGuest(port).use { rogue ->
            rogue.readHello()
            FrameIO.write(rogue.out, BatchInfo(1, 100).toFrame())
            assertTrue(BatchReply.parse(FrameIO.read(rogue.input)).accepted)
            FrameIO.write(rogue.out, FileHeader(0, "huge.bin", 1_000_000, "x", 0).toFrame())
            assertTrue(rogue.isDroppedByHost())
        }
        host.link.await("receive channel closed", 5_000) {
            host.link.channels.any { it.direction == ChannelDirection.RECEIVE && !it.ready && it.detail!!.contains("announced") }
        }
        assertTrue(host.dir.listFiles()!!.isEmpty())
    }

    @Test
    fun moreFilesThanAnnouncedAreRejected() {
        val (host, port) = startHost()
        val payload = ByteArray(10) { it.toByte() }
        val crc = java.util.zip.CRC32().also { it.update(payload) }
        RogueGuest(port).use { rogue ->
            rogue.readHello()
            FrameIO.write(rogue.out, BatchInfo(1, 100).toFrame())
            assertTrue(BatchReply.parse(FrameIO.read(rogue.input)).accepted)
            FrameIO.write(rogue.out, FileHeader(0, "one.bin", 10, "x", 0).toFrame())
            assertTrue(FileStart.parse(FrameIO.read(rogue.input)).accepted)
            rogue.out.write(payload)
            FrameIO.write(rogue.out, FileEnd(crc.value).toFrame())
            assertTrue(FileAck.parse(FrameIO.read(rogue.input)).ok)
            FrameIO.write(rogue.out, FileHeader(1, "two.bin", 10, "x", 0).toFrame())
            assertTrue(rogue.isDroppedByHost())
        }
        host.link.await("receive channel closed", 5_000) {
            host.link.channels.any { it.direction == ChannelDirection.RECEIVE && !it.ready && it.detail!!.contains("announced") }
        }
        assertEquals(listOf("one.bin"), host.dir.listFiles()!!.map { it.name })
    }

    @Test
    fun hostAnswersWithTheComplementaryRole() {
        val (_, port) = startHost()
        RogueGuest(port, Protocol.ROLE_RECEIVER).use { rogue ->
            val hello = rogue.readHello()
            assertEquals(Protocol.ROLE_SENDER, hello.role)
            assertEquals("R", hello.deviceName)
        }
        RogueGuest(port, Protocol.ROLE_SENDER).use { rogue ->
            assertEquals(Protocol.ROLE_RECEIVER, rogue.readHello().role)
        }
    }

    @Test
    fun invalidRoleIsRejected() {
        val (_, port) = startHost()
        RogueGuest(port, role = 0).use { rogue ->
            assertTrue(rogue.isDroppedByHost())
        }
    }

    @Test
    fun stalledReceiverIsDetectedAndTheFileIsInterrupted() {
        // A host that completes the handshake, accepts the batch, and then goes silent for good.
        val port = freePort()
        val server = ServerSocket(port)
        cleanup.add { server.close() }
        Thread {
            try {
                while (true) {
                    val s = server.accept()
                    Thread {
                        try {
                            val guest = Hello.parse(FrameIO.read(s.getInputStream()))
                            FrameIO.write(s.getOutputStream(), Hello(Protocol.complementOf(guest.role)!!, "Frozen").toFrame())
                            if (guest.role == Protocol.ROLE_SENDER) {
                                FrameIO.read(s.getInputStream()) // BATCH
                                FrameIO.write(s.getOutputStream(), BatchReply(true, "").toFrame())
                            }
                            Thread.sleep(60_000)
                        } catch (_: Exception) {
                        }
                    }.also { it.isDaemon = true }.start()
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()

        val guest = phone("Guest")
        guest.duplex.startGuest("127.0.0.1", port)
        guest.link.awaitReady(ChannelDirection.SEND)
        val started = System.currentTimeMillis()
        guest.duplex.send(listOf(RandomSource("blocked.bin", 500_000_000)))
        guest.sent.awaitFinished(1, 20_000)
        val took = System.currentTimeMillis() - started
        val result = guest.sent.finished.single()
        assertEquals(FileState.INTERRUPTED, result.state)
        assertTrue("silence should be noticed after ~1.5 s, took $took ms", took in 500..15_000)
    }
}

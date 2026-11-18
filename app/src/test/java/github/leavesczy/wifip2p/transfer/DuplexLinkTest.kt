package github.leavesczy.wifip2p.transfer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs the complete two-way link (handshake, both channels, batches, CRC, cancel, reconnect) over
 * real TCP sockets. Small chunk sizes are used on purpose so that every file spans many chunks.
 */
class DuplexLinkTest {

    private val tuning = Tuning(
        chunkSize = 64 * 1024, queueDepth = 4, progressIntervalMs = 5, stallTimeoutMs = 5_000,
        admissionIdleMs = 600, unreachableAfterAttempts = 2
    )
    private val root: File = java.nio.file.Files.createTempDirectory("wfp2p-test").toFile()
    private val cleanup = ArrayList<() -> Unit>()

    @After
    fun tearDown() {
        cleanup.forEach { it() }
        root.deleteRecursively()
    }

    private fun phone(name: String, sinks: DirSinks? = null): Phone {
        val dir = File(root, name).also { it.mkdirs() }
        val p = if (sinks != null) Phone(name, dir, tuning, sinks) else Phone(name, dir, tuning)
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
    private fun connected(hostSinks: DirSinks? = null): Pair<Phone, Phone> {
        val port = freePort()
        val host = phone("Host-Phone", hostSinks)
        val guest = phone("Guest-Phone")
        hostPorts[host] = port
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
        // neither direction had to reconnect
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
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND)) // never reconnected
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
        host.received.await("host noticed", 10_000) { host.received.finished.isNotEmpty() }
        assertTrue(host.sinks.committed.isEmpty())
        host.received.await("partial upload deleted", 5_000) { host.dir.listFiles()!!.isEmpty() }

        // the download in the opposite direction finished untouched
        verifyDelivered(host, guest, downloads)
        assertEquals("the receive channel must not have reconnected", 1, guest.link.readyCount(ChannelDirection.RECEIVE))

        // the send channel heals itself and works again
        guest.link.awaitReady(ChannelDirection.SEND, count = 2, timeoutMs = 15_000)
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
        guest.received.await("partial download deleted", 5_000) { guest.dir.listFiles()!!.isEmpty() }
        host.sent.await("sender noticed", 10_000) { host.sent.finished.isNotEmpty() }
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))

        guest.link.awaitReady(ChannelDirection.RECEIVE, count = 2, timeoutMs = 15_000)
        val small = RandomSource("again.bin", 100_000)
        assertTrue(host.duplex.send(listOf(small)))
        guest.received.await("new file", 10_000) { guest.received.finished.any { it.name == "again.bin" } }
        assertEquals(small.sha256(), sha256(File(guest.dir, "again.bin")))
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
        assertFalse("damaged partial file must not be kept", File(host.dir, "damaged.bin").exists())
        assertEquals(b.sha256(), sha256(File(host.dir, "healthy.bin")))
    }

    @Test
    fun duplicateNamesAreNotOverwritten() {
        val (host, guest) = connected()
        guest.duplex.send(listOf(RandomSource("same.txt", 1000, seed = 1), RandomSource("same.txt", 1000, seed = 2)))
        host.received.awaitFinished(2)
        assertEquals(listOf("same (1).txt", "same.txt"), host.sinks.committed.map { it.name }.sorted())
    }

    @Test
    fun maliciousFileNameCannotEscapeTheTargetDirectory() {
        val (host, guest) = connected()
        guest.duplex.send(listOf(MemorySource("../../../tmp/pwned.txt", "hi".toByteArray())))
        host.received.awaitFinished(1)
        val stored = host.sinks.committed.single()
        assertEquals("pwned.txt", stored.name)
        assertEquals(host.dir.canonicalPath, stored.parentFile.canonicalPath)
    }

    @Test
    fun batchLargerThanFreeSpaceIsRefusedWithoutBreakingTheConnection() {
        val (host, guest) = connected()
        host.sinks.available = 10_000
        guest.duplex.send(listOf(RandomSource("big.bin", 1_000_000)))
        guest.sent.awaitFinished(1)
        val r = guest.sent.finished.single()
        assertEquals(FileState.FAILED, r.state)
        assertTrue(r.message!!.contains("Not enough storage"))
        assertTrue(host.dir.listFiles()!!.isEmpty())

        host.sinks.available = Long.MAX_VALUE
        guest.duplex.send(listOf(RandomSource("fits.bin", 5000)))
        guest.sent.awaitFinished(2)
        assertEquals(FileState.DONE, guest.sent.finished.last().state)
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND)) // same connection was reused
    }

    @Test
    fun unreadableSourceOnlyFailsThatFile() {
        val (_, guest) = connected()
        val files = listOf(RandomSource("ok1.bin", 5000), RandomSource("locked.bin", 5000, failOpen = true), RandomSource("ok2.bin", 5000))
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
        host.sinks.failCreate = true
        guest.duplex.send(listOf(RandomSource("x.bin", 300_000), RandomSource("y.bin", 100)))
        guest.sent.awaitFinished(2)
        assertTrue(guest.sent.finished.all { it.state == FileState.FAILED })
        assertTrue(guest.sent.finished.first().message!!.contains("disk full"))
        host.sinks.failCreate = false
        guest.duplex.send(listOf(RandomSource("z.bin", 1000)))
        guest.sent.awaitFinished(3)
        assertEquals(FileState.DONE, guest.sent.finished.last().state)
        // the same connection carried all of it: nothing was torn down
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
        assertEquals(files.size, host.sinks.committed.size)
        for (i in listOf(0, 99, 299)) {
            assertEquals(files[i].sha256(), sha256(File(host.dir, files[i].name)))
        }
    }

    // ---- connection behaviour --------------------------------------------------------------------

    @Test
    fun sendWithoutConnectionIsRejectedImmediately() {
        val phone = phone("Lonely")
        assertFalse(phone.duplex.send(listOf(MemorySource("a", ByteArray(1)))))
    }

    @Test
    fun guestKeepsRetryingUntilTheHostAppears() {
        val port = freePort()
        val guest = phone("Guest")
        guest.duplex.startGuest("127.0.0.1", port) // nobody is listening yet (DHCP / listener not up)
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
        val (host, guest) = connected()
        val port = hostPort(host)
        // a bystander: same machine, different source address
        val intruder = Socket()
        intruder.bind(InetSocketAddress("127.0.0.2", 0))
        intruder.connect(InetSocketAddress("127.0.0.1", port), 3_000)
        intruder.soTimeout = 5_000
        FrameIO.write(intruder.getOutputStream(), Hello(Protocol.ROLE_SENDER, "Intruder").toFrame())
        val dropped = try {
            FrameIO.read(intruder.getInputStream())
            false
        } catch (_: java.io.IOException) {
            true
        }
        assertTrue("the host must not answer a second phone", dropped)
        // the real peer was not disturbed
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))
        assertEquals(1, guest.link.readyCount(ChannelDirection.RECEIVE))
        intruder.close()

        // after the first phone is gone for a while, somebody else may connect
        guest.duplex.stop()
        Thread.sleep(1200)
        val second = Socket()
        second.bind(InetSocketAddress("127.0.0.2", 0))
        second.connect(InetSocketAddress("127.0.0.1", port), 3_000)
        val answer = Handshake.asGuest(second, Protocol.ROLE_SENDER, "Second", 5_000)
        assertEquals("Host-Phone", answer.deviceName)
        second.close()
    }

    private fun hostPort(host: Phone): Int = hostPorts[host]!!
    private val hostPorts = HashMap<Phone, Int>()

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
            } catch (_: java.io.IOException) {
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
    fun stalledReceiverIsDetectedByTheSenderWatchdog() {
        // A host that completes the handshake but then never reads the data of the files it is sent.
        val port = freePort()
        val server = java.net.ServerSocket(port)
        val frozen = Thread {
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
                            Thread.sleep(60_000) // ...and never read the data
                        } catch (_: Exception) {
                        }
                    }.also { it.isDaemon = true }.start()
                }
            } catch (_: Exception) {
            }
        }
        frozen.isDaemon = true
        frozen.start()
        val guest = phone("Guest")
        cleanup.add { server.close() }
        guest.duplex.startGuest("127.0.0.1", port)
        guest.link.awaitReady(ChannelDirection.SEND)
        val started = System.currentTimeMillis()
        guest.duplex.send(listOf(RandomSource("blocked.bin", 500_000_000)))
        guest.sent.awaitFinished(1, 20_000)
        val took = System.currentTimeMillis() - started
        assertEquals(FileState.FAILED, guest.sent.finished.single().state)
        assertTrue("watchdog should fire after ~5 s, took $took ms", took in 3_000..15_000)
    }

    // ---- one bad file never takes the connection down --------------------------------------------

    @Test
    fun sourceThatIsShorterThanItsSizeFailsOnlyThatFile() {
        val (host, guest) = connected()
        guest.duplex.send(listOf(ShortSource("short.bin", 300_000, 120_000), RandomSource("after.bin", 200_000)))
        guest.sent.awaitFinished(2)
        host.received.awaitFinished(2)
        val short = guest.sent.finished.first { it.name == "short.bin" }
        assertEquals(FileState.FAILED, short.state)
        assertTrue(short.message!!.contains("Could not read"))
        assertEquals(FileState.FAILED, host.received.finished.first { it.name == "short.bin" }.state)
        assertTrue(host.dir.listFiles()!!.none { it.name == "short.bin" })
        assertEquals(FileState.DONE, guest.sent.finished.first { it.name == "after.bin" }.state)
        assertEquals(FileState.DONE, host.received.finished.first { it.name == "after.bin" }.state)
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))
    }

    @Test
    fun diskFullWhileReceivingKeepsTheConnectionForTheNextFile() {
        val (host, guest) = connected(FlakySinks(File(root, "Host-Phone"), 100_000, 1))
        guest.duplex.send(listOf(RandomSource("big.bin", 1_000_000), RandomSource("small.bin", 50_000)))
        guest.sent.awaitFinished(2)
        host.received.awaitFinished(2)
        val big = guest.sent.finished.first { it.name == "big.bin" }
        assertEquals(FileState.FAILED, big.state)
        assertTrue(big.message!!.contains("No space left"))
        assertTrue(host.dir.listFiles()!!.none { it.name == "big.bin" })
        assertEquals(FileState.DONE, guest.sent.finished.first { it.name == "small.bin" }.state)
        assertEquals(FileState.DONE, host.received.finished.first { it.name == "small.bin" }.state)
        assertEquals(1, guest.link.readyCount(ChannelDirection.SEND))
    }

    @Test
    fun aBugInTheUserInterfaceDoesNotBreakTheConnection() {
        val port = freePort()
        val host = phone("Host-Phone")
        host.duplex.startHost(port)
        host.link.await("host listening") { host.link.listening }
        val sentRecorder = RecordingEvents()
        val buggyEvents = object : SessionEvents by sentRecorder {
            override fun onProgress(progress: FileProgress) {
                throw IllegalStateException("UI bug")
            }
        }
        val guestLink = RecordingLink()
        val guestDir = File(root, "Guest-Phone").also { it.mkdirs() }
        val guest = DuplexLink("Guest-Phone", tuning, DirSinks(guestDir), buggyEvents, RecordingEvents(), guestLink)
        cleanup.add { guest.stop() }
        guest.startGuest("127.0.0.1", port)
        guestLink.awaitReady(ChannelDirection.SEND)
        guestLink.awaitReady(ChannelDirection.RECEIVE)
        host.link.awaitReady(ChannelDirection.SEND)
        host.link.awaitReady(ChannelDirection.RECEIVE)
        val file = RandomSource("with-bug.bin", 3_000_000)
        assertTrue(guest.send(listOf(file)))
        sentRecorder.awaitFinished(1)
        host.received.awaitFinished(1)
        assertEquals(FileState.DONE, sentRecorder.finished.single().state)
        assertEquals(file.sha256(), sha256(File(host.dir, "with-bug.bin")))
        assertEquals(1, guestLink.readyCount(ChannelDirection.SEND))
    }
}

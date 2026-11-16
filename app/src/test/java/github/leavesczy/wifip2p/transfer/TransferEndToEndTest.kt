package github.leavesczy.wifip2p.transfer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the complete engine (handshake, batches, CRC, cancel, reconnect) over real TCP sockets.
 * Small chunk sizes are used on purpose so that every file spans many pipeline chunks.
 */
class TransferEndToEndTest {

    private val tuning = Tuning(chunkSize = 64 * 1024, queueDepth = 4, progressIntervalMs = 5, stallTimeoutMs = 5_000)
    private val dir: File = java.nio.file.Files.createTempDirectory("wfp2p-test").toFile()
    private val cleanup = ArrayList<() -> Unit>()

    @After
    fun tearDown() {
        cleanup.forEach { it() }
        dir.deleteRecursively()
    }

    private class Pair2(val sender: LinkRunner, val receiver: LinkRunner,
                        val senderEvents: RecordingEvents, val receiverEvents: RecordingEvents,
                        val sinks: DirSinks)

    private fun start(senderIsHost: Boolean, connectPortOverride: Int? = null, receiverSinks: SinkFactory? = null): Pair2 {
        val port = freePort()
        val senderEvents = RecordingEvents()
        val receiverEvents = RecordingEvents()
        val sinks = DirSinks(dir)
        val sender = LinkRunner(Protocol.ROLE_SENDER, "Sender-Phone", tuning, null, senderEvents)
        val receiver = LinkRunner(Protocol.ROLE_RECEIVER, "Receiver-Phone", tuning, receiverSinks ?: sinks, receiverEvents)
        cleanup.add { sender.stop(); receiver.stop() }
        if (senderIsHost) {
            sender.startHost(port)
            receiver.startGuest("127.0.0.1", connectPortOverride ?: port)
        } else {
            receiver.startHost(port)
            sender.startGuest("127.0.0.1", connectPortOverride ?: port)
        }
        return Pair2(sender, receiver, senderEvents, receiverEvents, sinks)
    }

    private fun sampleFiles(): List<RandomSource> = listOf(
        RandomSource("empty.bin", 0),
        RandomSource("tiny.bin", 1),
        RandomSource("exact-chunk.bin", 64 * 1024),
        RandomSource("odd size.bin", 64 * 1024 * 3 + 17),
        RandomSource("photo.jpg", 5_000_000),
        RandomSource("\u0939\u093F\u0928\u094D\u0926\u0940 \u092B\u093E\u0907\u0932.txt", 12_345)
    )

    private fun verifyAllDelivered(p: Pair2, files: List<RandomSource>) {
        p.senderEvents.awaitFinished(files.size)
        p.receiverEvents.awaitFinished(files.size)
        for (f in files) {
            val sent = p.senderEvents.finished.first { it.name == f.name }
            assertEquals("sender state of ${f.name}: ${sent.message}", FileState.DONE, sent.state)
            val got = p.receiverEvents.finished.first { it.name == f.name }
            assertEquals("receiver state of ${f.name}: ${got.message}", FileState.DONE, got.state)
            val stored = File(got.location!!)
            assertEquals("size of ${f.name}", f.size, stored.length())
            assertEquals("content of ${f.name}", f.sha256(), sha256(stored))
        }
    }

    @Test
    fun receiverIsGroupOwner_multipleFilesArriveBitPerfect() {
        val p = start(senderIsHost = false)
        p.senderEvents.awaitReady()
        p.receiverEvents.awaitReady()
        assertEquals("Receiver-Phone", p.senderEvents.peerName)
        assertEquals("Sender-Phone", p.receiverEvents.peerName)
        val files = sampleFiles()
        assertTrue(p.sender.send(files))
        verifyAllDelivered(p, files)
        p.senderEvents.await("batch end", 5_000) { p.senderEvents.batchesFinished == 1 && p.receiverEvents.batchesFinished == 1 }
        // progress reached 100 % of the batch
        val last = p.senderEvents.progress.last()
        assertEquals(last.batchTotal, last.batchBytes)
    }

    @Test
    fun senderIsGroupOwner_worksToo() {
        // The old app could not do this at all: it only worked when the RECEIVER became group owner.
        val p = start(senderIsHost = true)
        p.senderEvents.awaitReady()
        p.receiverEvents.awaitReady()
        val files = sampleFiles()
        assertTrue(p.sender.send(files))
        verifyAllDelivered(p, files)
    }

    @Test
    fun manySmallFilesAllArrive() {
        val p = start(senderIsHost = false)
        p.senderEvents.awaitReady()
        val files = (1..300).map { RandomSource("small-$it.bin", 500L + it * 7, seed = it.toLong()) }
        val started = System.nanoTime()
        assertTrue(p.sender.send(files))
        p.senderEvents.awaitFinished(files.size, 60_000)
        p.receiverEvents.awaitFinished(files.size, 60_000)
        val seconds = (System.nanoTime() - started) / 1e9
        println("      300 small files in %.2f s (%.0f files/s)".format(seconds, files.size / seconds))
        assertTrue(p.senderEvents.finished.all { it.state == FileState.DONE })
        assertEquals(files.size, p.sinks.committed.size)
        // spot-check the content of a few
        for (i in listOf(0, 99, 299)) {
            assertEquals(files[i].sha256(), sha256(File(dir, files[i].name)))
        }
        assertEquals(1, p.senderEvents.readyCount())
    }

    @Test
    fun severalBatchesOnTheSameConnection() {
        val p = start(senderIsHost = false)
        p.senderEvents.awaitReady()
        val first = listOf(RandomSource("one.bin", 300_000))
        val second = listOf(RandomSource("two.bin", 200_000), RandomSource("three.bin", 100_000))
        assertTrue(p.sender.send(first))
        assertTrue(p.sender.send(second))
        p.senderEvents.awaitFinished(3)
        p.receiverEvents.awaitFinished(3)
        assertTrue(p.senderEvents.finished.all { it.state == FileState.DONE })
        assertEquals(1, p.senderEvents.readyCount()) // never reconnected
    }

    @Test
    fun duplicateNamesAreNotOverwritten() {
        val p = start(senderIsHost = false)
        p.senderEvents.awaitReady()
        p.sender.send(listOf(RandomSource("same.txt", 1000, seed = 1), RandomSource("same.txt", 1000, seed = 2)))
        p.receiverEvents.awaitFinished(2)
        val names = p.sinks.committed.map { it.name }.sorted()
        assertEquals(listOf("same (1).txt", "same.txt"), names)
    }

    @Test
    fun maliciousFileNameCannotEscapeTheTargetDirectory() {
        val p = start(senderIsHost = false)
        p.senderEvents.awaitReady()
        p.sender.send(listOf(MemorySource("../../../tmp/pwned.txt", "hi".toByteArray())))
        p.receiverEvents.awaitFinished(1)
        val stored = p.sinks.committed.single()
        assertEquals("pwned.txt", stored.name)
        assertEquals(dir.canonicalPath, stored.parentFile.canonicalPath)
    }

    @Test
    fun corruptionInTransitIsDetectedAndOnlyThatFileFails() {
        val port = freePort()
        val receiverEvents = RecordingEvents()
        val senderEvents = RecordingEvents()
        val sinks = DirSinks(dir)
        val receiver = LinkRunner(Protocol.ROLE_RECEIVER, "R", tuning, sinks, receiverEvents)
        val sender = LinkRunner(Protocol.ROLE_SENDER, "S", tuning, null, senderEvents)
        val proxy = CorruptingProxy(port, flipAtByte = 100_000) // lands inside the first file
        cleanup.add { sender.stop(); receiver.stop(); proxy.close() }
        receiver.startHost(port)
        // the proxy dials the receiver as soon as the sender connects, so wait until it is listening
        receiverEvents.await("receiver listening", 5_000) { receiverEvents.statuses.any { it.first == LinkStatus.LISTENING } }
        sender.startGuest("127.0.0.1", proxy.port)
        senderEvents.awaitReady()
        receiverEvents.awaitReady()

        val a = RandomSource("damaged.bin", 500_000)
        val b = RandomSource("healthy.bin", 400_000)
        sender.send(listOf(a, b))
        senderEvents.awaitFinished(2)
        receiverEvents.awaitFinished(2)

        val sa = senderEvents.finished.first { it.name == "damaged.bin" }
        assertEquals(FileState.FAILED, sa.state)
        assertTrue(sa.message!!.contains("Checksum"))
        assertEquals(FileState.DONE, senderEvents.finished.first { it.name == "healthy.bin" }.state)
        assertFalse("damaged partial file must not be kept", File(dir, "damaged.bin").exists())
        assertEquals(b.sha256(), sha256(File(dir, "healthy.bin")))
    }

    @Test
    fun cancelStopsTransferDeletesPartialFileAndLinkReconnects() {
        val p = start(senderIsHost = false)
        p.senderEvents.awaitReady()
        p.receiverEvents.awaitReady()

        val slow = RandomSource("huge.bin", 200_000_000, throttleBytesPerSecond = 40_000_000)
        p.sender.send(listOf(slow, RandomSource("never-sent.bin", 1000)))
        p.senderEvents.await("some progress", 10_000) { p.senderEvents.progress.any { it.fileBytes > 3_000_000 } }
        p.sender.cancelTransfers()

        p.senderEvents.awaitFinished(2, 10_000)
        assertTrue(p.senderEvents.finished.all { it.state == FileState.CANCELLED })
        p.receiverEvents.await("receiver noticed", 10_000) { p.receiverEvents.finished.isNotEmpty() }
        assertTrue(p.sinks.committed.isEmpty())
        p.receiverEvents.await("partial file deleted", 5_000) { dir.listFiles()!!.isEmpty() }

        // the link heals itself: both sides get READY again and a new transfer works
        p.senderEvents.awaitReady(15_000, count = 2)
        p.receiverEvents.awaitReady(15_000, count = 2)
        val small = RandomSource("after-cancel.bin", 250_000)
        assertTrue(p.sender.send(listOf(small)))
        p.receiverEvents.await("new file", 10_000) { p.receiverEvents.finished.any { it.name == "after-cancel.bin" } }
        assertEquals(small.sha256(), sha256(File(dir, "after-cancel.bin")))
    }

    @Test
    fun batchLargerThanFreeSpaceIsRefusedWithoutBreakingTheConnection() {
        val p = start(senderIsHost = false)
        p.senderEvents.awaitReady()
        p.sinks.available = 10_000
        p.sender.send(listOf(RandomSource("big.bin", 1_000_000)))
        p.senderEvents.awaitFinished(1)
        val r = p.senderEvents.finished.single()
        assertEquals(FileState.FAILED, r.state)
        assertTrue(r.message!!.contains("Not enough storage"))
        assertTrue(dir.listFiles()!!.isEmpty())

        p.sinks.available = Long.MAX_VALUE
        p.sender.send(listOf(RandomSource("fits.bin", 5000)))
        p.senderEvents.awaitFinished(2)
        assertEquals(FileState.DONE, p.senderEvents.finished.last().state)
        assertEquals(1, p.senderEvents.readyCount()) // same connection was reused
    }

    @Test
    fun unreadableSourceOnlyFailsThatFile() {
        val p = start(senderIsHost = false)
        p.senderEvents.awaitReady()
        val files = listOf(RandomSource("ok1.bin", 5000), RandomSource("locked.bin", 5000, failOpen = true), RandomSource("ok2.bin", 5000))
        p.sender.send(files)
        p.senderEvents.awaitFinished(3)
        assertEquals(FileState.DONE, p.senderEvents.finished.first { it.name == "ok1.bin" }.state)
        val locked = p.senderEvents.finished.first { it.name == "locked.bin" }
        assertEquals(FileState.FAILED, locked.state)
        assertTrue(locked.message!!.contains("permission denied"))
        p.senderEvents.await("ok2", 5_000) { p.senderEvents.finished.any { it.name == "ok2.bin" } }
        assertEquals(FileState.DONE, p.senderEvents.finished.first { it.name == "ok2.bin" }.state)
    }

    @Test
    fun receiverThatCannotCreateFilesFailsCleanlyAndReconnects() {
        val p = start(senderIsHost = false)
        p.senderEvents.awaitReady()
        p.sinks.failCreate = true
        p.sender.send(listOf(RandomSource("x.bin", 300_000), RandomSource("y.bin", 100)))
        p.senderEvents.awaitFinished(2)
        assertTrue(p.senderEvents.finished.all { it.state == FileState.FAILED })
        assertTrue(p.senderEvents.finished.first().message!!.contains("disk full"))
        p.sinks.failCreate = false
        p.senderEvents.awaitReady(15_000, count = 2)
        p.sender.send(listOf(RandomSource("z.bin", 1000)))
        p.senderEvents.awaitFinished(3)
        assertEquals(FileState.DONE, p.senderEvents.finished.last().state)
    }

    @Test
    fun twoSendersAreRefusedWithAClearMessage() {
        val port = freePort()
        val e1 = RecordingEvents()
        val e2 = RecordingEvents()
        val a = LinkRunner(Protocol.ROLE_SENDER, "A", tuning, null, e1)
        val b = LinkRunner(Protocol.ROLE_SENDER, "B", tuning, null, e2)
        cleanup.add { a.stop(); b.stop() }
        a.startHost(port)
        b.startGuest("127.0.0.1", port)
        e2.await("role mismatch", 10_000) { e2.statuses.any { it.first == LinkStatus.DISCONNECTED && it.second!!.contains("Send mode") } }
        assertEquals(0, e1.readyCount())
        assertEquals(0, e2.readyCount())
    }

    @Test
    fun sendWithoutConnectionIsRejectedImmediately() {
        val e = RecordingEvents()
        val sender = LinkRunner(Protocol.ROLE_SENDER, "S", tuning, null, e)
        cleanup.add { sender.stop() }
        assertFalse(sender.send(listOf(MemorySource("a", ByteArray(1)))))
    }

    @Test
    fun guestKeepsRetryingUntilTheHostAppears() {
        val port = freePort()
        val e = RecordingEvents()
        val sender = LinkRunner(Protocol.ROLE_SENDER, "S", tuning, null, e)
        cleanup.add { sender.stop() }
        sender.startGuest("127.0.0.1", port) // nobody is listening yet (DHCP / listener not up)
        Thread.sleep(1500)
        assertEquals(0, e.readyCount())
        val re = RecordingEvents()
        val receiver = LinkRunner(Protocol.ROLE_RECEIVER, "R", tuning, DirSinks(dir), re)
        cleanup.add { receiver.stop() }
        receiver.startHost(port)
        e.awaitReady(15_000)
        assertNotNull(e.peerName)
    }

    @Test
    fun stalledReceiverIsDetectedByTheSenderWatchdog() {
        // A receiver that accepts the connection and answers the handshake but then never reads again.
        val port = freePort()
        val server = java.net.ServerSocket(port)
        val frozen = Thread {
            try {
                val s = server.accept()
                val input = s.getInputStream()
                val output = s.getOutputStream()
                FrameIO.write(output, Hello(Protocol.ROLE_RECEIVER, "Frozen").toFrame())
                FrameIO.read(input) // HELLO
                FrameIO.read(input) // BATCH
                FrameIO.write(output, BatchReply(true, "").toFrame())
                Thread.sleep(60_000) // ...and never read the data
            } catch (_: Exception) {
            }
        }
        frozen.isDaemon = true
        frozen.start()
        val e = RecordingEvents()
        val sender = LinkRunner(Protocol.ROLE_SENDER, "S", tuning, null, e)
        cleanup.add { sender.stop(); server.close() }
        sender.startGuest("127.0.0.1", port)
        e.awaitReady()
        val started = System.currentTimeMillis()
        sender.send(listOf(RandomSource("blocked.bin", 500_000_000)))
        e.awaitFinished(1, 20_000)
        val took = System.currentTimeMillis() - started
        assertEquals(FileState.FAILED, e.finished.single().state)
        assertTrue("watchdog should fire after ~5 s, took $took ms", took in 3_000..15_000)
    }

    // ---- a hostile or buggy peer ---------------------------------------------------------------

    private class RogueSender(port: Int) : java.io.Closeable {
        val socket = java.net.Socket("127.0.0.1", port)
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        init {
            socket.soTimeout = 5_000
            FrameIO.write(out, Hello(Protocol.ROLE_SENDER, "rogue").toFrame())
            Hello.parse(FrameIO.read(input))
        }

        fun isDroppedByReceiver(): Boolean {
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

    private fun startReceiverHost(): Triple<LinkRunner, RecordingEvents, Int> {
        val port = freePort()
        val events = RecordingEvents()
        val receiver = LinkRunner(Protocol.ROLE_RECEIVER, "R", tuning, DirSinks(dir), events)
        cleanup.add { receiver.stop() }
        receiver.startHost(port)
        events.await("listening", 5_000) { events.statuses.any { it.first == LinkStatus.LISTENING } }
        return Triple(receiver, events, port)
    }

    @Test
    fun fileWithoutAnnouncedBatchIsRejected() {
        val (_, events, port) = startReceiverHost()
        RogueSender(port).use { rogue ->
            FrameIO.write(rogue.out, FileHeader(0, "x.bin", 10, "x", 0).toFrame())
            assertTrue(rogue.isDroppedByReceiver())
        }
        events.await("disconnect reported", 5_000) {
            events.statuses.any { it.first == LinkStatus.DISCONNECTED && it.second!!.contains("announced") }
        }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun fileBiggerThanTheAnnouncedBatchIsRejected() {
        val (_, events, port) = startReceiverHost()
        RogueSender(port).use { rogue ->
            FrameIO.write(rogue.out, BatchInfo(1, 100).toFrame())
            assertTrue(BatchReply.parse(FrameIO.read(rogue.input)).accepted)
            FrameIO.write(rogue.out, FileHeader(0, "huge.bin", 1_000_000, "x", 0).toFrame())
            assertTrue(rogue.isDroppedByReceiver())
        }
        events.await("disconnect reported", 5_000) {
            events.statuses.any { it.first == LinkStatus.DISCONNECTED && it.second!!.contains("announced") }
        }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun moreFilesThanAnnouncedAreRejected() {
        val (_, events, port) = startReceiverHost()
        val payload = ByteArray(10) { it.toByte() }
        val crc = java.util.zip.CRC32().also { it.update(payload) }
        RogueSender(port).use { rogue ->
            FrameIO.write(rogue.out, BatchInfo(1, 100).toFrame())
            assertTrue(BatchReply.parse(FrameIO.read(rogue.input)).accepted)
            FrameIO.write(rogue.out, FileHeader(0, "one.bin", 10, "x", 0).toFrame())
            rogue.out.write(payload)
            FrameIO.write(rogue.out, FileEnd(crc.value).toFrame())
            assertTrue(FileAck.parse(FrameIO.read(rogue.input)).ok)
            // the batch announced a single file - a second one is a violation
            FrameIO.write(rogue.out, FileHeader(1, "two.bin", 10, "x", 0).toFrame())
            assertTrue(rogue.isDroppedByReceiver())
        }
        events.await("disconnect reported", 5_000) {
            events.statuses.any { it.first == LinkStatus.DISCONNECTED && it.second!!.contains("announced") }
        }
        assertEquals(listOf("one.bin"), dir.listFiles()!!.map { it.name })
    }
}

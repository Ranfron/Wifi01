package github.leavesczy.wifip2p.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ProtocolAndSupportTest {

    private fun roundTrip(frame: Frame): Frame {
        val out = ByteArrayOutputStream()
        FrameIO.write(out, frame)
        return FrameIO.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun helloRoundTrip() {
        val hello = Hello(Protocol.ROLE_SENDER, "Pixel 8 \u0939\u093F\u0928\u094D\u0926\u0940 \uD83D\uDE00")
        assertEquals(hello, Hello.parse(roundTrip(hello.toFrame())))
    }

    @Test
    fun helloRejectsForeignTraffic() {
        val garbage = Frame(Protocol.TYPE_HELLO, ByteArray(16) { 7 })
        try {
            Hello.parse(garbage)
            fail("must reject")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun messagesRoundTrip() {
        assertEquals(BatchInfo(3, 1L shl 40), BatchInfo.parse(roundTrip(BatchInfo(3, 1L shl 40).toFrame())))
        assertEquals(BatchReply(false, "no space"), BatchReply.parse(roundTrip(BatchReply(false, "no space").toFrame())))
        val header = FileHeader(5, "a.jpg", 9_999_999_999L, "image/jpeg", 123456789L)
        assertEquals(header, FileHeader.parse(roundTrip(header.toFrame())))
        assertEquals(FileAck(2, true, ""), FileAck.parse(roundTrip(FileAck(2, true, "").toFrame())))
    }

    @Test
    fun crcSurvivesFullUnsignedRange() {
        for (crc in longArrayOf(0L, 1L, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL, 0xDEADBEEFL)) {
            assertEquals(crc, FileEnd.parse(roundTrip(FileEnd(crc).toFrame())).crc32)
        }
    }

    @Test
    fun oversizedAndNegativeLengthsAreRejected() {
        val bad = byteArrayOf(1, 0x7F, 0, 0, 0)
        try {
            FrameIO.read(ByteArrayInputStream(bad))
            fail("must reject huge frame")
        } catch (_: ProtocolException) {
        }
        val negative = byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        try {
            FrameIO.read(ByteArrayInputStream(negative))
            fail("must reject negative length")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun truncatedFrameIsEof() {
        val out = ByteArrayOutputStream()
        FrameIO.write(out, Hello(Protocol.ROLE_RECEIVER, "x").toFrame())
        val bytes = out.toByteArray()
        try {
            FrameIO.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 2)))
            fail("must fail")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun invalidFileHeaderIsRejected() {
        val frame = FileHeader(0, "a", 10, "x", 0).toFrame()
        // flip the sign bit of the size field (4 bytes index + 2+1 name + 8 size)
        val payload = frame.payload.copyOf()
        payload[4 + 2 + 1] = 0x80.toByte()
        try {
            FileHeader.parse(Frame(frame.type, payload))
            fail("negative size must be rejected")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun sanitizeBlocksPathTraversal() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.sh", FileNames.sanitize("C:\\Windows\\evil.sh"))
        assertEquals("file", FileNames.sanitize(".."))
        assertEquals("file", FileNames.sanitize(""))
        assertEquals("file", FileNames.sanitize(null))
        assertEquals("file", FileNames.sanitize("   ..."))
        assertEquals("a_b_c.txt", FileNames.sanitize("a<b>c.txt"))
        assertEquals("tab_name", FileNames.sanitize("tab\tname"))
        assertEquals(".hidden", FileNames.sanitize(".hidden"))
        assertEquals("\u0939\u093F\u0928\u094D\u0926\u0940.pdf", FileNames.sanitize("\u0939\u093F\u0928\u094D\u0926\u0940.pdf"))
    }

    @Test
    fun sanitizeKeepsExtensionWhenTruncating() {
        val long = "x".repeat(400) + ".mp4"
        val clean = FileNames.sanitize(long)
        assertTrue(clean.length <= 180)
        assertTrue(clean.endsWith(".mp4"))
    }

    @Test
    fun uniqueNames() {
        val taken = setOf("a.txt", "a (1).txt", "noext", "noext (1)")
        assertEquals("a (2).txt", FileNames.unique("a.txt") { it in taken })
        assertEquals("noext (2)", FileNames.unique("noext") { it in taken })
        assertEquals("b.txt", FileNames.unique("b.txt") { it in taken })
    }

    @Test
    fun formatters() {
        assertEquals("0 B", Formatters.size(0))
        assertEquals("1023 B", Formatters.size(1023))
        assertEquals("1.00 KB", Formatters.size(1024))
        assertEquals("1.50 MB", Formatters.size(1_572_864))
        assertEquals("35.0 MB/s", Formatters.speed(36_700_160))
        assertEquals("4.00 GB", Formatters.size(4L * 1024 * 1024 * 1024))
        assertEquals("00:05", Formatters.duration(5))
        assertEquals("1:01:01", Formatters.duration(3661))
        assertEquals("--:--", Formatters.duration(-1))
        assertEquals(10L, Formatters.etaSeconds(1000, 100))
        assertEquals(11L, Formatters.etaSeconds(1001, 100))
        assertEquals(-1L, Formatters.etaSeconds(1000, 0))
    }

    @Test
    fun speedMeterMeasuresWindow() {
        var now = 0L
        val meter = SpeedMeter(windowMs = 2000, clockMs = { now })
        assertEquals(0L, meter.bytesPerSecond())
        for (i in 0..10) {
            now = i * 100L
            meter.record(i * 10_000_000L) // 100 MB/s
        }
        assertEquals(100_000_000L, meter.bytesPerSecond())
        // the speed drops to 10 MB/s: after the window passes the old samples are forgotten
        var total = 100_000_000L
        for (i in 1..40) {
            now += 100
            total += 1_000_000
            meter.record(total)
        }
        assertEquals(10_000_000L, meter.bytesPerSecond())
    }

    // ---- ChunkPipe ---------------------------------------------------------------------------

    private fun pipeCopy(data: ByteArray, chunk: Int, depth: Int): ByteArray {
        val input = ByteArrayInputStream(data)
        val out = ByteArrayOutputStream()
        ChunkPipe.run(chunk, depth, CancelToken(), "test",
            producer = { c ->
                val n = FrameIO.readUpTo(input, c.data, c.data.size)
                c.length = n
                n > 0
            },
            consumer = { c -> out.write(c.data, 0, c.length) })
        return out.toByteArray()
    }

    @Test
    fun chunkPipeCopiesExactlyForAwkwardSizes() {
        val random = java.util.Random(42)
        for (size in intArrayOf(0, 1, 99, 100, 101, 999, 1000, 1001, 12_345, 100_000)) {
            val data = ByteArray(size).also { random.nextBytes(it) }
            assertTrue("size $size", data.contentEquals(pipeCopy(data, 100, 3)))
        }
        val big = ByteArray(3_000_000).also { random.nextBytes(it) }
        assertTrue(big.contentEquals(pipeCopy(big, 64 * 1024, 6)))
    }

    @Test
    fun chunkPipeReportsProducerFailure() {
        val count = AtomicInteger()
        try {
            ChunkPipe.run(10, 2, CancelToken(), "test",
                producer = { c ->
                    if (count.incrementAndGet() > 3) throw IOException("disk read error")
                    c.length = 10
                    true
                },
                consumer = { })
            fail("must propagate")
        } catch (e: IOException) {
            assertEquals("disk read error", e.message)
        }
    }

    @Test
    fun chunkPipeReportsConsumerFailureAndStopsProducer() {
        val produced = AtomicInteger()
        try {
            ChunkPipe.run(10, 2, CancelToken(), "test",
                producer = { c ->
                    produced.incrementAndGet()
                    c.length = 10
                    true // endless producer
                },
                consumer = { throw IOException("socket closed") })
            fail("must propagate")
        } catch (e: IOException) {
            assertEquals("socket closed", e.message)
        }
        Thread.sleep(300)
        val snapshot = produced.get()
        Thread.sleep(300)
        assertEquals("producer must have stopped", snapshot.toLong(), produced.get().toLong())
    }

    @Test
    fun chunkPipeCanBeCancelled() {
        val cancel = CancelToken()
        val seen = AtomicInteger()
        try {
            ChunkPipe.run(10, 2, cancel, "test",
                producer = { c ->
                    c.length = 10
                    true
                },
                consumer = {
                    if (seen.incrementAndGet() == 5) cancel.cancel()
                })
            fail("must be cancelled")
        } catch (_: CancelledException) {
        }
        assertFalse(seen.get() > 10)
    }

    @Test
    fun smallFilesSkipThePipelineAndReusePoolBuffers() {
        val pool = ChunkPool(100, 3)
        val first = pool.take()
        pool.giveBack(first)
        val data = ByteArray(80) { it.toByte() }
        val input = ByteArrayInputStream(data)
        val out = ByteArrayOutputStream()
        val callerThread = Thread.currentThread()
        var consumerThread: Thread? = null
        ChunkPipe.run(pool, 80, CancelToken(), "pipe-small",
            producer = { c ->
                val n = FrameIO.readUpTo(input, c.data, 100)
                c.length = n
                n > 0
            },
            consumer = { c ->
                consumerThread = Thread.currentThread()
                out.write(c.data, 0, c.length)
            })
        assertTrue(data.contentEquals(out.toByteArray()))
        assertTrue("small file must not need a helper thread", consumerThread === callerThread)
        assertTrue("buffers must come back to the pool", first === pool.take())
    }

    @Test
    fun poolBuffersAreReusedAcrossPipelinedFiles() {
        val pool = ChunkPool(1000, 4)
        val random = java.util.Random(7)
        var lastBuffers: Array<Chunk>? = null
        for (round in 1..3) {
            val data = ByteArray(25_000).also { random.nextBytes(it) }
            val input = ByteArrayInputStream(data)
            val out = ByteArrayOutputStream()
            ChunkPipe.run(pool, data.size.toLong(), CancelToken(), "pipe-big",
                producer = { c ->
                    val n = FrameIO.readUpTo(input, c.data, c.data.size)
                    c.length = n
                    n > 0
                },
                consumer = { c -> out.write(c.data, 0, c.length) })
            assertTrue(data.contentEquals(out.toByteArray()))
            val buffers = pool.take()
            if (lastBuffers != null) assertTrue(lastBuffers === buffers)
            lastBuffers = buffers
            pool.giveBack(buffers)
        }
    }

    @Test
    fun failedPipelineDoesNotReturnPossiblyBusyBuffers() {
        val pool = ChunkPool(100, 3)
        val before = pool.take()
        pool.giveBack(before)
        try {
            ChunkPipe.run(pool, 10_000, CancelToken(), "pipe-fail",
                producer = { c -> c.length = 100; true },
                consumer = { throw IOException("boom") })
            fail("must fail")
        } catch (_: IOException) {
        }
        assertFalse("a failed run must not recycle its buffers", before === pool.take())
    }

    @Test
    fun fileEndCarriesTheIncompleteFlag() {
        val end = FileEnd.parse(roundTrip(FileEnd(0xCAFEL, incomplete = true).toFrame()))
        assertEquals(0xCAFEL, end.crc32)
        assertTrue(end.incomplete)
        assertFalse(FileEnd.parse(roundTrip(FileEnd(7L).toFrame())).incomplete)
    }
}

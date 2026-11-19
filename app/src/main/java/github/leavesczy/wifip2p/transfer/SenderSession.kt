package github.leavesczy.wifip2p.transfer

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32

class RejectedException(message: String) : IOException(message)

internal fun describeError(t: Throwable): String {
    return when (t) {
        is CancelledException -> "Cancelled"
        is SocketTimeoutException -> "Timed out"
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }
}

/**
 * Sender half of one TCP connection. Long-lived: after [start] any number of batches can be sent
 * with [sendBatch].
 *
 * Connection health:
 *  - a heartbeat thread keeps the other side informed while idle,
 *  - the other side's heartbeats are watched: silence for [Tuning.deadAfterMs] means "dead", and the
 *    connection is closed at once instead of waiting minutes for the TCP stack to notice.
 *
 * Failures are classified:
 *  - a problem with ONE file (unreadable, refused, damaged) fails only that file;
 *  - a broken connection marks the unfinished files INTERRUPTED: they continue on the next link.
 */
class SenderSession(
    private val socket: Socket,
    private val tuning: Tuning,
    private val events: SessionEvents
) : Closeable {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val pool = ChunkPool(tuning.chunkSize, tuning.queueDepth)

    /** Every write to the socket goes through this lock, so frames and raw data never interleave. */
    private val writeLock = Any()

    // Frames from the receiver (BATCH_REPLY, FILE_START, FILE_ACK) or the CLOSED marker.
    private val inbox = LinkedBlockingQueue<Any>()
    private val closeLatch = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private val lastInbound = AtomicLong(System.nanoTime())
    private val lastProgress = AtomicLong(System.nanoTime())

    // Held while a batch runs, so "the connection closed" can wait until the batch has reported
    // the precise reason of every file (see awaitIdle).
    private val batchLock = ReentrantLock()

    @Volatile
    private var transferring = false

    /** True from a FILE header until its FILE_END: no heartbeat may appear inside that span (guarded by writeLock). */
    private var fileInProgress = false

    /** Why this side closed the connection on purpose (silence, stall), if it did. */
    @Volatile
    private var closeReason: String? = null

    val isClosed: Boolean
        get() = closed.get()

    /** Starts the reader, the heartbeat and the health watchdog. The HELLO exchange is already done. */
    fun start() {
        startReader()
        startHeartbeat()
        startWatchdog()
    }

    /** Blocks until the connection is gone. */
    fun awaitClosed() {
        closeLatch.await()
    }

    /** Waits (bounded) until a running batch has finished reporting. */
    fun awaitIdle(timeoutMs: Long) {
        try {
            if (batchLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                batchLock.unlock()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun closeBecause(reason: String) {
        if (closeReason == null) {
            closeReason = reason
        }
        close()
    }

    private fun write(frame: Frame) {
        synchronized(writeLock) {
            FrameIO.write(output, frame)
        }
    }

    private fun startThread(name: String, body: () -> Unit) {
        val thread = Thread({
            try {
                body()
            } catch (_: Throwable) {
            }
        }, name)
        thread.isDaemon = true
        thread.start()
    }

    private fun startReader() {
        startThread("wfp2p-sender-reader") {
            try {
                while (true) {
                    val frame = FrameIO.read(input)
                    lastInbound.set(System.nanoTime())
                    if (frame.type != Protocol.TYPE_HEARTBEAT) {
                        inbox.put(frame)
                    }
                }
            } catch (_: Throwable) {
                // connection closed or broken - reported through the CLOSED marker below
            } finally {
                inbox.offer(CLOSED)
                closeLatch.countDown()
            }
        }
    }

    private fun startHeartbeat() {
        startThread("wfp2p-sender-heartbeat") {
            try {
                while (!closed.get()) {
                    Thread.sleep(tuning.heartbeatIntervalMs)
                    if (!closed.get()) {
                        synchronized(writeLock) {
                            if (!fileInProgress) {
                                FrameIO.write(output, Heartbeat.frame())
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                closeBecause("Connection lost")
            }
        }
    }

    private fun startWatchdog() {
        startThread("wfp2p-sender-watchdog") {
            while (!closed.get()) {
                Thread.sleep(250)
                val now = System.nanoTime()
                if (millisSince(lastInbound, now) > tuning.deadAfterMs) {
                    closeBecause("The other phone stopped responding")
                } else if (transferring && millisSince(lastProgress, now) > tuning.stallTimeoutMs) {
                    closeBecause("The transfer stalled")
                }
            }
        }
    }

    private fun millisSince(at: AtomicLong, now: Long): Long = (now - at.get()) / 1_000_000

    private fun touch() {
        lastProgress.set(System.nanoTime())
    }

    @Throws(IOException::class)
    private fun awaitFrame(expectedType: Int, timeoutMs: Long, cancel: CancelToken): Frame {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            cancel.throwIfCancelled()
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) {
                throw SocketTimeoutException("The receiver did not answer in time")
            }
            val item = inbox.poll(minOf(remainingMs, 200L), TimeUnit.MILLISECONDS) ?: continue
            if (item === CLOSED) {
                inbox.offer(CLOSED) // keep later waiters informed as well
                throw EOFException("Connection closed by the receiver")
            }
            val frame = item as Frame
            if (frame.type != expectedType) {
                throw ProtocolException("Unexpected message ${frame.type} (expected $expectedType)")
            }
            return frame
        }
    }

    private inner class Tracker(val batchTotal: Long) {
        var batchBytes = 0L
        private val meter = SpeedMeter()
        private var lastEmitNanos = 0L

        fun emit(index: Int, name: String, fileBytes: Long, fileSize: Long, force: Boolean) {
            val now = System.nanoTime()
            if (!force && now - lastEmitNanos < tuning.progressIntervalMs * 1_000_000) {
                return
            }
            lastEmitNanos = now
            meter.record(batchBytes)
            events.onProgress(
                FileProgress(index, name, fileBytes, fileSize, batchBytes, batchTotal, meter.bytesPerSecond())
            )
        }
    }

    /**
     * Sends all [sources]. Never throws: every file gets exactly one
     * [SessionEvents.onFileFinished] callback and the returned [BatchResult] summarises the batch.
     */
    fun sendBatch(sources: List<SendSource>, cancel: CancelToken): BatchResult {
        batchLock.lock()
        try {
            return sendBatchLocked(sources, cancel)
        } finally {
            batchLock.unlock()
        }
    }

    private fun sendBatchLocked(sources: List<SendSource>, cancel: CancelToken): BatchResult {
        val total = sources.sumOf { it.size }
        events.onBatchStarted(sources.size, total)
        val tracker = Tracker(total)
        var done = 0
        var failed = 0
        var index = 0
        try {
            write(BatchInfo(sources.size, total).toFrame())
            val reply = BatchReply.parse(awaitFrame(Protocol.TYPE_BATCH_REPLY, tuning.ackTimeoutMs, cancel))
            if (!reply.accepted) {
                throw RejectedException(reply.message.ifEmpty { "The receiver refused the files" })
            }
            while (index < sources.size) {
                cancel.throwIfCancelled()
                when (sendOne(index, sources[index], cancel, tracker)) {
                    FileState.DONE -> done++
                    else -> failed++
                }
                index++
            }
            write(Frame(Protocol.TYPE_BATCH_END))
            return BatchResult(done, failed, 0, null)
        } catch (t: Throwable) {
            val cancelled = cancel.cancelled || t is CancelledException
            val state = when {
                cancelled -> FileState.CANCELLED
                t is RejectedException || t is ProtocolException -> FileState.FAILED
                t is IOException -> FileState.INTERRUPTED // the link broke: the files continue later
                else -> FileState.FAILED
            }
            val message = when {
                cancelled || t is RejectedException -> describeError(t)
                else -> closeReason ?: receiverExplanation() ?: describeError(t)
            }
            for (i in index until sources.size) {
                events.onFileFinished(i, sources[i].name, state, message, null)
            }
            val unfinished = sources.size - index
            if (t !is RejectedException) {
                close() // the byte stream may be in the middle of a file - it cannot be reused
            }
            return if (cancelled) {
                BatchResult(done, failed, unfinished, message)
            } else {
                BatchResult(done, failed + unfinished, 0, message)
            }
        } finally {
            transferring = false
            events.onBatchFinished()
        }
    }

    /**
     * When the receiver gives up it sends a FILE_ACK(false, reason) right before hanging up.
     * That reason is more useful than a generic "Broken pipe".
     */
    private fun receiverExplanation(): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400)
        while (System.nanoTime() < deadline) {
            val item = inbox.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (item === CLOSED) {
                inbox.offer(CLOSED)
                return null
            }
            val frame = item as Frame
            if (frame.type == Protocol.TYPE_FILE_ACK) {
                try {
                    val ack = FileAck.parse(frame)
                    if (!ack.ok && ack.message.isNotEmpty()) {
                        return ack.message
                    }
                } catch (_: IOException) {
                }
            }
        }
        return null
    }

    /** Skips [bytes] of [stream] cheaply where possible. Returns how many bytes were really skipped. */
    private fun skipExact(stream: InputStream, bytes: Long): Long {
        var left = bytes
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (stream.read() < 0) {
                break // the source is shorter than the receiver believes
            }
            left--
        }
        return bytes - left
    }

    /**
     * Sends one file. Returns DONE or FAILED for problems that leave the connection usable; only
     * socket and protocol errors are thrown, because they end the session.
     */
    @Throws(Exception::class)
    private fun sendOne(index: Int, source: SendSource, cancel: CancelToken, tracker: Tracker): FileState {
        events.onFileStarted(index, source.name, source.size)
        val key = source.key.ifEmpty { "${source.name}|${source.size}|${source.lastModified}" }

        // Opened before anything is announced: an unreadable file costs nothing on the wire.
        val stream: InputStream = try {
            source.open()
        } catch (e: Exception) {
            events.onFileFinished(index, source.name, FileState.FAILED, "Cannot read file: ${describeError(e)}", null)
            return FileState.FAILED
        }
        try {
            synchronized(writeLock) {
                fileInProgress = true
                FrameIO.write(
                    output,
                    FileHeader(index, source.name, source.size, source.mimeType, source.lastModified, key).toFrame()
                )
            }
            val start = FileStart.parse(awaitFrame(Protocol.TYPE_FILE_START, tuning.ackTimeoutMs, cancel))
            if (start.index != index) {
                throw ProtocolException("Out-of-order file start")
            }
            if (start.alreadyStored) {
                // the receiver has this file already: nothing is sent, and no FILE_END or ACK follows
                tracker.batchBytes += source.size
                events.onFileFinished(index, source.name, FileState.DONE, "Already saved on the other phone", null)
                return FileState.DONE
            }
            if (!start.accepted) {
                events.onFileFinished(index, source.name, FileState.FAILED, start.refusal, null)
                return FileState.FAILED
            }
            if (start.offset > source.size) {
                throw ProtocolException("Invalid resume offset")
            }
            val offset = start.offset
            val toSend = source.size - offset
            val crc = CRC32()
            var incomplete = false

            // Resume: continue after the bytes the receiver already stores. Their checksum comes from the
            // receiver (start.prefixCrc), so nothing is read twice.
            val skipped = try {
                skipExact(stream, offset)
            } catch (_: Exception) {
                -1L
            }
            if (skipped < offset) {
                incomplete = true
            }

            var sourceDone = incomplete
            var produced = 0L
            var sent = offset
            tracker.batchBytes += offset // the bytes the receiver already has count as done
            transferring = true
            touch()
            synchronized(writeLock) {
                ChunkPipe.run(
                    pool = pool,
                    expectedBytes = toSend,
                    cancel = cancel,
                    threadName = "wfp2p-read",
                    producer = { chunk ->
                        val remaining = toSend - produced
                        if (remaining <= 0) {
                            false
                        } else {
                            val want = minOf(chunk.data.size.toLong(), remaining).toInt()
                            var got = 0
                            if (!sourceDone) {
                                got = try {
                                    FrameIO.readUpTo(stream, chunk.data, want)
                                } catch (_: Exception) {
                                    -1
                                }
                                if (got < want) {
                                    sourceDone = true
                                    incomplete = true
                                    got = maxOf(got, 0)
                                }
                            }
                            // What the source could not deliver is padded with zeros: the receiver drops the
                            // file (it is flagged incomplete) but the stream stays in sync.
                            if (got < want) {
                                Arrays.fill(chunk.data, got, want, 0.toByte())
                            }
                            crc.update(chunk.data, 0, want)
                            chunk.length = want
                            produced += want
                            true
                        }
                    },
                    consumer = { chunk ->
                        output.write(chunk.data, 0, chunk.length)
                        sent += chunk.length
                        tracker.batchBytes += chunk.length
                        touch()
                        tracker.emit(index, source.name, sent, source.size, force = false)
                    }
                )
            }
            transferring = false
            tracker.emit(index, source.name, sent, source.size, force = true)
            // The checksum of the WHOLE file: the receiver's stored part combined with the bytes sent now.
            val wholeCrc = Crc32Combine.combine(start.prefixCrc, crc.value, toSend)
            synchronized(writeLock) {
                FrameIO.write(output, FileEnd(wholeCrc, incomplete).toFrame())
                fileInProgress = false
            }
            val ack = FileAck.parse(awaitFrame(Protocol.TYPE_FILE_ACK, tuning.ackTimeoutMs, cancel))
            if (ack.index != index) {
                throw ProtocolException("Out-of-order acknowledgement")
            }
            if (ack.ok && !incomplete) {
                events.onFileFinished(index, source.name, FileState.DONE, null, null)
                return FileState.DONE
            }
            val message = if (incomplete) {
                "Could not read the whole file on this phone"
            } else {
                ack.message.ifEmpty { "The receiver could not store the file" }
            }
            events.onFileFinished(index, source.name, FileState.FAILED, message, null)
            return FileState.FAILED
        } finally {
            transferring = false
            synchronized(writeLock) {
                fileInProgress = false
            }
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        val CLOSED = Any()
    }
}

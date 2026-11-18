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
 * with [sendBatch]. A background reader notices immediately when the other side goes away.
 *
 * Two kinds of failure are told apart:
 *  - a problem with ONE file (unreadable source, a source shorter than announced, refused by the
 *    receiver) fails only that file - the connection stays up and the next file goes out on it;
 *  - a broken connection (socket error, protocol violation, stalled receiver) ends the session.
 */
class SenderSession(
    private val socket: Socket,
    private val tuning: Tuning,
    private val events: SessionEvents
) : Closeable {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val pool = ChunkPool(tuning.chunkSize, tuning.queueDepth)

    // Frames from the receiver (BATCH_REPLY, FILE_ACK) or the CLOSED marker.
    private val inbox = LinkedBlockingQueue<Any>()
    private val closeLatch = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    private val lastActivityNanos = AtomicLong(System.nanoTime())

    // Held while a batch is running, so "the connection closed" can wait until the batch has
    // reported the precise reason of every failed file (see awaitIdle).
    private val batchLock = ReentrantLock()

    @Volatile
    private var transferring = false

    val isClosed: Boolean
        get() = closed.get()

    /** Starts the background reader and the stall watchdog. The HELLO exchange is already done. */
    fun start() {
        startReader()
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

    private fun startReader() {
        val thread = Thread({
            try {
                while (true) {
                    inbox.put(FrameIO.read(input))
                }
            } catch (_: Throwable) {
                // connection closed or broken - handled through the CLOSED marker below
            } finally {
                inbox.offer(CLOSED)
                closeLatch.countDown()
            }
        }, "wfp2p-sender-reader")
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Blocking socket writes have no timeout in Java, so a receiver that silently stops reading
     * would freeze the sender forever. This watchdog closes the socket when a running transfer
     * makes no progress for [Tuning.stallTimeoutMs].
     */
    private fun startWatchdog() {
        val thread = Thread({
            try {
                while (!closed.get()) {
                    Thread.sleep(500)
                    if (transferring) {
                        val idleMs = (System.nanoTime() - lastActivityNanos.get()) / 1_000_000
                        if (idleMs > tuning.stallTimeoutMs) {
                            close()
                        }
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "wfp2p-sender-watchdog")
        thread.isDaemon = true
        thread.start()
    }

    private fun touch() {
        lastActivityNanos.set(System.nanoTime())
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
     * After a fatal error (connection lost, cancelled) the session closes itself.
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
            FrameIO.write(output, BatchInfo(sources.size, total).toFrame())
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
            FrameIO.write(output, Frame(Protocol.TYPE_BATCH_END))
            return BatchResult(done, failed, 0, null)
        } catch (t: Throwable) {
            val wasCancelled = cancel.cancelled || t is CancelledException
            val message = if (wasCancelled || t is RejectedException) {
                describeError(t)
            } else {
                receiverExplanation() ?: describeError(t)
            }
            val state = if (wasCancelled) FileState.CANCELLED else FileState.FAILED
            for (i in index until sources.size) {
                events.onFileFinished(i, sources[i].name, state, message, null)
            }
            val unfinished = sources.size - index
            if (t !is RejectedException) {
                close() // the byte stream may be in the middle of a file - it cannot be reused
            }
            return if (wasCancelled) {
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
     * It is more useful to show that reason than a generic "Broken pipe".
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

    /**
     * Sends one file. Returns DONE or FAILED for problems that leave the connection usable; only
     * socket and protocol errors are thrown, because they end the session.
     */
    @Throws(Exception::class)
    private fun sendOne(index: Int, source: SendSource, cancel: CancelToken, tracker: Tracker): FileState {
        events.onFileStarted(index, source.name, source.size)
        val stream: InputStream
        try {
            stream = source.open()
        } catch (e: Exception) {
            // nothing has been announced to the receiver yet, so the stream is still in sync
            events.onFileFinished(index, source.name, FileState.FAILED, "Cannot read file: ${describeError(e)}", null)
            return FileState.FAILED
        }
        try {
            FrameIO.write(
                output,
                FileHeader(index, source.name, source.size, source.mimeType, source.lastModified).toFrame()
            )
            val crc = CRC32()
            var produced = 0L
            var sent = 0L
            var sourceDone = false
            var incomplete = false
            transferring = true
            touch()
            ChunkPipe.run(
                pool = pool,
                expectedBytes = source.size,
                cancel = cancel,
                threadName = "wfp2p-read",
                producer = { chunk ->
                    val remaining = source.size - produced
                    if (remaining <= 0) {
                        false
                    } else {
                        val want = minOf(chunk.data.size.toLong(), remaining).toInt()
                        var got = 0
                        if (!sourceDone) {
                            got = try {
                                FrameIO.readUpTo(stream, chunk.data, want)
                            } catch (_: Exception) {
                                0 // the provider failed mid-way: treated like a short file below
                            }
                            if (got < want) {
                                sourceDone = true
                                incomplete = true
                            }
                        }
                        // The receiver expects exactly `size` bytes. What the source could not deliver is
                        // padded with zeros: the file is marked incomplete and dropped on the other side,
                        // but the connection carries on with the next file instead of being torn down.
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
            transferring = false
            tracker.emit(index, source.name, sent, source.size, force = true)
            FrameIO.write(output, FileEnd(crc.value, incomplete).toFrame())
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

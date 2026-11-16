package github.leavesczy.wifip2p.transfer

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.zip.CRC32

/**
 * Receiver half of one TCP connection. [serve] loops over incoming batches until the connection
 * ends. Files are streamed straight into the [SinkFactory] (no temp copies), size- and
 * CRC-verified, and a partially received file is always deleted.
 */
class ReceiverSession(
    private val socket: Socket,
    private val deviceName: String,
    private val sinks: SinkFactory,
    private val tuning: Tuning,
    private val events: SessionEvents
) : Closeable {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val pool = ChunkPool(tuning.chunkSize, tuning.queueDepth)

    private var batchActive = false
    private var batchTotal = 0L
    private var batchBytes = 0L
    private var batchFileLimit = 0
    private var batchFilesSeen = 0
    private var meter = SpeedMeter()
    private var lastEmitNanos = 0L

    @Throws(IOException::class)
    fun handshake(): Hello {
        socket.soTimeout = tuning.handshakeTimeoutMs
        FrameIO.write(output, Hello(Protocol.ROLE_RECEIVER, deviceName).toFrame())
        val peer = Hello.parse(FrameIO.read(input))
        if (peer.role != Protocol.ROLE_SENDER) {
            throw ProtocolException("The other device is also in Receive mode")
        }
        socket.soTimeout = 0
        return peer
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    /** Blocks until the sender disconnects; always ends with an exception (EOF on a clean close). */
    @Throws(IOException::class)
    fun serve(cancel: CancelToken) {
        while (true) {
            cancel.throwIfCancelled()
            val frame = FrameIO.read(input)
            when (frame.type) {
                Protocol.TYPE_BATCH -> onBatch(BatchInfo.parse(frame))
                Protocol.TYPE_FILE -> onFile(FileHeader.parse(frame), cancel)
                Protocol.TYPE_BATCH_END -> {
                    batchActive = false
                    events.onBatchFinished()
                }
                else -> throw ProtocolException("Unexpected message ${frame.type}")
            }
        }
    }

    private fun onBatch(info: BatchInfo) {
        val free = try {
            sinks.availableBytes()
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
        batchActive = false
        if (info.totalBytes > free) {
            FrameIO.write(
                output,
                BatchReply(
                    false,
                    "Not enough storage on the receiver (needs ${Formatters.size(info.totalBytes)}, " +
                            "free ${Formatters.size(free)})"
                ).toFrame()
            )
            return
        }
        batchActive = true
        batchTotal = info.totalBytes
        batchBytes = 0
        batchFileLimit = info.fileCount
        batchFilesSeen = 0
        meter = SpeedMeter()
        lastEmitNanos = 0
        FrameIO.write(output, BatchReply(true, "").toFrame())
        events.onBatchStarted(info.fileCount, info.totalBytes)
    }

    /**
     * Tells the sender WHY the transfer stops, then closes the sending direction and swallows what
     * is still in flight for a moment. Closing a socket with unread data would send a TCP reset that
     * can destroy the explanation before the sender reads it ("Broken pipe" is not helpful).
     */
    private fun hangUp(index: Int, reason: String) {
        try {
            FrameIO.write(output, FileAck(index, false, reason).toFrame())
            socket.shutdownOutput()
            socket.soTimeout = 500
            val trash = ByteArray(64 * 1024)
            val deadline = System.nanoTime() + 1_500_000_000L
            while (System.nanoTime() < deadline) {
                if (input.read(trash) < 0) {
                    break
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun emit(index: Int, name: String, fileBytes: Long, fileSize: Long, force: Boolean) {
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

    @Throws(IOException::class)
    private fun onFile(header: FileHeader, cancel: CancelToken) {
        // The storage check happened for the announced batch only: a peer must not be able to
        // sneak in more (or bigger) files than it announced.
        if (!batchActive || batchFilesSeen >= batchFileLimit || batchBytes + header.size > batchTotal) {
            throw ProtocolException("The sender did not follow the announced transfer")
        }
        batchFilesSeen++
        val name = FileNames.sanitize(header.name)
        events.onFileStarted(header.index, name, header.size)

        val handle: SinkHandle
        try {
            handle = sinks.create(name, header.size, header.mimeType, header.lastModified)
        } catch (e: Exception) {
            // The sender is already streaming this file's bytes: tell it why, then drop the connection.
            val reason = "Cannot create file: ${describeError(e)}"
            events.onFileFinished(header.index, name, FileState.FAILED, reason, null)
            hangUp(header.index, reason)
            throw IOException(reason, e)
        }

        var committed = false
        var reported = false
        try {
            socket.soTimeout = tuning.stallTimeoutMs
            val crc = CRC32()
            var remaining = header.size
            var received = 0L
            ChunkPipe.run(
                pool = pool,
                expectedBytes = header.size,
                cancel = cancel,
                threadName = "wfp2p-net-read",
                producer = { chunk ->
                    if (remaining <= 0) {
                        false
                    } else {
                        val want = minOf(chunk.data.size.toLong(), remaining).toInt()
                        val n = FrameIO.readUpTo(input, chunk.data, want)
                        if (n < want) {
                            throw EOFException("Connection lost while receiving \"$name\"")
                        }
                        chunk.length = n
                        remaining -= n
                        true
                    }
                },
                consumer = { chunk ->
                    crc.update(chunk.data, 0, chunk.length)
                    handle.stream.write(chunk.data, 0, chunk.length)
                    received += chunk.length
                    batchBytes += chunk.length
                    emit(header.index, name, received, header.size, force = false)
                }
            )
            handle.stream.flush()
            emit(header.index, name, received, header.size, force = true)
            val end = FileEnd.parse(FrameIO.read(input))
            socket.soTimeout = 0

            // All bytes of this file are consumed: from here on the stream stays consistent even if
            // this single file has to be rejected, so these failures are NOT fatal for the session.
            var failure: String? = null
            if (end.crc32 != crc.value) {
                failure = "Checksum mismatch - the file was damaged in transit"
            }
            var location: String? = null
            if (failure == null) {
                try {
                    location = handle.commit()
                    committed = true
                } catch (e: Exception) {
                    failure = "Cannot save file: ${describeError(e)}"
                }
            }
            if (failure != null) {
                handle.abort()
                reported = true
                events.onFileFinished(header.index, name, FileState.FAILED, failure, null)
                FrameIO.write(output, FileAck(header.index, false, failure).toFrame())
                return
            }
            reported = true
            events.onFileFinished(header.index, name, FileState.DONE, null, location)
            FrameIO.write(output, FileAck(header.index, true, "").toFrame())
        } catch (t: Throwable) {
            if (!committed) {
                handle.abort() // never delete a file that was already published
            }
            if (!reported) {
                val cancelledHere = t is CancelledException || cancel.cancelled
                val state = if (cancelledHere) FileState.CANCELLED else FileState.FAILED
                events.onFileFinished(header.index, name, state, describeError(t), null)
                if (!cancelledHere) {
                    hangUp(header.index, describeError(t))
                }
            }
            if (t is IOException) {
                throw t
            }
            throw IOException(describeError(t), t)
        } finally {
            try {
                socket.soTimeout = 0
            } catch (_: Exception) {
            }
        }
    }
}

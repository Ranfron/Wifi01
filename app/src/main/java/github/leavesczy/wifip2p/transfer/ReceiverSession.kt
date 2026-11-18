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
 *
 * A file that cannot be stored (no permission, disk full, a checksum error, ...) is rejected on its
 * own: its bytes are still read and dropped, so the stream stays in sync and the rest of the batch
 * arrives normally. Only a broken connection ends the session.
 */
class ReceiverSession(
    private val socket: Socket,
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

    @Throws(IOException::class)
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
            rejectFile(header, name, "Cannot create file: ${describeError(e)}", cancel)
            return
        }
        receiveInto(handle, header, name, cancel)
    }

    /** The file cannot be stored: read its bytes and drop them, then tell the sender why. */
    @Throws(IOException::class)
    private fun rejectFile(header: FileHeader, name: String, reason: String, cancel: CancelToken) {
        discardFile(header, cancel)
        events.onFileFinished(header.index, name, FileState.FAILED, reason, null)
        FrameIO.write(output, FileAck(header.index, false, reason).toFrame())
    }

    /** Reads and drops the bytes of a file that is not stored, plus its FILE_END frame. */
    @Throws(IOException::class)
    private fun discardFile(header: FileHeader, cancel: CancelToken) {
        socket.soTimeout = tuning.stallTimeoutMs
        try {
            val buffer = ByteArray(minOf(64L * 1024, maxOf(header.size, 1L)).toInt())
            var remaining = header.size
            while (remaining > 0) {
                cancel.throwIfCancelled()
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val n = FrameIO.readUpTo(input, buffer, want)
                if (n < want) {
                    throw EOFException("Connection lost while receiving \"${header.name}\"")
                }
                remaining -= n
                batchBytes += n
            }
            FileEnd.parse(FrameIO.read(input))
        } finally {
            socket.soTimeout = 0
        }
    }

    @Throws(IOException::class)
    private fun receiveInto(handle: SinkHandle, header: FileHeader, name: String, cancel: CancelToken) {
        var handleOpen = true
        var writeError: IOException? = null
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
                    if (writeError == null) {
                        try {
                            handle.stream.write(chunk.data, 0, chunk.length)
                        } catch (e: IOException) {
                            // e.g. the storage is full: stop writing, but keep reading so the stream stays in sync
                            writeError = e
                            abortHandle(handle)
                            handleOpen = false
                        }
                    }
                    received += chunk.length
                    batchBytes += chunk.length
                    emit(header.index, name, received, header.size, force = false)
                }
            )
            if (writeError == null) {
                try {
                    handle.stream.flush()
                } catch (e: IOException) {
                    writeError = e
                    abortHandle(handle)
                    handleOpen = false
                }
            }
            emit(header.index, name, received, header.size, force = true)
            val end = FileEnd.parse(FrameIO.read(input))
            socket.soTimeout = 0

            // All bytes of this file are consumed, so the stream is in sync: nothing below ends the connection.
            val storageProblem = writeError
            var failure: String? = when {
                storageProblem != null -> "Cannot save file: ${describeError(storageProblem)}"
                end.incomplete -> "The file was incomplete on the sending phone (it changed or could not be read)"
                end.crc32 != crc.value -> "Checksum mismatch - the file was damaged in transit"
                else -> null
            }
            var location: String? = null
            if (failure == null) {
                try {
                    location = handle.commit()
                    handleOpen = false
                } catch (e: Exception) {
                    failure = "Cannot save file: ${describeError(e)}"
                }
            }
            if (failure != null) {
                if (handleOpen) {
                    abortHandle(handle)
                    handleOpen = false
                }
                reported = true
                events.onFileFinished(header.index, name, FileState.FAILED, failure, null)
                FrameIO.write(output, FileAck(header.index, false, failure).toFrame())
                return
            }
            reported = true
            events.onFileFinished(header.index, name, FileState.DONE, null, location)
            FrameIO.write(output, FileAck(header.index, true, "").toFrame())
        } catch (t: Throwable) {
            // the connection is gone (or was cancelled): never keep a partial file
            if (handleOpen) {
                abortHandle(handle)
                handleOpen = false
            }
            if (!reported) {
                val cancelled = t is CancelledException || cancel.cancelled
                events.onFileFinished(
                    header.index, name,
                    if (cancelled) FileState.CANCELLED else FileState.FAILED,
                    describeError(t), null
                )
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

    private fun abortHandle(handle: SinkHandle) {
        try {
            handle.abort()
        } catch (_: Exception) {
        }
    }
}

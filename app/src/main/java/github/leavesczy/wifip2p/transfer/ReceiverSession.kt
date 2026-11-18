package github.leavesczy.wifip2p.transfer

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/**
 * Receiver half of one TCP connection. [serve] loops over incoming batches until the connection ends.
 *
 * Files are streamed straight into the [IncomingStore] and checksummed. Two rules keep transfers
 * robust:
 *  - a connection that breaks keeps the partial bytes (the sender continues from them later);
 *  - a file that cannot be stored is refused or dropped on its own - the rest of the batch goes on.
 *
 * Only a damaged file (checksum mismatch, incomplete source) throws its partial data away.
 */
class ReceiverSession(
    private val socket: Socket,
    private val store: IncomingStore,
    private val tuning: Tuning,
    private val events: SessionEvents
) : Closeable {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()
    private val pool = ChunkPool(tuning.chunkSize, tuning.queueDepth)
    private val closed = AtomicBoolean(false)

    private var batchActive = false
    private var batchTotal = 0L
    private var batchBytes = 0L
    private var batchFileLimit = 0
    private var batchFilesSeen = 0
    private var meter = SpeedMeter()
    private var lastEmitNanos = 0L

    override fun close() {
        closed.set(true)
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun write(frame: Frame) {
        synchronized(writeLock) {
            FrameIO.write(output, frame)
        }
    }

    /** Blocks until the sender disconnects; always ends with an exception (EOF on a clean close). */
    @Throws(IOException::class)
    fun serve(cancel: CancelToken) {
        startHeartbeat()
        try {
            while (true) {
                cancel.throwIfCancelled()
                // Silence from the sender for longer than this = dead. The sender heartbeats while idle.
                socket.soTimeout = tuning.deadAfterMs.toInt()
                val frame = FrameIO.read(input)
                when (frame.type) {
                    Protocol.TYPE_HEARTBEAT -> Unit
                    Protocol.TYPE_BATCH -> onBatch(BatchInfo.parse(frame))
                    Protocol.TYPE_FILE -> onFile(FileHeader.parse(frame), cancel)
                    Protocol.TYPE_BATCH_END -> {
                        batchActive = false
                        events.onBatchFinished()
                    }

                    else -> throw ProtocolException("Unexpected message ${frame.type}")
                }
            }
        } finally {
            close()
        }
    }

    private fun startHeartbeat() {
        val thread = Thread({
            try {
                while (!closed.get()) {
                    Thread.sleep(tuning.heartbeatIntervalMs)
                    if (!closed.get()) {
                        write(Heartbeat.frame())
                    }
                }
            } catch (_: Throwable) {
                close()
            }
        }, "wfp2p-receiver-heartbeat")
        thread.isDaemon = true
        thread.start()
    }

    @Throws(IOException::class)
    private fun onBatch(info: BatchInfo) {
        val free = try {
            store.availableBytes()
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
        batchActive = false
        if (info.totalBytes > free) {
            write(
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
        write(BatchReply(true, "").toFrame())
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
        val key = header.key.ifEmpty { "$name|${header.size}|${header.lastModified}" }
        val prefix = CRC32()

        val incoming: IncomingFile = try {
            store.open(key, name, header.size, header.mimeType, header.lastModified)
        } catch (e: Exception) {
            refuse(header, name, "Cannot create file: ${describeError(e)}")
            return
        }
        try {
            // The checksum must cover what is already stored; read it before accepting the resume.
            incoming.feedStoredBytes(prefix)
        } catch (_: Exception) {
            incoming.discard()
            refuse(header, name, "The stored part of this file was damaged - it starts again on the next try")
            return
        }
        receiveInto(incoming, header, name, prefix, cancel)
    }

    /** The sender learns the reason before it sends a single byte of this file. */
    @Throws(IOException::class)
    private fun refuse(header: FileHeader, name: String, reason: String) {
        events.onFileStarted(header.index, name, header.size)
        events.onFileFinished(header.index, name, FileState.FAILED, reason, null)
        write(FileStart(header.index, 0, 0L, reason).toFrame())
    }

    @Throws(IOException::class)
    private fun receiveInto(incoming: IncomingFile, header: FileHeader, name: String, prefix: CRC32, cancel: CancelToken) {
        var open = true
        var reported = false

        // Ends this file with a failure. The partial data is kept (resumable) or thrown away (damaged).
        fun finish(message: String, keepPartial: Boolean) {
            if (keepPartial) {
                incoming.suspend()
            } else {
                incoming.discard()
            }
            open = false
            reported = true
            events.onFileFinished(header.index, name, FileState.FAILED, message, null)
            write(FileAck(header.index, false, message).toFrame())
        }

        try {
            val offset = incoming.offset
            events.onFileStarted(header.index, name, header.size)
            val newBytes = header.size - offset
            val crc = CRC32() // covers only the bytes received now; the stored part is in [prefix]
            write(FileStart(header.index, offset, prefix.value, "").toFrame())
            batchBytes += offset // stored bytes count as received

            socket.soTimeout = tuning.stallTimeoutMs
            var remaining = newBytes
            var received = offset
            var storageError: IOException? = null
            ChunkPipe.run(
                pool = pool,
                expectedBytes = remaining,
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
                    if (storageError == null) {
                        try {
                            incoming.stream.write(chunk.data, 0, chunk.length)
                        } catch (e: IOException) {
                            // e.g. the storage is full: stop writing, keep reading so the stream stays in sync
                            storageError = e
                        }
                    }
                    received += chunk.length
                    batchBytes += chunk.length
                    emit(header.index, name, received, header.size, force = false)
                }
            )
            emit(header.index, name, received, header.size, force = true)
            val end = FileEnd.parse(FrameIO.read(input))
            socket.soTimeout = tuning.deadAfterMs.toInt()

            // All bytes of this file are consumed, so the stream is in sync: nothing below ends the connection.
            val storage = storageError
            when {
                end.incomplete -> {
                    finish("The file was incomplete on the sending phone (it changed or could not be read)", keepPartial = false)
                    return
                }

                end.crc32 != Crc32Combine.combine(prefix.value, crc.value, newBytes) -> {
                    finish("Checksum mismatch - the partial data was removed, the next send starts again", keepPartial = false)
                    return
                }

                storage != null -> {
                    finish("Cannot save file: ${describeError(storage)}", keepPartial = true)
                    return
                }
            }
            val location = try {
                incoming.stream.flush()
                incoming.commit()
            } catch (e: Exception) {
                finish("Cannot save file: ${describeError(e)}", keepPartial = true)
                return
            }
            open = false
            reported = true
            events.onFileFinished(header.index, name, FileState.DONE, null, location)
            write(FileAck(header.index, true, "").toFrame())
        } catch (t: Throwable) {
            // A broken connection keeps the partial data: the sender continues from it later.
            if (open) {
                incoming.suspend()
            }
            if (!reported) {
                val cancelled = t is CancelledException || cancel.cancelled
                events.onFileFinished(
                    header.index, name,
                    if (cancelled) FileState.CANCELLED else FileState.INTERRUPTED,
                    describeError(t), null
                )
            }
            if (t is IOException) {
                throw t
            }
            throw IOException(describeError(t), t)
        }
    }
}

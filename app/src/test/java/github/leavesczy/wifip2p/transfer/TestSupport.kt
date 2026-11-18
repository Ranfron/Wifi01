package github.leavesczy.wifip2p.transfer

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32

/** Deterministic pseudo-random file content that is generated on the fly (no disk needed). */
class RandomSource(
    override val name: String,
    override val size: Long,
    private val seed: Long = name.hashCode().toLong(),
    private val throttleBytesPerSecond: Long = 0,
    private val failOpen: Boolean = false,
    override val key: String = ""
) : SendSource {
    override val mimeType: String = "application/octet-stream"
    override val lastModified: Long = 0

    override fun open(): InputStream {
        if (failOpen) {
            throw IOException("permission denied")
        }
        return object : InputStream() {
            private val random = Random(seed)
            private var position = 0L
            private val start = System.nanoTime()

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= size) {
                    return -1
                }
                val n = minOf(len.toLong(), size - position, 256 * 1024L).toInt()
                val tmp = ByteArray(n)
                random.nextBytes(tmp)
                System.arraycopy(tmp, 0, b, off, n)
                position += n
                if (throttleBytesPerSecond > 0) {
                    val due = start + position * 1_000_000_000L / throttleBytesPerSecond
                    val wait = due - System.nanoTime()
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    }
                }
                return n
            }
        }
    }

    fun sha256(): String = sha256(open())
}

/** Declares [size] bytes but can only deliver [actual] - like a provider that reports the wrong size. */
class ShortSource(override val name: String, override val size: Long, private val actual: Long) : SendSource {
    override val mimeType: String = "application/octet-stream"
    override val lastModified: Long = 0
    override fun open(): InputStream = RandomSource(name, actual).open()
}

class MemorySource(override val name: String, private val bytes: ByteArray) : SendSource {
    override val size: Long = bytes.size.toLong()
    override val mimeType: String = "application/octet-stream"
    override val lastModified: Long = 0
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    input.use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

fun sha256(file: File): String = sha256(file.inputStream())

/** Copies a complete part file into the "Downloads" folder of a test phone. */
class TestPublisher(private val dir: File) : Publisher {
    val committed = CopyOnWriteArrayList<File>()

    override fun publish(part: File, name: String, mimeType: String): String? {
        dir.mkdirs()
        val target = File(dir, FileNames.unique(name) { File(dir, it).exists() })
        part.copyTo(target)
        committed.add(target)
        return target.absolutePath
    }
}

/**
 * Real resumable storage, plus knobs to simulate the phone's problems: a full disk, a storage that
 * refuses the file, or only a limited amount of free space.
 */
class ControlledStore(staging: File, publishDir: File) : IncomingStore {
    val publisher = TestPublisher(publishDir)
    val inner = FileIncomingStore(staging, publisher)

    @Volatile
    var available: Long = Long.MAX_VALUE

    @Volatile
    var failOpen: String? = null

    /** The next [writeFailuresLeft] files fail after [writeFailAfter] bytes of writing. */
    @Volatile
    var writeFailuresLeft = 0

    @Volatile
    var writeFailAfter = 0L

    override fun availableBytes(): Long = available

    override fun open(key: String, name: String, size: Long, mimeType: String, lastModified: Long): IncomingFile {
        failOpen?.let { throw IOException(it) }
        val real = inner.open(key, name, size, mimeType, lastModified)
        val failing = writeFailuresLeft > 0
        if (failing) {
            writeFailuresLeft--
        }
        return ControlledFile(real, if (failing) writeFailAfter else -1L)
    }

    fun partials(): List<PartialFile> = inner.partials()
}

private class ControlledFile(private val real: IncomingFile, private val failAfter: Long) : IncomingFile {
    override val offset: Long
        get() = real.offset

    private var written = 0L

    override val stream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failAfter >= 0 && written + len > failAfter) {
                throw IOException("No space left on device")
            }
            written += len
            real.stream.write(b, off, len)
        }

        override fun flush() = real.stream.flush()

        override fun close() = real.stream.close()
    }

    override fun feedStoredBytes(crc: CRC32) = real.feedStoredBytes(crc)

    override fun commit(): String? = real.commit()

    override fun suspend() = real.suspend()

    override fun discard() = real.discard()
}

class FinishedFile(
    val index: Int,
    val name: String,
    val state: FileState,
    val message: String?,
    val location: String?
)

fun awaitCondition(what: String, timeoutMs: Long, describe: () -> String = { "" }, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) {
            throw AssertionError("Timed out waiting for $what. ${describe()}")
        }
        Thread.sleep(10)
    }
}

/** Records the events of one direction (files a phone sends, or files it receives). */
class RecordingEvents : SessionEvents {
    val finished = CopyOnWriteArrayList<FinishedFile>()
    val progress = CopyOnWriteArrayList<FileProgress>()

    @Volatile
    var batchesFinished = 0

    @Volatile
    var batchesStarted = 0

    override fun onBatchStarted(fileCount: Int, totalBytes: Long) {
        batchesStarted++
    }

    override fun onProgress(progress: FileProgress) {
        this.progress.add(progress)
    }

    override fun onFileFinished(index: Int, name: String, state: FileState, message: String?, location: String?) {
        finished.add(FinishedFile(index, name, state, message, location))
    }

    override fun onBatchFinished() {
        batchesFinished++
    }

    fun summary(): String = finished.map { it.name + ":" + it.state + ":" + it.message }.toString()

    fun awaitFinished(count: Int, timeoutMs: Long = 30_000) {
        awaitCondition("$count finished files (have ${finished.size})", timeoutMs, { summary() }) {
            finished.size >= count
        }
    }

    fun awaitState(name: String, state: FileState, timeoutMs: Long = 30_000) {
        awaitCondition("$name to become $state", timeoutMs, { summary() }) {
            finished.any { it.name == name && it.state == state }
        }
    }

    fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        awaitCondition(what, timeoutMs, { summary() }, condition)
    }
}

class ChannelEvent(val direction: ChannelDirection, val ready: Boolean, val peer: String?, val detail: String?)

class RecordingLink : LinkListener {
    val channels = CopyOnWriteArrayList<ChannelEvent>()
    val failures = CopyOnWriteArrayList<String>()

    @Volatile
    var listening = false

    @Volatile
    var unreachable = 0

    override fun onListening() {
        listening = true
    }

    override fun onChannel(direction: ChannelDirection, ready: Boolean, peerName: String?, detail: String?) {
        channels.add(ChannelEvent(direction, ready, peerName, detail))
    }

    override fun onHostUnreachable() {
        unreachable++
    }

    override fun onFailed(detail: String) {
        failures.add(detail)
    }

    fun readyCount(direction: ChannelDirection): Int = channels.count { it.direction == direction && it.ready }

    fun peerName(direction: ChannelDirection): String? =
        channels.lastOrNull { it.direction == direction && it.ready }?.peer

    fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        awaitCondition(what, timeoutMs, {
            channels.map { it.direction.toString() + (if (it.ready) "+" else "-") + it.detail }.toString()
        }, condition)
    }

    fun awaitReady(direction: ChannelDirection, count: Int = 1, timeoutMs: Long = 15_000) {
        await("$direction channel ready #$count", timeoutMs) { readyCount(direction) >= count }
    }
}

/** One phone of a test: its own storage (staging + published folder), its own events, its own link. */
class Phone(val name: String, val dir: File, tuning: Tuning) {
    /** Files this phone sends. */
    val sent = RecordingEvents()

    /** Files this phone receives. */
    val received = RecordingEvents()
    val link = RecordingLink()
    val store = ControlledStore(File(dir.parentFile, dir.name + "-staging"), dir)
    val duplex = DuplexLink(name, tuning, store, sent, received, link)
}

/**
 * TCP proxy that flips one bit in the client->server stream of every connection at [flipAtByte].
 * Only the connection that carries file data is long enough to be hit.
 */
class CorruptingProxy(private val targetPort: Int, private val flipAtByte: Long) : Closeable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    init {
        val t = Thread {
            try {
                while (true) {
                    val client = server.accept()
                    val upstream = Socket("127.0.0.1", targetPort)
                    pump(client, upstream, flipAtByte)
                    pump(upstream, client, -1)
                }
            } catch (_: Exception) {
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun pump(from: Socket, to: Socket, flipAt: Long) {
        val t = Thread {
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (flipAt >= 0 && flipAt >= total && flipAt < total + n) {
                        val i = (flipAt - total).toInt()
                        buffer[i] = (buffer[i].toInt() xor 0x01).toByte()
                    }
                    total += n
                    output.write(buffer, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                try {
                    to.shutdownOutput()
                } catch (_: Exception) {
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    override fun close() {
        try {
            server.close()
        } catch (_: Exception) {
        }
    }
}

/** A TCP proxy that can be cut on demand: simulates the Wi-Fi link breaking (a call, a sleeping radio). */
class CuttableProxy(private val targetPort: Int) : Closeable {
    private val server = ServerSocket(0)
    private val pairs = CopyOnWriteArrayList<Pair<Socket, Socket>>()
    val port: Int get() = server.localPort

    init {
        val t = Thread {
            try {
                while (true) {
                    val client = server.accept()
                    val upstream = Socket("127.0.0.1", targetPort)
                    pairs.add(client to upstream)
                    relay(client, upstream)
                    relay(upstream, client)
                }
            } catch (_: Exception) {
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun relay(from: Socket, to: Socket) {
        val t = Thread {
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                try {
                    to.shutdownOutput()
                } catch (_: Exception) {
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    /** Breaks every connection that passes through the proxy right now. */
    fun cutAll() {
        for ((client, upstream) in pairs) {
            try {
                client.close()
            } catch (_: Exception) {
            }
            try {
                upstream.close()
            } catch (_: Exception) {
            }
        }
        pairs.clear()
    }

    override fun close() {
        cutAll()
        try {
            server.close()
        } catch (_: Exception) {
        }
    }
}

fun freePort(): Int = ServerSocket(0).use { it.localPort }

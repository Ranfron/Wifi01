package github.leavesczy.wifip2p.transfer

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

/** Deterministic pseudo-random file content that is generated on the fly (no disk needed). */
class RandomSource(
    override val name: String,
    override val size: Long,
    private val seed: Long = name.hashCode().toLong(),
    private val throttleBytesPerSecond: Long = 0,
    private val failOpen: Boolean = false
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

    fun sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class MemorySource(override val name: String, private val bytes: ByteArray) : SendSource {
    override val size: Long = bytes.size.toLong()
    override val mimeType: String = "application/octet-stream"
    override val lastModified: Long = 0
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Stores received files in a directory. Mirrors the contract of the real Android sink. */
class DirSinks(private val dir: File) : SinkFactory {
    @Volatile
    var available: Long = Long.MAX_VALUE

    @Volatile
    var failCreate = false

    val committed = CopyOnWriteArrayList<File>()

    @Volatile
    var abortedCount = 0

    override fun availableBytes(): Long = available

    override fun create(name: String, size: Long, mimeType: String, lastModified: Long): SinkHandle {
        if (failCreate) {
            throw IOException("disk full")
        }
        val unique = FileNames.unique(name) { File(dir, it).exists() }
        val file = File(dir, unique)
        val out = FileOutputStream(file)
        return object : SinkHandle {
            override val stream: OutputStream = out

            override fun commit(): String? {
                out.close()
                committed.add(file)
                return file.absolutePath
            }

            override fun abort() {
                try {
                    out.close()
                } catch (_: Exception) {
                }
                file.delete()
                abortedCount++
            }
        }
    }
}

/** Discards everything (used for throughput measurements). */
class NullSinks : SinkFactory {
    override fun availableBytes(): Long = Long.MAX_VALUE
    override fun create(name: String, size: Long, mimeType: String, lastModified: Long): SinkHandle {
        val sink = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
        return object : SinkHandle {
            override val stream: OutputStream = sink
            override fun commit(): String? = null
            override fun abort() {}
        }
    }
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
        awaitCondition("$count finished files (have ${finished.size})", timeoutMs, { summary() }) { finished.size >= count }
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
        awaitCondition(what, timeoutMs, { channels.map { it.direction.toString() + (if (it.ready) "+" else "-") + it.detail }.toString() }, condition)
    }

    fun awaitReady(direction: ChannelDirection, count: Int = 1, timeoutMs: Long = 10_000) {
        await("$direction channel ready #$count", timeoutMs) { readyCount(direction) >= count }
    }
}

/** One phone of a test: its own storage folder, its own events, its own link. */
class Phone(val name: String, val dir: File, tuning: Tuning) {
    /** Files this phone sends. */
    val sent = RecordingEvents()

    /** Files this phone receives. */
    val received = RecordingEvents()
    val link = RecordingLink()
    val sinks = DirSinks(dir)
    val duplex = DuplexLink(name, tuning, sinks, sent, received, link)
}

/**
 * TCP proxy that flips one bit in the client->server stream of EVERY connection at [flipAtByte].
 * Only the connection that carries file data is long enough to be hit (a link has two connections).
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
                        buffer[(flipAt - total).toInt()] = (buffer[(flipAt - total).toInt()].toInt() xor 0x01).toByte()
                    }
                    total += n
                    output.write(buffer, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { to.shutdownOutput() } catch (_: Exception) {}
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

fun freePort(): Int = ServerSocket(0).use { it.localPort }

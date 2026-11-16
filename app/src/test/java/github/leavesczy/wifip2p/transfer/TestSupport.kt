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

class RecordingEvents : LinkEvents {
    val statuses = CopyOnWriteArrayList<Pair<LinkStatus, String?>>()
    val finished = CopyOnWriteArrayList<FinishedFile>()
    val progress = CopyOnWriteArrayList<FileProgress>()

    @Volatile
    var batchesFinished = 0

    @Volatile
    var batchesStarted = 0

    @Volatile
    var peerName: String? = null

    @Volatile
    var maxBytesPerSecond = 0L

    override fun onLinkStatus(status: LinkStatus, detail: String?) {
        statuses.add(status to detail)
        if (status == LinkStatus.READY) {
            peerName = detail
        }
    }

    override fun onBatchStarted(fileCount: Int, totalBytes: Long) {
        batchesStarted++
    }

    override fun onProgress(progress: FileProgress) {
        this.progress.add(progress)
        if (progress.bytesPerSecond > maxBytesPerSecond) {
            maxBytesPerSecond = progress.bytesPerSecond
        }
    }

    override fun onFileFinished(index: Int, name: String, state: FileState, message: String?, location: String?) {
        finished.add(FinishedFile(index, name, state, message, location))
    }

    override fun onBatchFinished() {
        batchesFinished++
    }

    fun readyCount(): Int = statuses.count { it.first == LinkStatus.READY }

    fun awaitReady(timeoutMs: Long = 10_000, count: Int = 1) {
        await("link READY #$count", timeoutMs) { readyCount() >= count }
    }

    fun awaitFinished(count: Int, timeoutMs: Long = 30_000) {
        await("$count finished files (have ${finished.size})", timeoutMs) { finished.size >= count }
    }

    fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for $what. statuses=$statuses finished=${finished.map { it.name + ":" + it.state + ":" + it.message }}")
            }
            Thread.sleep(10)
        }
    }
}

/** TCP proxy that flips one bit of the sender->receiver stream to prove that corruption is detected. */
class CorruptingProxy(private val targetPort: Int, private val flipAtByte: Long) : Closeable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    init {
        val t = Thread {
            try {
                val client = server.accept()
                val upstream = Socket("127.0.0.1", targetPort)
                pump(client, upstream, flipAtByte)
                pump(upstream, client, -1)
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

package github.leavesczy.wifip2p.transfer

import java.io.IOException
import java.net.Socket
import java.util.Locale

/**
 * Performance knobs of the transfer engine.
 *
 * [socketBufferSize] = 0 keeps the kernel defaults on purpose: Linux auto-tunes TCP buffers, and
 * forcing SO_SNDBUF/SO_RCVBUF is capped by net.core.*mem_max (often only ~200 KB on Android),
 * which would actually make large transfers SLOWER than auto-tuning.
 */
data class Tuning(
    /** Size of one pipeline buffer. Big enough to keep syscalls rare, small enough for smooth progress. */
    val chunkSize: Int = 512 * 1024,
    /** Number of buffers circulating between the disk thread and the network thread. */
    val queueDepth: Int = 6,
    val socketBufferSize: Int = 0,
    val connectTimeoutMs: Int = 4_000,
    val handshakeTimeoutMs: Int = 10_000,
    /** A running transfer that moves no byte for this long is aborted. */
    val stallTimeoutMs: Int = 30_000,
    /** How long the sender waits for the receiver to confirm a file (receiver may still be flushing to disk). */
    val ackTimeoutMs: Long = 60_000,
    val progressIntervalMs: Long = 100,
    /** A second phone may only take over after the first one has been gone for this long. */
    val admissionIdleMs: Long = 10_000,
    /** After this many failed connection attempts the guest tells the user that the host cannot be reached. */
    val unreachableAfterAttempts: Int = 12,
    /** How often an idle side sends a heartbeat frame. */
    val heartbeatIntervalMs: Long = 3_000,
    /** A link without any frame from the other side for this long is considered dead. */
    val deadAfterMs: Long = 20_000,
    /** Reconnect delay: starts at [reconnectMinMs], doubles up to [reconnectMaxMs]. */
    val reconnectMinMs: Long = 400,
    val reconnectMaxMs: Long = 4_000
)

class CancelledException(message: String = "Cancelled") : IOException(message)

class CancelToken {
    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    @Throws(CancelledException::class)
    fun throwIfCancelled() {
        if (cancelled) {
            throw CancelledException()
        }
    }
}

object SocketOptions {

    fun apply(socket: Socket, tuning: Tuning) {
        try {
            socket.tcpNoDelay = true // control frames must not wait for Nagle; bulk data is written in big blocks anyway
        } catch (_: Exception) {
        }
        try {
            socket.keepAlive = true
        } catch (_: Exception) {
        }
        if (tuning.socketBufferSize > 0) {
            try {
                socket.sendBufferSize = tuning.socketBufferSize
                socket.receiveBufferSize = tuning.socketBufferSize
            } catch (_: Exception) {
            }
        }
    }
}

/** Moving-window throughput meter. Not thread-safe: feed it from one thread. */
class SpeedMeter(
    private val windowMs: Long = 2_000,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val times = ArrayDeque<Long>()
    private val totals = ArrayDeque<Long>()

    fun reset() {
        times.clear()
        totals.clear()
    }

    fun record(totalBytes: Long) {
        val now = clockMs()
        times.addLast(now)
        totals.addLast(totalBytes)
        while (times.size > 2 && now - times.first() > windowMs) {
            times.removeFirst()
            totals.removeFirst()
        }
    }

    fun bytesPerSecond(): Long {
        if (times.size < 2) {
            return 0
        }
        val elapsed = times.last() - times.first()
        if (elapsed <= 0) {
            return 0
        }
        return (totals.last() - totals.first()) * 1000 / elapsed
    }
}

object Formatters {

    fun size(bytes: Long): String {
        if (bytes < 0) {
            return "?"
        }
        if (bytes < 1024) {
            return "$bytes B"
        }
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val pattern = when {
            value >= 100 -> "%.0f %s"
            value >= 10 -> "%.1f %s"
            else -> "%.2f %s"
        }
        return String.format(Locale.US, pattern, value, units[unit])
    }

    fun speed(bytesPerSecond: Long): String = size(bytesPerSecond) + "/s"

    fun duration(seconds: Long): String {
        if (seconds < 0) {
            return "--:--"
        }
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /** Remaining seconds, or -1 when unknown. */
    fun etaSeconds(remainingBytes: Long, bytesPerSecond: Long): Long {
        if (bytesPerSecond <= 0 || remainingBytes < 0) {
            return -1
        }
        return (remainingBytes + bytesPerSecond - 1) / bytesPerSecond
    }
}

object FileNames {

    private const val MAX_LENGTH = 180
    private const val ILLEGAL = "<>:\"|?*\\/"

    /**
     * Makes a file name received from the network safe to store: no path components
     * (`../../x`), no control or reserved characters, bounded length, never empty.
     */
    fun sanitize(raw: String?): String {
        var name = (raw ?: "").trim()
        val cut = maxOf(name.lastIndexOf('/'), name.lastIndexOf('\\'))
        if (cut >= 0) {
            name = name.substring(cut + 1)
        }
        val builder = StringBuilder(name.length)
        for (ch in name) {
            val bad = ch.code < 0x20 || ch.code == 0x7F || ILLEGAL.indexOf(ch) >= 0
            builder.append(if (bad) '_' else ch)
        }
        name = builder.toString().trim().trimEnd('.', ' ')
        if (name.isEmpty()) {
            return "file"
        }
        if (name.length > MAX_LENGTH) {
            val dot = name.lastIndexOf('.')
            val extension = if (dot > 0 && name.length - dot <= 12) name.substring(dot) else ""
            var base = name.substring(0, MAX_LENGTH - extension.length)
            if (base.isNotEmpty() && Character.isHighSurrogate(base.last())) {
                base = base.dropLast(1)
            }
            name = base + extension
        }
        return name
    }

    /** "photo.jpg" -> "photo (1).jpg" -> "photo (2).jpg" ... until [exists] says the name is free. */
    fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) {
            return name
        }
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = "$base ($counter)$extension"
            if (!exists(candidate)) {
                return candidate
            }
            counter++
        }
    }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }
}

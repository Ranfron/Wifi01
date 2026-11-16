package github.leavesczy.wifip2p.transfer

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A file that can be sent. The engine streams it directly - nothing is copied to a temp file first. */
interface SendSource {
    val name: String

    /** Exact size in bytes (must be known up-front). */
    val size: Long
    val mimeType: String
    val lastModified: Long

    @Throws(IOException::class)
    fun open(): InputStream
}

/** Output of one received file. */
interface SinkHandle {
    val stream: OutputStream

    /** Closes [stream] and publishes the file. Returns a location string (e.g. a content Uri) or null. */
    @Throws(IOException::class)
    fun commit(): String?

    /** Closes [stream] and deletes the partial file. Must never throw. */
    fun abort()
}

interface SinkFactory {
    /** Free space in bytes, used to refuse a batch that cannot fit. */
    fun availableBytes(): Long

    @Throws(IOException::class)
    fun create(name: String, size: Long, mimeType: String, lastModified: Long): SinkHandle
}

enum class FileState { QUEUED, ACTIVE, DONE, FAILED, CANCELLED }

data class FileProgress(
    val index: Int,
    val name: String,
    val fileBytes: Long,
    val fileSize: Long,
    val batchBytes: Long,
    val batchTotal: Long,
    val bytesPerSecond: Long
)

data class BatchResult(
    val done: Int,
    val failed: Int,
    val cancelled: Int,
    val error: String?
)

/** Callbacks may arrive on engine threads - implementations must be thread-safe. */
interface SessionEvents {
    fun onBatchStarted(fileCount: Int, totalBytes: Long) {}
    fun onFileStarted(index: Int, name: String, size: Long) {}
    fun onProgress(progress: FileProgress) {}
    fun onFileFinished(index: Int, name: String, state: FileState, message: String?, location: String?) {}
    fun onBatchFinished() {}
}

enum class LinkStatus {
    /** Host side: server socket is open and waiting for the other device. */
    LISTENING,

    /** Guest side: trying to reach the group owner. */
    CONNECTING,

    /** TCP connection established, exchanging HELLO. */
    HANDSHAKE,

    /** Both apps talk to each other; [LinkEvents.onLinkStatus] detail carries the peer's name. */
    READY,

    /** The connection ended (peer left, cancelled, network lost...). The link re-connects by itself. */
    DISCONNECTED,

    /** Unrecoverable problem (for instance the port cannot be opened). */
    FAILED
}

interface LinkEvents : SessionEvents {
    fun onLinkStatus(status: LinkStatus, detail: String?)
}

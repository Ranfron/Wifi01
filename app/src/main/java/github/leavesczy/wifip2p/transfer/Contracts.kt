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

/** The two independent byte streams of a link: what this phone sends, and what it receives. */
enum class ChannelDirection { SEND, RECEIVE }

interface LinkListener {
    /** Host side: the server socket is open. */
    fun onListening() {}

    /** A channel became usable ([ready] = true) or went down. It re-connects by itself. */
    fun onChannel(direction: ChannelDirection, ready: Boolean, peerName: String?, detail: String?)

    /** Guest side: the host does not answer although we keep trying (the app is probably not open there). */
    fun onHostUnreachable() {}

    /** Unrecoverable problem, for instance the port cannot be opened. */
    fun onFailed(detail: String)
}

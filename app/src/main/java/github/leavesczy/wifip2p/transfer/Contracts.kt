package github.leavesczy.wifip2p.transfer

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/** A file that can be sent. The engine streams it directly - nothing is copied first. */
interface SendSource {
    val name: String

    /** Exact size in bytes (must be known up-front). */
    val size: Long
    val mimeType: String
    val lastModified: Long

    /**
     * Stable id of this file across attempts. A resumed transfer announces the same key, so the
     * receiver finds the bytes it already has. Empty means "derive it from name, size and time".
     */
    val key: String
        get() = ""

    @Throws(IOException::class)
    fun open(): InputStream
}

/** One received file. Its bytes survive an interrupted transfer, so it can be resumed later. */
interface IncomingFile {
    /** Bytes stored by an earlier attempt. The sender continues from here (0 for a new file). */
    val offset: Long

    /** Feeds the [offset] stored bytes into [crc], so the checksum covers the whole file. */
    @Throws(IOException::class)
    fun feedStoredBytes(crc: CRC32)

    /** Where the new bytes are written (after the stored ones). */
    val stream: OutputStream

    /** Publishes the complete file and removes the partial data. Returns its location. */
    @Throws(IOException::class)
    fun commit(): String?

    /** Closes the stream but KEEPS the partial bytes, so a later attempt can resume. Never throws. */
    fun suspend()

    /** Closes the stream and deletes everything of this file. Never throws. */
    fun discard()
}

/** Decides where received files go, and keeps partial files so they can be resumed. */
interface IncomingStore {
    /** Free space in bytes, used to refuse a batch that cannot fit. */
    fun availableBytes(): Long

    /** Starts a new file, or continues the partial file with the same [key] and fingerprint. */
    @Throws(IOException::class)
    fun open(key: String, name: String, size: Long, mimeType: String, lastModified: Long): Opened
}

/**
 * DONE - arrived and stored. FAILED - refused or damaged, the user decides. CANCELLED - stopped by the
 * user. INTERRUPTED - the connection broke; the file can continue by itself when the phones meet again.
 */
/** What the store decided for an incoming file. */
sealed class Opened {
    /** Receive into [file]: the sender continues from [file].offset. */
    class Receiving(val file: IncomingFile) : Opened()

    /** The file is already complete here (its acknowledgement was lost earlier): nothing is sent or stored again. */
    class AlreadyStored(val location: String) : Opened()
}

enum class FileState { QUEUED, ACTIVE, DONE, FAILED, CANCELLED, INTERRUPTED }

data class FileProgress(
    /** Absolute bytes of this file (a resumed file starts above zero). */
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
    /** The other phone runs another build of the app: nothing connects until both builds match. */
    fun onIncompatible() {}

    /** A guest came with a QR code that is no longer the current one: it was refused. */
    fun onStaleCode() {}

    /** A bug in the app was caught instead of breaking the connection. For logging only. */
    fun onInternalError(error: Throwable) {}

    /** Host side: the server socket is open. */
    fun onListening() {}

    /** A channel became usable ([ready] = true) or went down. It re-connects by itself. */
    fun onChannel(direction: ChannelDirection, ready: Boolean, peerName: String?, detail: String?)

    /** Guest side: the host does not answer although we keep trying (the app is probably not open there). */
    fun onHostUnreachable() {}

    /** Unrecoverable problem, for instance the port cannot be opened. */
    fun onFailed(detail: String)
}

/**
 * Wraps the callbacks of a session: an exception thrown by the app (a UI bug, for instance) is
 * reported instead of tearing down the connection. The transfer protocol never depends on the UI.
 */
class GuardedEvents(
    private val inner: SessionEvents,
    private val report: (Throwable) -> Unit
) : SessionEvents {

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            report(t)
        }
    }

    override fun onBatchStarted(fileCount: Int, totalBytes: Long) =
        guard { inner.onBatchStarted(fileCount, totalBytes) }

    override fun onFileStarted(index: Int, name: String, size: Long) =
        guard { inner.onFileStarted(index, name, size) }

    override fun onProgress(progress: FileProgress) = guard { inner.onProgress(progress) }

    override fun onFileFinished(index: Int, name: String, state: FileState, message: String?, location: String?) =
        guard { inner.onFileFinished(index, name, state, message, location) }

    override fun onBatchFinished() = guard { inner.onBatchFinished() }
}

package github.leavesczy.wifip2p.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire protocol (v2). Everything is big-endian.
 *
 * Control messages are small length-prefixed frames: `[type:1][length:4][payload]`.
 * File contents are NOT framed: after FILE_START the sender writes exactly `size - offset` raw bytes,
 * then FILE_END carries the CRC-32 of the whole file.
 *
 *   both    : HELLO, HEARTBEAT (idle side every few seconds; the receiver also during transfers)
 *   sender  : BATCH -> (receiver: BATCH_REPLY) ->
 *             { FILE(header with key) -> (receiver: FILE_START(offset) or a refusal),
 *               raw bytes, FILE_END -> (receiver: FILE_ACK) }* -> BATCH_END
 *
 * [FILE_START.offset] lets an interrupted file continue where it stopped: the receiver keeps the
 * partial bytes, the sender sends only the rest.
 */
object Protocol {
    const val PORT = 1995
    const val MAGIC = 0x57465032 // "WFP2"
    const val VERSION = 2

    const val ROLE_SENDER = 1
    const val ROLE_RECEIVER = 2

    const val TYPE_HELLO = 1
    const val TYPE_BATCH = 2
    const val TYPE_BATCH_REPLY = 3
    const val TYPE_FILE = 4
    const val TYPE_FILE_END = 5
    const val TYPE_FILE_ACK = 6
    const val TYPE_BATCH_END = 7
    const val TYPE_FILE_START = 8
    const val TYPE_HEARTBEAT = 9

    const val MAX_PAYLOAD = 64 * 1024
    const val MAX_FILES_PER_BATCH = 1_000_000
    const val MAX_NAME_CHARS = 255
    const val MAX_KEY_CHARS = 128

    /** The role the other side must have on the same connection. */
    fun complementOf(role: Int): Int? = when (role) {
        ROLE_SENDER -> ROLE_RECEIVER
        ROLE_RECEIVER -> ROLE_SENDER
        else -> null
    }
}

open class ProtocolException(message: String) : IOException(message)

/** The two phones run different builds of the app. Reported to the user, not retried silently. */
class VersionMismatchException(message: String) : ProtocolException(message)

class Frame(val type: Int, val payload: ByteArray = EMPTY) {
    companion object {
        val EMPTY = ByteArray(0)
    }
}

object FrameIO {

    private const val HEADER_SIZE = 5

    @Throws(IOException::class)
    fun write(out: OutputStream, frame: Frame) {
        val size = frame.payload.size
        if (size > Protocol.MAX_PAYLOAD) {
            throw ProtocolException("Frame too large: $size")
        }
        // One single write() so a small control frame never gets split into several tiny TCP packets.
        val buffer = ByteArray(HEADER_SIZE + size)
        buffer[0] = frame.type.toByte()
        buffer[1] = (size ushr 24).toByte()
        buffer[2] = (size ushr 16).toByte()
        buffer[3] = (size ushr 8).toByte()
        buffer[4] = size.toByte()
        System.arraycopy(frame.payload, 0, buffer, HEADER_SIZE, size)
        out.write(buffer)
        out.flush()
    }

    @Throws(IOException::class)
    fun read(input: InputStream): Frame {
        val header = ByteArray(HEADER_SIZE)
        readFully(input, header, HEADER_SIZE)
        val type = header[0].toInt() and 0xFF
        val size = ((header[1].toInt() and 0xFF) shl 24) or
                ((header[2].toInt() and 0xFF) shl 16) or
                ((header[3].toInt() and 0xFF) shl 8) or
                (header[4].toInt() and 0xFF)
        if (size < 0 || size > Protocol.MAX_PAYLOAD) {
            throw ProtocolException("Invalid frame size: $size")
        }
        if (size == 0) {
            return Frame(type, Frame.EMPTY)
        }
        val payload = ByteArray(size)
        readFully(input, payload, size)
        return Frame(type, payload)
    }

    /** Reads up to [length] bytes. Returns less than [length] only when the stream ended. */
    @Throws(IOException::class)
    fun readUpTo(input: InputStream, buffer: ByteArray, length: Int): Int {
        var offset = 0
        while (offset < length) {
            val n = input.read(buffer, offset, length - offset)
            if (n < 0) {
                break
            }
            offset += n
        }
        return offset
    }

    /** Reads exactly [length] bytes or throws [EOFException]. */
    @Throws(IOException::class)
    fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        val n = readUpTo(input, buffer, length)
        if (n < length) {
            throw EOFException("Connection closed ($n of $length bytes)")
        }
    }
}

private inline fun encode(block: (DataOutputStream) -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream(64)
    DataOutputStream(bytes).use(block)
    return bytes.toByteArray()
}

private inline fun <T> decode(frame: Frame, expectedType: Int, block: (DataInputStream) -> T): T {
    if (frame.type != expectedType) {
        throw ProtocolException("Unexpected message type ${frame.type} (expected $expectedType)")
    }
    return DataInputStream(ByteArrayInputStream(frame.payload)).use(block)
}

/** First message sent by both sides right after the TCP connection is up. */
data class Hello(val role: Int, val deviceName: String, val version: Int = Protocol.VERSION) {

    fun toFrame(): Frame = Frame(Protocol.TYPE_HELLO, encode {
        it.writeInt(Protocol.MAGIC)
        it.writeShort(version)
        it.writeByte(role)
        it.writeUTF(deviceName.take(64))
    })

    companion object {
        fun parse(frame: Frame): Hello = decode(frame, Protocol.TYPE_HELLO) {
            if (it.readInt() != Protocol.MAGIC) {
                throw ProtocolException("The other device is not running this app")
            }
            val version = it.readUnsignedShort()
            if (version != Protocol.VERSION) {
                throw VersionMismatchException("The two phones run different versions of the app - update both")
            }
            val role = it.readUnsignedByte()
            Hello(role = role, deviceName = it.readUTF(), version = version)
        }
    }
}

data class BatchInfo(val fileCount: Int, val totalBytes: Long) {

    fun toFrame(): Frame = Frame(Protocol.TYPE_BATCH, encode {
        it.writeInt(fileCount)
        it.writeLong(totalBytes)
    })

    companion object {
        fun parse(frame: Frame): BatchInfo = decode(frame, Protocol.TYPE_BATCH) {
            val count = it.readInt()
            val total = it.readLong()
            if (count < 0 || count > Protocol.MAX_FILES_PER_BATCH || total < 0) {
                throw ProtocolException("Invalid batch description")
            }
            BatchInfo(count, total)
        }
    }
}

data class BatchReply(val accepted: Boolean, val message: String) {

    fun toFrame(): Frame = Frame(Protocol.TYPE_BATCH_REPLY, encode {
        it.writeBoolean(accepted)
        it.writeUTF(message.take(300))
    })

    companion object {
        fun parse(frame: Frame): BatchReply = decode(frame, Protocol.TYPE_BATCH_REPLY) {
            BatchReply(accepted = it.readBoolean(), message = it.readUTF())
        }
    }
}

/**
 * Announces one file. [key] identifies the file across attempts: a resumed transfer announces the
 * same key, so the receiver can find the bytes it already has.
 */
data class FileHeader(
    val index: Int,
    val name: String,
    val size: Long,
    val mimeType: String,
    val lastModified: Long,
    val key: String = ""
) {

    fun toFrame(): Frame = Frame(Protocol.TYPE_FILE, encode {
        it.writeInt(index)
        it.writeUTF(name.take(Protocol.MAX_NAME_CHARS))
        it.writeLong(size)
        it.writeUTF(mimeType.take(100))
        it.writeLong(lastModified)
        it.writeUTF(key.take(Protocol.MAX_KEY_CHARS))
    })

    companion object {
        fun parse(frame: Frame): FileHeader = decode(frame, Protocol.TYPE_FILE) {
            val index = it.readInt()
            val name = it.readUTF()
            val size = it.readLong()
            val mime = it.readUTF()
            val modified = it.readLong()
            val key = it.readUTF()
            if (index < 0 || size < 0 || key.length > Protocol.MAX_KEY_CHARS) {
                throw ProtocolException("Invalid file header")
            }
            FileHeader(index, name, size, mime, modified, key)
        }
    }
}

/**
 * The receiver's answer to a FILE header: either "send from [offset]" (0 for a new file, more for a
 * resumed one), or a refusal with a [refusal] reason, in which case no bytes are sent at all.
 */
data class FileStart(
    val index: Int,
    val offset: Long,
    val prefixCrc: Long = 0L,
    val refusal: String = "",
    /** The file is already complete on the receiving phone: no bytes are sent for it. */
    val alreadyStored: Boolean = false
) {

    val accepted: Boolean
        get() = refusal.isEmpty()

    fun toFrame(): Frame = Frame(Protocol.TYPE_FILE_START, encode {
        it.writeInt(index)
        it.writeLong(offset)
        it.writeInt(prefixCrc.toInt())
        it.writeUTF(refusal.take(300))
        it.writeBoolean(alreadyStored)
    })

    companion object {
        fun parse(frame: Frame): FileStart = decode(frame, Protocol.TYPE_FILE_START) {
            val index = it.readInt()
            val offset = it.readLong()
            val prefixCrc = it.readInt().toLong() and 0xFFFFFFFFL
            val refusal = it.readUTF()
            val alreadyStored = it.readBoolean()
            if (index < 0 || offset < 0) {
                throw ProtocolException("Invalid file start")
            }
            FileStart(index, offset, prefixCrc, refusal, alreadyStored)
        }
    }
}

/**
 * Sent by the sender after the raw bytes of a file. [crc32] is the CRC-32 of the WHOLE file (the
 * stored part included on a resume). [incomplete] = true means the sending phone could not read the
 * whole file: the missing bytes were padded so the stream stays in sync, and the file is dropped.
 */
data class FileEnd(val crc32: Long, val incomplete: Boolean = false) {

    fun toFrame(): Frame = Frame(Protocol.TYPE_FILE_END, encode {
        it.writeInt(crc32.toInt())
        it.writeBoolean(incomplete)
    })

    companion object {
        fun parse(frame: Frame): FileEnd = decode(frame, Protocol.TYPE_FILE_END) {
            val crc = it.readInt().toLong() and 0xFFFFFFFFL
            FileEnd(crc, it.readBoolean())
        }
    }
}

data class FileAck(val index: Int, val ok: Boolean, val message: String) {

    fun toFrame(): Frame = Frame(Protocol.TYPE_FILE_ACK, encode {
        it.writeInt(index)
        it.writeBoolean(ok)
        it.writeUTF(message.take(300))
    })

    companion object {
        fun parse(frame: Frame): FileAck = decode(frame, Protocol.TYPE_FILE_ACK) {
            FileAck(index = it.readInt(), ok = it.readBoolean(), message = it.readUTF())
        }
    }
}

/** Liveness signal. Sent between files and by the receiver during transfers, never inside file data. */
object Heartbeat {
    fun frame(): Frame = Frame(Protocol.TYPE_HEARTBEAT)
}

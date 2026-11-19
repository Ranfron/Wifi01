package github.leavesczy.wifip2p.transfer

import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * The identity of a file that does not depend on the moment it was picked. The same file always gets the same key,
 * so a file that already arrived is recognised when it is sent again. Name, size and the bytes at the start and at
 * the end tell different files apart in practice; the key is a fixed-length hash.
 */
object FileFingerprint {

    /** How many bytes at each end of the file are sampled. */
    const val SAMPLE_BYTES = 256 * 1024

    fun key(name: String, size: Long, head: ByteArray, tail: ByteArray): String {
        val headCrc = CRC32().apply { update(head) }.value
        val tailCrc = CRC32().apply { update(tail) }.value
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$name|$size|$headCrc|$tailCrc".toByteArray(Charsets.UTF_8))
        return "fp1-" + digest.take(20).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

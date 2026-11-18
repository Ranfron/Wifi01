package github.leavesczy.wifip2p.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The v2 messages: resume offsets, refusals, keys and the version check. */
class ProtocolV2Test {

    @Test
    fun fileStartCarriesTheResumeOffsetOrARefusal() {
        val accepted = FileStart.parse(FileStart(3, 4_194_304L, 0x9ABCDEF0L).toFrame())
        assertEquals(4_194_304L, accepted.offset)
        assertEquals(0x9ABCDEF0L, accepted.prefixCrc)
        assertTrue(accepted.accepted)
        val refused = FileStart.parse(FileStart(3, 0, 0L, "Cannot create file: disk full").toFrame())
        assertFalse(refused.accepted)
        assertEquals("Cannot create file: disk full", refused.refusal)
    }

    @Test
    fun fileHeaderCarriesTheKeyForResume() {
        val header = FileHeader(1, "a.bin", 10, "x", 5, key = "9f1c-uuid")
        assertEquals("9f1c-uuid", FileHeader.parse(header.toFrame()).key)
    }

    @Test
    fun aHelloFromAnOlderVersionIsRejectedWithAClearMessage() {
        try {
            Hello.parse(Hello(Protocol.ROLE_SENDER, "old", version = 1).toFrame())
            fail("an incompatible version must be refused")
        } catch (e: ProtocolException) {
            assertTrue(e.message!!.contains("different versions"))
        }
    }

    @Test
    fun heartbeatIsAnEmptyFrame() {
        val frame = Heartbeat.frame()
        assertEquals(Protocol.TYPE_HEARTBEAT, frame.type)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun crcOfAConcatenationIsCombinedFromItsParts() {
        val random = java.util.Random(11)
        for (len in intArrayOf(0, 1, 2, 7, 1024, 65_537, 3_000_000)) {
            val a = ByteArray(len / 3).also { random.nextBytes(it) }
            val b = ByteArray(len - a.size).also { random.nextBytes(it) }
            val whole = java.util.zip.CRC32().apply { update(a); update(b) }
            val ca = java.util.zip.CRC32().apply { update(a) }
            val cb = java.util.zip.CRC32().apply { update(b) }
            assertEquals("length $len", whole.value, Crc32Combine.combine(ca.value, cb.value, b.size.toLong()))
        }
    }
}

package github.leavesczy.wifip2p.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The identity of a picked file: stable across picks, different for different files. */
class FingerprintTest {

    private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * seed + 1).toByte() }

    @Test
    fun theSameFileGetsTheSameKeyEveryTime() {
        val head = bytes(1000, 3)
        val tail = bytes(1000, 5)
        assertEquals(FileFingerprint.key("a.mp4", 9_000, head, tail), FileFingerprint.key("a.mp4", 9_000, head, tail))
    }

    @Test
    fun differentContentWithTheSameNameAndSizeGetsADifferentKey() {
        val head = bytes(1000, 3)
        val k1 = FileFingerprint.key("a.mp4", 9_000, head, bytes(1000, 5))
        val k2 = FileFingerprint.key("a.mp4", 9_000, head, bytes(1000, 7))
        assertFalse(k1 == k2)
    }

    @Test
    fun aDifferentNameOrSizeGetsADifferentKey() {
        val head = bytes(1000, 3)
        val tail = bytes(1000, 5)
        val base = FileFingerprint.key("a.mp4", 9_000, head, tail)
        assertFalse(base == FileFingerprint.key("b.mp4", 9_000, head, tail))
        assertFalse(base == FileFingerprint.key("a.mp4", 9_001, head, tail))
    }

    @Test
    fun theKeyFitsTheProtocolLimit() {
        val key = FileFingerprint.key("x".repeat(255), Long.MAX_VALUE, bytes(10, 1), bytes(10, 2))
        assertTrue(key.length <= Protocol.MAX_KEY_CHARS)
        assertTrue(key.startsWith("fp1-"))
    }
}

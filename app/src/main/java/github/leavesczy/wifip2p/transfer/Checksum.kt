package github.leavesczy.wifip2p.transfer

/**
 * CRC-32 of a concatenation, computed from the CRCs of its parts (the zlib algorithm).
 * It lets a resumed file be checked end to end without re-reading the bytes that were sent earlier.
 */
object Crc32Combine {
    private const val POLY = 0xEDB88320L
    private const val MASK = 0xFFFFFFFFL

    /** CRC-32 of (A + B), given crc(A) = [crc1], crc(B) = [crc2] and len(B) = [len2]. */
    fun combine(crc1: Long, crc2: Long, len2: Long): Long {
        if (len2 <= 0) {
            return crc1 and MASK
        }
        val odd = LongArray(32)
        val even = LongArray(32)
        odd[0] = POLY
        var row = 1L
        for (n in 1 until 32) {
            odd[n] = row
            row = row shl 1
        }
        square(even, odd) // operator for two zero bits
        square(odd, even) // operator for four zero bits

        var a = crc1 and MASK
        var len = len2
        do {
            square(even, odd)
            if ((len and 1L) != 0L) {
                a = times(even, a)
            }
            len = len shr 1
            if (len == 0L) {
                break
            }
            square(odd, even)
            if ((len and 1L) != 0L) {
                a = times(odd, a)
            }
            len = len shr 1
        } while (len != 0L)
        return (a xor (crc2 and MASK)) and MASK
    }

    private fun times(mat: LongArray, vector: Long): Long {
        var vec = vector
        var sum = 0L
        var i = 0
        while (vec != 0L) {
            if ((vec and 1L) != 0L) {
                sum = sum xor mat[i]
            }
            vec = vec shr 1
            i++
        }
        return sum
    }

    private fun square(target: LongArray, mat: LongArray) {
        for (n in 0 until 32) {
            target[n] = times(mat, mat[n])
        }
    }
}

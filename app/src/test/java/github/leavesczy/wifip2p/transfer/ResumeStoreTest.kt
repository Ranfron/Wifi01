package github.leavesczy.wifip2p.transfer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/** The receiver's partial storage: what survives a break, a restart, a changed source, and expiry. */
class ResumeStoreTest {

    private val root: File = java.nio.file.Files.createTempDirectory("wfp2p-store").toFile()
    private var now = 1_000_000_000L
    private val staging = File(root, "staging")
    private val published = File(root, "downloads")
    private val publisher = TestPublisher(published)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store() = FileIncomingStore(staging, publisher, clock = { now })

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { (it * seed + 3).toByte() }

    @Test
    fun aBrokenTransferResumesFromTheStoredBytes() {
        val data = bytes(10_000)
        val first = store().open("k1", "f.bin", data.size.toLong(), "x", 42)
        assertEquals(0L, first.offset)
        first.stream.write(data, 0, 4_000)
        first.suspend() // the connection broke here

        now += 60_000
        val second = store().open("k1", "f.bin", data.size.toLong(), "x", 42)
        assertEquals("the sender continues from byte 4000", 4_000L, second.offset)
        val crc = CRC32()
        second.feedStoredBytes(crc)
        val expected = CRC32().also { it.update(data, 0, 4_000) }
        assertEquals("the checksum covers the stored bytes", expected.value, crc.value)

        second.stream.write(data, 4_000, 6_000)
        val location = second.commit()
        assertTrue(location != null)
        assertArrayEquals(data, File(published, "f.bin").readBytes())
        assertTrue("nothing is left in the staging folder", store().partials().isEmpty())
    }

    @Test
    fun aChangedSourceStartsAgainFromZero() {
        val first = store().open("k2", "f.bin", 100, "x", 1)
        first.stream.write(bytes(40))
        first.suspend()
        val changed = store().open("k2", "f.bin", 100, "x", lastModified = 2) // modified since
        assertEquals(0L, changed.offset)
        changed.discard()
    }

    @Test
    fun discardRemovesTheFileAndItsPartialData() {
        val file = store().open("k3", "gone.bin", 50, "x", 1)
        file.stream.write(bytes(20))
        file.suspend()
        assertEquals(1, store().partials().size)
        store().discard("k3")
        assertTrue(store().partials().isEmpty())
    }

    @Test
    fun partialsListWhatIsStoredAndNewestFirst() {
        val a = store().open("a", "a.bin", 100, "x", 1)
        a.stream.write(bytes(30))
        a.suspend()
        now += 1_000
        val b = store().open("b", "b.bin", 100, "x", 1)
        b.stream.write(bytes(70))
        b.suspend()
        val list = store().partials()
        assertEquals(listOf("b.bin", "a.bin"), list.map { it.name })
        assertEquals(70L, list[0].stored)
        assertEquals(30L, list[1].stored)
    }

    @Test
    fun partialsOlderThanFiveDaysAreDeletedAndNewerOnesStay() {
        val old = store().open("old", "old.bin", 100, "x", 1)
        old.stream.write(bytes(10))
        old.suspend()
        now += 4 * 24 * 60 * 60 * 1000L
        val recent = store().open("recent", "recent.bin", 100, "x", 1)
        recent.stream.write(bytes(10))
        recent.suspend()

        now += 2 * 24 * 60 * 60 * 1000L // old is now 6 days, recent is 2 days
        val removed = store().purgeOlderThan(5L * 24 * 60 * 60 * 1000)
        assertEquals(1, removed)
        assertEquals(listOf("recent.bin"), store().partials().map { it.name })
    }

    @Test
    fun aCompleteFileThatCouldNotBePublishedStaysResumable() {
        val failing = object : Publisher {
            override fun publish(part: File, name: String, mimeType: String): String? = throw java.io.IOException("no space")
        }
        val s = FileIncomingStore(staging, failing, clock = { now })
        val file = s.open("p", "p.bin", 10, "x", 1)
        file.stream.write(bytes(10))
        try {
            file.commit()
        } catch (_: java.io.IOException) {
            file.suspend()
        }
        val again = FileIncomingStore(staging, publisher, clock = { now }).open("p", "p.bin", 10, "x", 1)
        assertEquals("all bytes are stored: the next attempt only publishes", 10L, again.offset)
        again.commit()
        assertEquals(10L, File(published, "p.bin").length())
    }

    @Test
    fun aFreshInstallReportsRealFreeSpaceBeforeAnyFileExists() {
        // the staging folder is created on first use: its free space must not read as zero
        val fresh = FileIncomingStore(File(root, "never-created/incoming"), publisher)
        assertTrue("free space of a fresh folder", fresh.availableBytes() > 0)
        assertTrue(File(root, "never-created/incoming").isDirectory)
    }
}

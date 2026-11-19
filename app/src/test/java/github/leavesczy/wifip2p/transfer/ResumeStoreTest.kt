package github.leavesczy.wifip2p.transfer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32

/** The receiving side's memory: what survives a break, a restart, a changed source, and expiry. */
class ResumeStoreTest {

    private val root: File = java.nio.file.Files.createTempDirectory("wfp2p-store").toFile()
    private var now = 1_000_000_000L
    private val meta = File(root, "meta")
    private val downloads = File(root, "Downloads/WifiP2P")
    private val medium = FileMedium(downloads)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store() = FileIncomingStore(meta, medium, clock = { now })

    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { (it * seed + 3).toByte() }

    private fun receiving(opened: Opened): IncomingFile =
        (opened as? Opened.Receiving)?.file ?: throw AssertionError("expected a file to receive, got $opened")

    /** Files a user can see (partial files start with a dot). */
    private fun visible(folder: File): List<File> = folder.listFiles()!!.filter { !it.name.startsWith(".") }

    @Test
    fun aBrokenTransferResumesFromTheStoredBytes() {
        val data = bytes(10_000)
        val first = receiving(store().open("k1", "f.bin", 10_000, "x", 42))
        assertEquals(0L, first.offset)
        first.stream.write(data, 0, 4_000)
        first.suspend() // the connection broke here

        now += 60_000
        val second = receiving(store().open("k1", "f.bin", 10_000, "x", 42))
        assertEquals("the sender continues from byte 4000", 4_000L, second.offset)
        val crc = CRC32()
        second.feedStoredBytes(crc)
        assertEquals("the checksum covers the stored bytes", CRC32().also { it.update(data, 0, 4_000) }.value, crc.value)

        second.stream.write(data, 4_000, 6_000)
        val location = second.commit()!!
        assertArrayEquals(data, File(location).readBytes())
        assertTrue(store().partials().isEmpty())
        assertEquals("one visible file, no partial left", listOf(File(location).name), visible(downloads).map { it.name })
        assertEquals(1, downloads.listFiles()!!.size)
    }

    @Test
    fun aRestartOfTheAppKeepsThePartialFile() {
        val data = bytes(8_000)
        val file = receiving(store().open("restart", "r.bin", 8_000, "x", 3))
        file.stream.write(data, 0, 3_000)
        file.suspend()
        // a new store on the same folders: this is what the app sees after it was closed and opened again
        val restarted = FileIncomingStore(meta, FileMedium(downloads), clock = { now })
        assertEquals(listOf(3_000L), restarted.partials().map { it.stored })
        assertEquals(3_000L, receiving(restarted.open("restart", "r.bin", 8_000, "x", 3)).offset)
    }

    @Test
    fun aChangedSourceStartsAgainFromZero() {
        val first = receiving(store().open("k2", "f.bin", 100, "x", 1))
        first.stream.write(bytes(40))
        first.suspend()
        val changed = receiving(store().open("k2", "f.bin", 100, "x", lastModified = 2))
        assertEquals(0L, changed.offset)
        changed.discard()
    }

    @Test
    fun discardRemovesTheFileAndItsPartialData() {
        val file = receiving(store().open("k3", "gone.bin", 50, "x", 1))
        file.stream.write(bytes(20))
        file.suspend()
        assertEquals(1, store().partials().size)
        store().discard("k3")
        assertTrue(store().partials().isEmpty())
        assertTrue(downloads.listFiles()!!.isEmpty())
    }

    @Test
    fun partialsListWhatIsStoredNewestFirst() {
        val a = receiving(store().open("a", "a.bin", 100, "x", 1))
        a.stream.write(bytes(30))
        a.suspend()
        now += 1_000
        val b = receiving(store().open("b", "b.bin", 100, "x", 1))
        b.stream.write(bytes(70))
        b.suspend()
        val list = store().partials()
        assertEquals(listOf("b.bin", "a.bin"), list.map { it.name })
        assertEquals(70L, list[0].stored)
        assertEquals(30L, list[1].stored)
    }

    @Test
    fun partialsOlderThanFiveDaysAreDeletedAndNewerOnesStay() {
        val old = receiving(store().open("old", "old.bin", 100, "x", 1))
        old.stream.write(bytes(10))
        old.suspend()
        now += 4L * 24 * 60 * 60 * 1000
        val recent = receiving(store().open("recent", "recent.bin", 100, "x", 1))
        recent.stream.write(bytes(10))
        recent.suspend()

        now += 2L * 24 * 60 * 60 * 1000 // old is now 6 days, recent is 2 days
        assertEquals(1, store().purgeOlderThan(5L * 24 * 60 * 60 * 1000))
        assertEquals(listOf("recent.bin"), store().partials().map { it.name })
        assertEquals("the expired partial is gone from the folder too", 1, downloads.listFiles()!!.size)
    }

    @Test
    fun aFileThatIsAlreadySavedIsRecognisedUntilTheUserDeletesIt() {
        val data = bytes(500)
        val file = receiving(store().open("dup", "d.bin", 500, "x", 1))
        file.stream.write(data)
        val location = file.commit()!!

        val again = store().open("dup", "d.bin", 500, "x", 1)
        assertTrue("the same file is not stored twice", again is Opened.AlreadyStored)
        assertEquals(location, (again as Opened.AlreadyStored).location)

        File(location).delete() // the user removed it: a new copy is wanted
        assertTrue(store().open("dup", "d.bin", 500, "x", 1) is Opened.Receiving)
    }

    @Test
    fun aFailedFinishKeepsTheWholeFileForTheNextAttempt() {
        val failing = object : Medium by medium {
            override fun complete(ref: String, name: String): String = throw IOException("disk full")
        }
        val failingStore = FileIncomingStore(meta, failing, clock = { now })
        val file = receiving(failingStore.open("c", "c.bin", 10, "x", 1))
        file.stream.write(bytes(10))
        try {
            file.commit()
            fail("the finish must fail")
        } catch (_: IOException) {
            file.suspend()
        }
        val again = receiving(store().open("c", "c.bin", 10, "x", 1))
        assertEquals("all bytes are stored: the next attempt only finishes", 10L, again.offset)
        val location = again.commit()!!
        assertEquals(10L, File(location).length())
    }

    @Test
    fun theFreeSpaceOfAFreshFolderIsRealNotZero() {
        val fresh = FileMedium(File(root, "never-created/Downloads/WifiP2P"))
        assertTrue("free space of a folder that does not exist yet", fresh.freeSpace() > 0)
        assertFalse(File(root, "never-created/Downloads/WifiP2P").exists())
    }

    @Test
    fun outputIsWrittenOnlyOnce() {
        // the store never copies: after the commit the only bytes on disk are the file itself
        val data = bytes(64 * 1024)
        val file = receiving(store().open("once", "o.bin", data.size.toLong(), "x", 1))
        val out: OutputStream = file.stream
        out.write(data)
        val location = file.commit()!!
        assertEquals(data.size.toLong(), downloads.listFiles()!!.sumOf { it.length() })
        assertEquals(data.size.toLong(), File(location).length())
    }
}

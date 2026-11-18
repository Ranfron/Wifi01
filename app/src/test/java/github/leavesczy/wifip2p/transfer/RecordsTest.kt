package github.leavesczy.wifip2p.transfer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The persistent records: escaping, the outgoing ledger for resume, and the history log. */
class RecordsTest {

    private val root: File = java.nio.file.Files.createTempDirectory("wfp2p-records").toFile()
    private var now = 5_000_000_000L

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun tabLogSurvivesTabsNewlinesAndBackslashes() {
        val fields = listOf("plain", "with\ttab", "two\nlines", "back\\slash", "", "trailing\\")
        val line = TabLog.encode(fields)
        assertTrue("one record is one line", !line.contains('\n'))
        assertEquals(fields, TabLog.decode(line))
    }

    private fun entry(key: String, state: FileState = FileState.INTERRUPTED) = OutgoingEntry(
        key = key, uri = "content://media/$key", name = "$key.bin", size = 100, lastModified = 7,
        state = state, reason = "link broke", updatedAt = 0
    )

    @Test
    fun outgoingLedgerKeepsEntriesAcrossRestarts() {
        val file = File(root, "outgoing.log")
        val ledger = OutgoingLedger(file, clock = { now })
        ledger.put(entry("a"))
        ledger.put(entry("b", FileState.FAILED))
        val reopened = OutgoingLedger(file, clock = { now })
        assertEquals(setOf("a", "b"), reopened.all().map { it.key }.toSet())
        assertEquals(FileState.FAILED, reopened.get("b")!!.state)
        assertEquals("content://media/a", reopened.get("a")!!.uri)
        reopened.remove("a")
        assertNull(OutgoingLedger(file, clock = { now }).get("a"))
    }

    @Test
    fun outgoingLedgerExpiresEntriesNotTouchedForFiveDays() {
        val file = File(root, "outgoing.log")
        val ledger = OutgoingLedger(file, clock = { now })
        ledger.put(entry("old"))
        now += 6L * 24 * 60 * 60 * 1000
        ledger.put(entry("fresh"))
        val expired = ledger.purgeOlderThan(5L * 24 * 60 * 60 * 1000)
        assertEquals(listOf("old"), expired.map { it.key })
        assertEquals(listOf("fresh"), ledger.all().map { it.key })
    }

    @Test
    fun historyKeepsTheNewestEntriesOnly() {
        val history = HistoryLog(File(root, "history.log"), capacity = 3)
        for (i in 1..5) {
            history.add(HistoryEntry(i.toLong(), i % 2 == 0, "peer", "f$i.bin", i * 10L, FileState.DONE, "/x/$i", ""))
        }
        val list = HistoryLog(File(root, "history.log"), capacity = 3).list()
        assertEquals(listOf("f5.bin", "f4.bin", "f3.bin"), list.map { it.name })
        assertTrue(list[0].outgoing.not())
        assertTrue(list[1].outgoing)
        history.clear()
        assertTrue(HistoryLog(File(root, "history.log")).list().isEmpty())
    }
}

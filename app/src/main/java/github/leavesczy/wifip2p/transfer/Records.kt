package github.leavesczy.wifip2p.transfer

import java.io.File
import java.io.IOException

/** One record per line, tab-separated. The escaping keeps names with tabs or line breaks safe. */
object TabLog {

    fun encode(fields: List<String>): String = fields.joinToString("\t") { escape(it) }

    fun decode(line: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                current.append(
                    when (line[i + 1]) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> line[i + 1]
                    }
                )
                i += 2
                continue
            }
            if (c == '\t') {
                fields.add(current.toString())
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}

internal fun readRecordLines(file: File): List<String> =
    if (file.isFile) file.readLines(Charsets.UTF_8).filter { it.isNotEmpty() } else emptyList()

/** Replaces [file] by writing a temp file first and renaming it over the old one. */
internal fun writeAtomic(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (file.exists() && !file.delete()) {
        throw IOException("Cannot replace ${file.name}")
    }
    if (!tmp.renameTo(file)) {
        throw IOException("Cannot save ${file.name}")
    }
}

/**
 * A file on the SENDING side that did not arrive completely. It keeps what is needed to send it
 * again from where it stopped: the content URI (permission persisted by the app) and the [key] that
 * tells the receiver which partial data belongs to it.
 */
data class OutgoingEntry(
    val key: String,
    val uri: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val state: FileState,
    val reason: String,
    val updatedAt: Long
)

class OutgoingLedger(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var entries: LinkedHashMap<String, OutgoingEntry>? = null

    /** Newest first. */
    fun all(): List<OutgoingEntry> = synchronized(lock) { loaded().values.sortedByDescending { it.updatedAt } }

    fun get(key: String): OutgoingEntry? = synchronized(lock) { loaded()[key] }

    /** Stores or replaces the entry and stamps it with the current time. */
    fun put(entry: OutgoingEntry) {
        synchronized(lock) {
            loaded()[entry.key] = entry.copy(updatedAt = clock())
            save()
        }
    }

    fun remove(key: String): OutgoingEntry? = synchronized(lock) {
        val removed = loaded().remove(key)
        if (removed != null) {
            save()
        }
        removed
    }

    /** Removes and returns the entries that were not touched for [maxAgeMs]. */
    fun purgeOlderThan(maxAgeMs: Long): List<OutgoingEntry> = synchronized(lock) {
        val limit = clock() - maxAgeMs
        val expired = loaded().values.filter { it.updatedAt < limit }
        if (expired.isNotEmpty()) {
            expired.forEach { loaded().remove(it.key) }
            save()
        }
        expired
    }

    private fun loaded(): LinkedHashMap<String, OutgoingEntry> {
        entries?.let { return it }
        val map = LinkedHashMap<String, OutgoingEntry>()
        for (line in readRecordLines(file)) {
            decode(line)?.let { map[it.key] = it }
        }
        entries = map
        return map
    }

    private fun save() {
        val text = loaded().values.joinToString("") { TabLog.encode(encode(it)) + "\n" }
        writeAtomic(file, text)
    }

    private fun encode(e: OutgoingEntry): List<String> = listOf(
        e.key, e.uri, e.name, e.size.toString(), e.lastModified.toString(),
        e.state.name, e.reason, e.updatedAt.toString()
    )

    private fun decode(line: String): OutgoingEntry? {
        val f = TabLog.decode(line)
        if (f.size < 8) {
            return null
        }
        return try {
            OutgoingEntry(
                key = f[0], uri = f[1], name = f[2], size = f[3].toLong(), lastModified = f[4].toLong(),
                state = FileState.valueOf(f[5]), reason = f[6], updatedAt = f[7].toLong()
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** One finished file, in either direction. */
data class HistoryEntry(
    val time: Long,
    val outgoing: Boolean,
    val peer: String,
    val name: String,
    val size: Long,
    val state: FileState,
    val location: String,
    val reason: String
)

/** Persistent list of finished transfers. Keeps the newest [capacity] entries. */
class HistoryLog(private val file: File, private val capacity: Int = 500) {

    private val lock = Any()

    /** Newest first. */
    fun list(): List<HistoryEntry> = synchronized(lock) { readAll().reversed() }

    fun add(entry: HistoryEntry) {
        synchronized(lock) {
            val all = readAll() + entry
            val kept = if (all.size > capacity) all.subList(all.size - capacity, all.size) else all
            writeAtomic(file, kept.joinToString("") { TabLog.encode(encode(it)) + "\n" })
        }
    }

    fun clear() {
        synchronized(lock) {
            file.delete()
        }
    }

    private fun readAll(): List<HistoryEntry> = readRecordLines(file).mapNotNull { decode(it) }

    private fun encode(e: HistoryEntry): List<String> = listOf(
        e.time.toString(), if (e.outgoing) "1" else "0", e.peer, e.name,
        e.size.toString(), e.state.name, e.location, e.reason
    )

    private fun decode(line: String): HistoryEntry? {
        val f = TabLog.decode(line)
        if (f.size < 8) {
            return null
        }
        return try {
            HistoryEntry(
                time = f[0].toLong(), outgoing = f[1] == "1", peer = f[2], name = f[3],
                size = f[4].toLong(), state = FileState.valueOf(f[5]), location = f[6], reason = f[7]
            )
        } catch (_: Exception) {
            null
        }
    }
}

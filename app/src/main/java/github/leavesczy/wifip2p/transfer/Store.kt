package github.leavesczy.wifip2p.transfer

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32

/**
 * Where received bytes live. A partial file is an item in the medium that is hidden until it is complete (a
 * `.part` file in the Downloads folder, or a pending MediaStore entry there). Completing it gives it its real name
 * in the same place. Nothing is copied, so a received file takes its size once.
 */
interface Medium {
    /** Free bytes on the volume that holds the received files. */
    fun freeSpace(): Long

    /** Creates an empty partial item. Returns its reference (a path or a content Uri string). */
    @Throws(IOException::class)
    fun createPartial(name: String, mimeType: String): String

    /** Number of bytes stored in [ref], or -1 when the item no longer exists. */
    fun storedSize(ref: String): Long

    /** Hands the first [length] stored bytes of [ref] to [sink], in order. */
    @Throws(IOException::class)
    fun readPrefix(ref: String, length: Long, sink: (ByteArray, Int, Int) -> Unit)

    /** Opens [ref] for writing after its first [offset] bytes. Anything beyond them is dropped. */
    @Throws(IOException::class)
    fun openAt(ref: String, offset: Long): OutputStream

    /** Gives the complete item its final [name] in the same folder and makes it visible. Returns its location. */
    @Throws(IOException::class)
    fun complete(ref: String, name: String): String

    /** Whether a completed file is still there (the user may have deleted it). */
    fun exists(location: String): Boolean

    /** Deletes an item, partial or complete. Never throws. */
    fun delete(ref: String)
}

/** A folder of the file system: the Downloads folder on Android 9 and older, and a folder in tests. */
class FileMedium(private val folder: File) : Medium {

    /** The files completed by this medium (for tests and diagnostics). */
    val completed = CopyOnWriteArrayList<File>()

    override fun freeSpace(): Long {
        val existing = generateSequence(folder) { it.parentFile }.firstOrNull { it.exists() } ?: folder
        return existing.usableSpace
    }

    @Throws(IOException::class)
    override fun createPartial(name: String, mimeType: String): String {
        folder.mkdirs()
        val file = File(folder, ".${UUID.randomUUID()}.part")
        if (!file.createNewFile()) {
            throw IOException("Cannot create ${file.name}")
        }
        return file.absolutePath
    }

    override fun storedSize(ref: String): Long {
        val file = File(ref)
        return if (file.isFile) file.length() else -1L
    }

    @Throws(IOException::class)
    override fun readPrefix(ref: String, length: Long, sink: (ByteArray, Int, Int) -> Unit) {
        FileInputStream(File(ref)).use { input ->
            val buffer = ByteArray(1 shl 20)
            var left = length
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) {
                    throw IOException("Stored data is damaged")
                }
                sink(buffer, 0, n)
                left -= n
            }
        }
    }

    @Throws(IOException::class)
    override fun openAt(ref: String, offset: Long): OutputStream {
        val file = File(ref)
        if (file.length() != offset) {
            RandomAccessFile(file, "rw").use { it.setLength(offset) }
        }
        return FileOutputStream(file, true)
    }

    @Throws(IOException::class)
    override fun complete(ref: String, name: String): String {
        val file = File(ref)
        val target = File(folder, FileNames.unique(name) { File(folder, it).exists() })
        if (!file.renameTo(target)) {
            throw IOException("Cannot finish ${target.name}")
        }
        completed.add(target)
        return target.absolutePath
    }

    override fun exists(location: String): Boolean = File(location).isFile

    override fun delete(ref: String) {
        File(ref).delete()
    }
}

/** A partially received file, as shown to the user. [stored] bytes are kept. */
data class PartialFile(val key: String, val name: String, val size: Long, val stored: Long, val updatedAt: Long)

/**
 * The receiving side's memory. Three things survive a broken connection and an app restart:
 *  - the partial data of a file that did not finish (so the sender continues from it),
 *  - the list of files that were completed (so a file that arrives again is not saved twice),
 *  - the metadata that ties both to the sender's file key.
 */
class FileIncomingStore(
    private val metaDir: File,
    private val medium: Medium,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : IncomingStore {

    private class Meta(
        val key: String,
        val name: String,
        val size: Long,
        val mimeType: String,
        val lastModified: Long,
        val ref: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    private class Done(val size: Long, val lastModified: Long, val location: String, val at: Long)

    private val lock = Any()
    private var done: LinkedHashMap<String, Done>? = null

    override fun availableBytes(): Long = medium.freeSpace()

    @Throws(IOException::class)
    override fun open(key: String, name: String, size: Long, mimeType: String, lastModified: Long): Opened {
        synchronized(lock) {
            metaDir.mkdirs()
            val finished = completedMap()[key]
            if (finished != null) {
                if (finished.size == size && finished.lastModified == lastModified && medium.exists(finished.location)) {
                    return Opened.AlreadyStored(finished.location)
                }
                // the user deleted it, or the source changed: this is a new file under the same key
                completedMap().remove(key)
                saveCompleted()
            }

            val metaFile = File(metaDir, idOf(key) + ".meta")
            val old = readMeta(metaFile)
            val now = clock()
            val sameFile = old != null && old.key == key && old.size == size && old.lastModified == lastModified
            val storedBefore = if (old != null && sameFile) medium.storedSize(old.ref) else -1L
            val reuse = old != null && sameFile && storedBefore >= 0 && storedBefore <= size

            val ref: String
            val offset: Long
            if (reuse && old != null) {
                ref = old.ref
                offset = storedBefore
            } else {
                old?.let { medium.delete(it.ref) }
                ref = medium.createPartial(name, mimeType)
                offset = 0L
            }
            val createdAt = if (reuse && old != null) old.createdAt else now
            writeMeta(metaFile, Meta(key, name, size, mimeType, lastModified, ref, createdAt, now))
            val stream = medium.openAt(ref, offset)
            return Opened.Receiving(Entry(key, name, size, mimeType, lastModified, ref, createdAt, offset, stream, metaFile))
        }
    }

    /** Partial files on disk, newest first. A complete file that could not be finished is listed too. */
    fun partials(): List<PartialFile> = synchronized(lock) {
        metaFiles().mapNotNull { metaFile ->
            val meta = readMeta(metaFile) ?: return@mapNotNull null
            val stored = medium.storedSize(meta.ref)
            if (stored < 0) {
                metaFile.delete()
                null
            } else {
                PartialFile(meta.key, meta.name, meta.size, stored, meta.updatedAt)
            }
        }.sortedByDescending { it.updatedAt }
    }

    /** Deletes the partial data of one file (the user chose "clean"). */
    fun discard(key: String) {
        synchronized(lock) {
            val metaFile = File(metaDir, idOf(key) + ".meta")
            readMeta(metaFile)?.let { medium.delete(it.ref) }
            metaFile.delete()
        }
    }

    /** Deletes partial files that were not touched for [maxAgeMs]. Returns how many were removed. */
    fun purgeOlderThan(maxAgeMs: Long): Int = synchronized(lock) {
        val limit = clock() - maxAgeMs
        var removed = 0
        for (metaFile in metaFiles()) {
            val meta = readMeta(metaFile)
            if (meta == null || meta.updatedAt < limit) {
                meta?.let { medium.delete(it.ref) }
                metaFile.delete()
                if (meta != null) {
                    removed++
                }
            }
        }
        removed
    }

    private inner class Entry(
        private val key: String,
        private val name: String,
        private val size: Long,
        private val mimeType: String,
        private val lastModified: Long,
        private val ref: String,
        private val createdAt: Long,
        override val offset: Long,
        override val stream: OutputStream,
        private val metaFile: File
    ) : IncomingFile {

        @Throws(IOException::class)
        override fun feedStoredBytes(crc: CRC32) {
            if (offset > 0) {
                medium.readPrefix(ref, offset) { bytes, from, count -> crc.update(bytes, from, count) }
            }
        }

        @Throws(IOException::class)
        override fun commit(): String? = synchronized(lock) {
            stream.flush()
            stream.close()
            val location = medium.complete(ref, name)
            rememberCompleted(key, size, lastModified, location)
            metaFile.delete()
            location
        }

        override fun suspend() {
            try {
                stream.close()
            } catch (_: IOException) {
            }
            try {
                writeMeta(metaFile, Meta(key, name, size, mimeType, lastModified, ref, createdAt, clock()))
            } catch (_: IOException) {
            }
        }

        override fun discard() {
            try {
                stream.close()
            } catch (_: IOException) {
            }
            medium.delete(ref)
            metaFile.delete()
        }
    }

    // ---- the already-saved registry -----------------------------------------------------------

    private fun completedMap(): LinkedHashMap<String, Done> {
        done?.let { return it }
        val map = LinkedHashMap<String, Done>()
        val file = File(metaDir, COMPLETED_FILE)
        for (line in readRecordLines(file)) {
            val f = TabLog.decode(line)
            if (f.size >= 5) {
                try {
                    map[f[0]] = Done(f[1].toLong(), f[2].toLong(), f[3], f[4].toLong())
                } catch (_: NumberFormatException) {
                }
            }
        }
        done = map
        return map
    }

    private fun rememberCompleted(key: String, size: Long, lastModified: Long, location: String) {
        val map = completedMap()
        map.remove(key)
        map[key] = Done(size, lastModified, location, clock())
        while (map.size > MAX_COMPLETED) {
            map.remove(map.keys.first())
        }
        saveCompleted()
    }

    private fun saveCompleted() {
        val text = completedMap().entries.joinToString("") { (key, d) ->
            TabLog.encode(listOf(key, d.size.toString(), d.lastModified.toString(), d.location, d.at.toString())) + "\n"
        }
        writeAtomic(File(metaDir, COMPLETED_FILE), text)
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun metaFiles(): List<File> =
        metaDir.listFiles { file -> file.name.endsWith(".meta") }?.toList() ?: emptyList()

    private fun idOf(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun readMeta(file: File): Meta? {
        if (!file.isFile) {
            return null
        }
        return try {
            val f = TabLog.decode(file.readText(Charsets.UTF_8).trimEnd('\n', '\r'))
            if (f.size < 8) {
                null
            } else {
                Meta(
                    key = f[0],
                    name = f[1],
                    size = f[2].toLong(),
                    mimeType = f[3],
                    lastModified = f[4].toLong(),
                    ref = f[5],
                    createdAt = f[6].toLong(),
                    updatedAt = f[7].toLong()
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMeta(file: File, meta: Meta) {
        val line = TabLog.encode(
            listOf(
                meta.key, meta.name, meta.size.toString(), meta.mimeType, meta.lastModified.toString(),
                meta.ref, meta.createdAt.toString(), meta.updatedAt.toString()
            )
        )
        writeAtomic(file, line + "\n")
    }

    private companion object {
        const val COMPLETED_FILE = "completed.log"
        const val MAX_COMPLETED = 5_000
    }
}

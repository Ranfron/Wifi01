package github.leavesczy.wifip2p.transfer

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Moves a complete file to where the user can find it (Downloads on Android, a folder in tests). */
interface Publisher {
    /** Copies [part] to its final place and returns that location. The caller removes [part] afterwards. */
    @Throws(IOException::class)
    fun publish(part: File, name: String, mimeType: String): String?
}

/** A partially received file, as shown to the user. [stored] bytes are kept on disk. */
data class PartialFile(val key: String, val name: String, val size: Long, val stored: Long, val updatedAt: Long)

/**
 * Stores incoming files in a private staging folder. Each file has two pieces: `<id>.part` (the bytes
 * received so far) and `<id>.meta` (what they belong to). Because both live on disk, a broken
 * connection - and even an app restart - leaves something the sender can continue.
 */
class FileIncomingStore(
    private val stagingDir: File,
    private val publisher: Publisher,
    private val freeSpace: () -> Long = { usableSpaceOf(stagingDir) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) : IncomingStore {

    companion object {
        /**
         * Free space of the volume that will hold [dir]. A folder that does not exist yet reports 0
         * from File.usableSpace, so the nearest existing parent is asked instead.
         */
        fun usableSpaceOf(dir: File): Long {
            val existing = generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() } ?: dir
            return existing.usableSpace
        }
    }

    private class Meta(
        val key: String,
        val name: String,
        val size: Long,
        val mimeType: String,
        val lastModified: Long,
        val createdAt: Long,
        val updatedAt: Long
    )

    override fun availableBytes(): Long {
        if (!stagingDir.isDirectory) {
            stagingDir.mkdirs()
        }
        return freeSpace()
    }

    @Throws(IOException::class)
    override fun open(key: String, name: String, size: Long, mimeType: String, lastModified: Long): IncomingFile {
        if (!stagingDir.isDirectory && !stagingDir.mkdirs()) {
            throw IOException("Cannot create the storage folder")
        }
        val id = idOf(key)
        val part = File(stagingDir, "$id.part")
        val metaFile = File(stagingDir, "$id.meta")
        val old = readMeta(metaFile)
        val now = clock()
        // Same key, size and modification time = the same file: continue it. Anything else starts again.
        val sameFile = old != null && old.key == key && old.size == size && old.lastModified == lastModified
        val stored = if (sameFile && part.isFile) part.length() else 0L
        val offset = if (sameFile && stored <= size) stored else 0L
        if (offset == 0L) {
            part.delete()
        }
        val createdAt = if (sameFile && old != null) old.createdAt else now
        val meta = Meta(key, name, size, mimeType, lastModified, createdAt, now)
        writeMeta(metaFile, meta)
        val stream = FileOutputStream(part, offset > 0)
        return Entry(part, metaFile, meta, offset, stream)
    }

    /** Partial files on disk, newest first (a complete file that could not be published is listed too). */
    fun partials(): List<PartialFile> {
        val list = ArrayList<PartialFile>()
        for (metaFile in metaFiles()) {
            val meta = readMeta(metaFile) ?: continue
            val part = partOf(metaFile)
            if (!part.isFile) {
                metaFile.delete()
                continue
            }
            list.add(PartialFile(meta.key, meta.name, meta.size, part.length(), meta.updatedAt))
        }
        return list.sortedByDescending { it.updatedAt }
    }

    /** Deletes the partial data of one file (the user chose "clean"). */
    fun discard(key: String) {
        val id = idOf(key)
        File(stagingDir, "$id.part").delete()
        File(stagingDir, "$id.meta").delete()
    }

    /** Deletes partial files not touched for [maxAgeMs]. Returns how many were removed. */
    fun purgeOlderThan(maxAgeMs: Long): Int {
        val limit = clock() - maxAgeMs
        var removed = 0
        for (metaFile in metaFiles()) {
            val meta = readMeta(metaFile)
            if (meta == null || meta.updatedAt < limit) {
                partOf(metaFile).delete()
                metaFile.delete()
                if (meta != null) {
                    removed++
                }
            }
        }
        return removed
    }

    private inner class Entry(
        private val part: File,
        private val metaFile: File,
        private val meta: Meta,
        override val offset: Long,
        override val stream: OutputStream
    ) : IncomingFile {

        @Throws(IOException::class)
        override fun feedStoredBytes(crc: CRC32) {
            if (offset == 0L) {
                return
            }
            FileInputStream(part).use { input ->
                val buffer = ByteArray(1 shl 20)
                var left = offset
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) {
                        throw IOException("Stored data is damaged")
                    }
                    crc.update(buffer, 0, n)
                    left -= n
                }
            }
        }

        @Throws(IOException::class)
        override fun commit(): String? {
            stream.flush()
            stream.close()
            val location = publisher.publish(part, meta.name, meta.mimeType)
            part.delete()
            metaFile.delete()
            return location
        }

        override fun suspend() {
            try {
                stream.close()
            } catch (_: IOException) {
            }
            try {
                writeMeta(metaFile, Meta(meta.key, meta.name, meta.size, meta.mimeType, meta.lastModified, meta.createdAt, clock()))
            } catch (_: IOException) {
            }
        }

        override fun discard() {
            try {
                stream.close()
            } catch (_: IOException) {
            }
            part.delete()
            metaFile.delete()
        }
    }

    private fun metaFiles(): List<File> =
        stagingDir.listFiles { file -> file.name.endsWith(".meta") }?.toList() ?: emptyList()

    private fun partOf(metaFile: File): File = File(stagingDir, metaFile.name.removeSuffix(".meta") + ".part")

    private fun idOf(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun readMeta(file: File): Meta? {
        if (!file.isFile) {
            return null
        }
        return try {
            val fields = TabLog.decode(file.readText(Charsets.UTF_8).trimEnd('\n', '\r'))
            if (fields.size < 7) {
                null
            } else {
                Meta(
                    key = fields[0],
                    name = fields[1],
                    size = fields[2].toLong(),
                    mimeType = fields[3],
                    lastModified = fields[4].toLong(),
                    createdAt = fields[5].toLong(),
                    updatedAt = fields[6].toLong()
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMeta(file: File, meta: Meta) {
        val line = TabLog.encode(
            listOf(
                meta.key, meta.name, meta.size.toString(), meta.mimeType,
                meta.lastModified.toString(), meta.createdAt.toString(), meta.updatedAt.toString()
            )
        )
        writeAtomic(file, line + "\n")
    }
}

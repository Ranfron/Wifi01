package github.leavesczy.wifip2p.storage

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import android.media.MediaScannerConnection
import github.leavesczy.wifip2p.transfer.FileFingerprint
import github.leavesczy.wifip2p.transfer.FileNames
import github.leavesczy.wifip2p.transfer.FrameIO
import github.leavesczy.wifip2p.transfer.FileMedium
import github.leavesczy.wifip2p.transfer.Medium
import github.leavesczy.wifip2p.transfer.SendSource
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal const val DOWNLOAD_SUBFOLDER = "WifiP2P"
private const val MAX_NAME_TRIES = 50

internal fun guessMimeType(name: String): String {
    val extension = FileNames.extensionOf(name)
    val mime = if (extension.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    return mime ?: "application/octet-stream"
}

/**
 * A file chosen with the system picker or shared from another app. It is read straight from its
 * content Uri while sending. The [key] stays the same across attempts, so a resume finds the partial
 * data on the other phone.
 */
class UriSendSource private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
    override val mimeType: String,
    override val key: String
) : SendSource {

    override val lastModified: Long = 0

    override fun open(): InputStream {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    companion object {
        private const val STAGING_DIR = "outgoing"

        /** Removes leftovers of files that had to be staged (see [create]); called at app start. */
        fun clearStaging(context: Context) {
            File(context.cacheDir, STAGING_DIR).deleteRecursively()
        }

        /**
         * The file's identity for the duplicate check. It is read from both ends of the file, so the same file gets the
         * same key every time it is picked. Null when the file cannot be read this way (the key is then random).
         */
        private fun fingerprint(resolver: ContentResolver, uri: Uri, name: String, size: Long): String? {
            if (size <= 0) {
                return null
            }
            return try {
                val pfd = resolver.openFileDescriptor(uri, "r") ?: return null
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    val sample = minOf(FileFingerprint.SAMPLE_BYTES.toLong(), size).toInt()
                    val head = ByteArray(sample)
                    val headRead = FrameIO.readUpTo(input, head, sample)
                    input.channel.position(size - sample)
                    val tail = ByteArray(sample)
                    val tailRead = FrameIO.readUpTo(input, tail, sample)
                    FileFingerprint.key(name, size, head.copyOf(headRead), tail.copyOf(tailRead))
                }
            } catch (_: Exception) {
                null
            }
        }

        /** Keeps read access to [uri] across app restarts, so an interrupted file can be resumed later. */
        fun takePersistable(context: Context, uri: Uri) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // some providers (shared files) do not allow it - such a file just cannot be resumed later
            }
        }

        fun releasePersistable(context: Context, uri: Uri) {
            try {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }

        @Throws(IOException::class)
        fun create(context: Context, uri: Uri, key: String? = null): SendSource {
            // Only content:// is accepted. A file:// Uri sent by another app could point into this
            // app's private data (confused-deputy attack).
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                throw IOException("Unsupported file location")
            }
            if (uri.authority == context.packageName + ".fileprovider") {
                throw IOException("Unsupported file location")
            }
            val resolver = context.contentResolver
            var displayName: String? = null
            var size = -1L
            try {
                resolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            displayName = cursor.getString(nameIndex)
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {
                // some providers refuse the query; the descriptor below still works
            }
            val name = FileNames.sanitize(displayName ?: uri.lastPathSegment ?: "file")
            val mime = resolver.getType(uri)?.takeIf { it.contains('/') } ?: guessMimeType(name)
            if (size < 0) {
                size = try {
                    resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                } catch (e: FileNotFoundException) {
                    throw IOException("Cannot open file", e)
                }
            }
            val fileKey = key ?: fingerprint(resolver, uri, name, size) ?: UUID.randomUUID().toString()
            if (size < 0) {
                return stage(context, uri, name, mime, fileKey)
            }
            return UriSendSource(resolver, uri, name, size, mime, fileKey)
        }

        /** The provider does not know the size (streamed/virtual files): copy once to the cache to learn it. */
        private fun stage(context: Context, uri: Uri, name: String, mime: String, fileKey: String): SendSource {
            val dir = File(context.cacheDir, STAGING_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("Cannot prepare file")
            }
            val file = File(dir, UUID.randomUUID().toString())
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
                input.use { source ->
                    FileOutputStream(file).use { target ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            target.write(buffer, 0, n)
                        }
                    }
                }
            } catch (e: Exception) {
                file.delete()
                throw IOException("Cannot read file", e)
            }
            val stagedName = name
            val stagedMime = mime
            val stagedKey = fileKey
            return object : SendSource {
                override val name = stagedName
                override val size = file.length()
                override val mimeType = stagedMime
                override val lastModified = 0L
                override val key = stagedKey

                override fun open(): InputStream {
                    return object : FilterInputStream(FileInputStream(file)) {
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                file.delete()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Downloads/WifiP2P folder through MediaStore (Android 10 and newer). A partial file is a pending entry there:
 * hidden from other apps until it is complete. Completing it gives it its name and clears the pending flag, so the
 * bytes are written once and never copied.
 */
class MediaStoreMedium(private val context: Context) : Medium {

    private val resolver = context.contentResolver

    @Suppress("DEPRECATION")
    override fun freeSpace(): Long = try {
        File(Environment.getExternalStorageDirectory().path).usableSpace
    } catch (_: Exception) {
        context.filesDir.usableSpace
    }

    override fun createPartial(name: String, mimeType: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${UUID.randomUUID()}.part")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBFOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create the file in Downloads")
        return uri.toString()
    }

    override fun storedSize(ref: String): Long = try {
        resolver.openFileDescriptor(Uri.parse(ref), "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    override fun readPrefix(ref: String, length: Long, sink: (ByteArray, Int, Int) -> Unit) {
        val pfd = resolver.openFileDescriptor(Uri.parse(ref), "r") ?: throw IOException("The partial file is gone")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
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

    override fun openAt(ref: String, offset: Long): OutputStream {
        val pfd = resolver.openFileDescriptor(Uri.parse(ref), "rw") ?: throw IOException("The partial file is gone")
        val fos = FileOutputStream(pfd.fileDescriptor)
        try {
            if (fos.channel.size() > offset) {
                fos.channel.truncate(offset)
            }
            fos.channel.position(offset)
        } catch (e: IOException) {
            pfd.close()
            throw e
        }
        return object : FilterOutputStream(fos) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    pfd.close()
                }
            }
        }
    }

    override fun complete(ref: String, name: String): String {
        val uri = Uri.parse(ref)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (attempt in 0..MAX_NAME_TRIES) {
            val candidate = if (attempt == 0) name else "$base ($attempt)$ext"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, candidate)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val changed = try {
                resolver.update(uri, values, null, null)
            } catch (_: Exception) {
                0
            }
            if (changed > 0) {
                return uri.toString()
            }
        }
        throw IOException("Cannot finish $name")
    }

    override fun exists(location: String): Boolean = try {
        resolver.openFileDescriptor(Uri.parse(location), "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    override fun delete(ref: String) {
        try {
            resolver.delete(Uri.parse(ref), null, null)
        } catch (_: Exception) {
        }
    }
}

/**
 * The receiving medium: Downloads/WifiP2P. A file is written straight into that folder (the fast path) whenever the app
 * may do so: Android 10 and older with the legacy storage rights, Android 11 and newer with "All files access". Without
 * it the MediaStore path is used, which is much slower on many phones. Every partial file keeps the medium it was started
 * in, so switching the permission does not lose a file.
 */
class DownloadsMedium(private val context: Context) : Medium {

    companion object {
        /** Whether files can be written straight into Downloads (the fast path). */
        fun fastAccess(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
    }

    private val mediaStore by lazy { MediaStoreMedium(context) }
    private val folder by lazy { FileMedium(legacyFolder()) }

    private fun preferred(): Medium = if (fastAccess()) folder else mediaStore

    private fun forRef(ref: String): Medium = if (ref.startsWith("content:")) mediaStore else folder

    override fun freeSpace(): Long = preferred().freeSpace()

    @Throws(IOException::class)
    override fun createPartial(name: String, mimeType: String): String = preferred().createPartial(name, mimeType)

    override fun storedSize(ref: String): Long = forRef(ref).storedSize(ref)

    @Throws(IOException::class)
    override fun readPrefix(ref: String, length: Long, sink: (ByteArray, Int, Int) -> Unit) =
        forRef(ref).readPrefix(ref, length, sink)

    @Throws(IOException::class)
    override fun openAt(ref: String, offset: Long): OutputStream = forRef(ref).openAt(ref, offset)

    @Throws(IOException::class)
    override fun complete(ref: String, name: String): String {
        val location = forRef(ref).complete(ref, name)
        if (!location.startsWith("content:")) {
            // a file written directly is not known to MediaStore yet: let the media apps see it too
            MediaScannerConnection.scanFile(context, arrayOf(location), null, null)
        }
        return location
    }

    override fun exists(location: String): Boolean = forRef(location).exists(location)

    override fun delete(ref: String) = forRef(ref).delete(ref)

    @Suppress("DEPRECATION")
    private fun legacyFolder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBFOLDER)
}

object FileOpener {

    /** Opens a received file in a suitable app. Returns false when nothing can show it. */
    fun open(context: Context, location: String, name: String): Boolean {
        val uri = resolve(context, location) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, guessMimeType(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun openDownloadsFolder(context: Context): Boolean {
        val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** A stored path (no scheme) or a file:// Uri is handed out through the FileProvider; content Uris pass through. */
    private fun resolve(context: Context, location: String): Uri? {
        val uri = Uri.parse(location)
        val path = when (uri.scheme) {
            null -> location
            "file" -> uri.path
            else -> null
        }
        if (path != null) {
            return try {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", File(path))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return uri
    }
}

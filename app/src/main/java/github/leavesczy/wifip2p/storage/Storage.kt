package github.leavesczy.wifip2p.storage

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import github.leavesczy.wifip2p.transfer.FileNames
import github.leavesczy.wifip2p.transfer.Publisher
import github.leavesczy.wifip2p.transfer.SendSource
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

internal const val DOWNLOAD_SUBFOLDER = "WifiP2P"

internal fun guessMimeType(name: String): String {
    val extension = FileNames.extensionOf(name)
    val mime = if (extension.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    return mime ?: "application/octet-stream"
}

/**
 * The folder for received files. An unfinished file sits in it as a `.part` piece; when it is complete it is
 * renamed inside the same folder. The bytes are written once and never copied.
 */
object ReceivedFolder {
    fun of(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "received")
    }
}

/**
 * Completes a received file by renaming it in its own folder: no second copy, no extra space needed.
 * Both names are in the same folder, so the rename is instant.
 */
class SameFolderPublisher : Publisher {
    override fun publish(part: File, name: String, mimeType: String): String? {
        val folder = part.parentFile ?: throw IOException("No folder for the received file")
        val target = File(folder, FileNames.unique(name) { File(folder, it).exists() })
        if (!part.renameTo(target)) {
            throw IOException("Cannot rename the received file")
        }
        return target.absolutePath
    }
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
            val fileKey = key ?: UUID.randomUUID().toString()
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
 * Copies a received file into Downloads/WifiP2P when the user asks for it. The copy needs room for the whole
 * file again, so it is made only on request, after a free-space check.
 */
class DownloadsPublisher(private val context: Context) : Publisher {

    override fun publish(part: File, name: String, mimeType: String): String? {
        return if (Build.VERSION.SDK_INT >= 29) publishMediaStore(part, name, mimeType) else publishLegacy(part, name)
    }

    private fun publishMediaStore(part: File, name: String, mimeType: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBFOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until the copy is complete
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create a file in Downloads")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open the new file")
            out.use { target ->
                FileInputStream(part).use { source -> source.copyTo(target, 1 shl 20) }
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri.toString()
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(part: File, name: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBFOLDER
        )
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create the Downloads/$DOWNLOAD_SUBFOLDER folder")
        }
        val target = File(dir, FileNames.unique(name) { File(dir, it).exists() })
        part.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        return Uri.fromFile(target).toString()
    }
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

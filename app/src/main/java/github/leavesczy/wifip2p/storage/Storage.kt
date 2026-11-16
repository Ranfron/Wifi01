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
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import github.leavesczy.wifip2p.transfer.FileNames
import github.leavesczy.wifip2p.transfer.SendSource
import github.leavesczy.wifip2p.transfer.SinkFactory
import github.leavesczy.wifip2p.transfer.SinkHandle
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal const val DOWNLOAD_SUBFOLDER = "WifiP2P"

internal fun guessMimeType(name: String): String {
    val extension = FileNames.extensionOf(name)
    val mime = if (extension.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    return mime ?: "application/octet-stream"
}

/**
 * A file chosen with the system picker or shared from another app.
 * It is read straight from its content Uri while sending - nothing is copied to a temp file first.
 */
class UriSendSource private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
    override val mimeType: String
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

        @Throws(IOException::class)
        fun create(context: Context, uri: Uri): SendSource {
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
            if (size < 0) {
                return stage(context, uri, name, mime)
            }
            return UriSendSource(resolver, uri, name, size, mime)
        }

        /** The provider does not know the size (streamed/virtual files): copy once to the cache to learn it. */
        private fun stage(context: Context, uri: Uri, name: String, mime: String): SendSource {
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
            return object : SendSource {
                override val name = stagedName
                override val size = file.length()
                override val mimeType = stagedMime
                override val lastModified = 0L

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
 * Saves received files into the public Downloads/WifiP2P folder, so the user can find them in any
 * file manager. Written through MediaStore on Android 10+ (no storage permission needed) and as
 * plain files on Android 6-9.
 */
class DownloadsSinkFactory(private val context: Context) : SinkFactory {

    @Suppress("DEPRECATION")
    override fun availableBytes(): Long {
        return try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    override fun create(name: String, size: Long, mimeType: String, lastModified: Long): SinkHandle {
        // The MIME type is derived from the (sanitised) name on purpose: MediaStore appends an extension
        // when the claimed type and the name disagree, and a sender is not a trustworthy source for it.
        val mime = guessMimeType(name)
        return if (Build.VERSION.SDK_INT >= 29) {
            MediaStoreHandle(context.contentResolver, name, mime)
        } else {
            createLegacy(name, mime)
        }
    }

    @Suppress("DEPRECATION")
    private fun createLegacy(name: String, mime: String): SinkHandle {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBFOLDER
        )
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create the Downloads/$DOWNLOAD_SUBFOLDER folder")
        }
        val file = File(dir, FileNames.unique(name) { File(dir, it).exists() })
        return LegacyHandle(context, file, mime)
    }

    private class MediaStoreHandle(
        private val resolver: ContentResolver,
        name: String,
        mime: String
    ) : SinkHandle {
        private val uri: Uri
        override val stream: OutputStream

        init {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBFOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until the file is complete
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Cannot create a file in Downloads")
            stream = try {
                resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open the new file")
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                throw if (e is IOException) e else IOException(e.message, e)
            }
        }

        override fun commit(): String? {
            stream.close()
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, values, null, null)
            return uri.toString()
        }

        override fun abort() {
            try {
                stream.close()
            } catch (_: Exception) {
            }
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
        }
    }

    private class LegacyHandle(
        private val context: Context,
        private val file: File,
        private val mime: String
    ) : SinkHandle {
        override val stream: OutputStream = FileOutputStream(file)

        override fun commit(): String? {
            stream.close()
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            return Uri.fromFile(file).toString()
        }

        override fun abort() {
            try {
                stream.close()
            } catch (_: Exception) {
            }
            file.delete()
        }
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

    private fun resolve(context: Context, location: String): Uri? {
        val uri = Uri.parse(location)
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return try {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", File(path))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return uri
    }
}

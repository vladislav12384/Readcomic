package com.readcomic.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import androidx.core.net.toUri
import java.io.File
import java.io.IOException

object ComicOpener {
    const val DEMO_URI = "readcomic://demo"

    fun isDemo(uri: String) = uri == DEMO_URI

    /** Opens a comic; the format is detected from the content, not from the file name. */
    fun open(context: Context, uri: String): ComicSource {
        if (isDemo(uri)) return DemoComicSource()
        val pfd = seekableDescriptor(context, uri.toUri())
        val header = ByteArray(5)
        val read = try {
            Os.pread(pfd.fileDescriptor, header, 0, header.size, 0)
        } catch (e: Exception) {
            pfd.close()
            throw IOException("Cannot read $uri", e)
        }
        return if (read >= 4 && String(header, 0, 4, Charsets.ISO_8859_1) == "%PDF") {
            PdfComicSource(pfd)
        } else {
            ArchiveComicSource(pfd)
        }
    }

    /**
     * Pages are read with random access, which needs a real file. Some providers (cloud
     * storage, other apps) only hand out pipes; those are copied into the cache once.
     */
    private fun seekableDescriptor(context: Context, uri: Uri): ParcelFileDescriptor {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
        if (pfd.statSize > 0) return pfd

        val cached = File(File(context.cacheDir, "comics").apply { mkdirs() }, LibraryStore.idFor(uri.toString()))
        if (!cached.exists() || cached.length() == 0L) {
            val partial = File(cached.path + ".part")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                partial.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
            if (!partial.renameTo(cached)) throw IOException("Cannot cache $uri")
        } else {
            pfd.close()
        }
        return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    fun titleFromName(name: String?): String {
        val base = name?.substringAfterLast('/')?.substringBeforeLast('.') ?: return "Без названия"
        return base.replace('_', ' ').trim().ifEmpty { "Без названия" }
    }
}

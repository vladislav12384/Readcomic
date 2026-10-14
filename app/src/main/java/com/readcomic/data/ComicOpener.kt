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
    /**
     * Opens a comic; the format is detected from the content, not from the file name: PDF,
     * anything libarchive reads (ZIP, RAR, 7z, TAR, also compressed; EPUB), or a folder of pictures.
     */
    fun open(context: Context, uri: String): ComicSource {
        val parsed = uri.toUri()
        if (Documents.isFolder(context, parsed)) return FolderComicSource(context, parsed)
        val pfd = seekableDescriptor(context, parsed)
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
            ArchiveComicSource(pfd, unpackDir(context, uri))
        }
    }

    /**
     * Where a solid archive is unpacked to. Only the few most recently opened comics are kept;
     * the system may also clear the cache, then the comic is simply unpacked again.
     */
    private fun unpackDir(context: Context, uri: String): File {
        val root = File(context.cacheDir, "unpacked").apply { mkdirs() }
        val dir = File(root, LibraryStore.idFor(uri))
        if (dir.exists()) dir.setLastModified(System.currentTimeMillis())
        root.listFiles()
            ?.filter { it != dir }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_UNPACKED - 1)
            ?.forEach { it.deleteRecursively() }
        return dir
    }

    private const val KEEP_UNPACKED = 3

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

    /** Size in bytes as the provider reports it, 0 if unknown. */
    fun fileSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    fun titleFromName(name: String?): String {
        val base = name?.substringAfterLast('/')?.substringBeforeLast('.') ?: return "Без названия"
        return base.replace('_', ' ').trim().ifEmpty { "Без названия" }
    }
}

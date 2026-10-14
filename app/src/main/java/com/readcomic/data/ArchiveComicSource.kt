package com.readcomic.data

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * CBZ / CBR (including RAR5) / CB7 / CBT comics, read with libarchive straight from a
 * memory-mapped file, so nothing is copied and pages can be decoded in parallel.
 */
class ArchiveComicSource(private val pfd: ParcelFileDescriptor) : ComicSource {
    private val size = pfd.statSize
    private val address: Long
    private val lock = ReentrantReadWriteLock()
    private var closed = false
    private val pages: List<String>

    init {
        if (size <= 0) {
            pfd.close()
            throw IOException("Empty file")
        }
        address = try {
            Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)
        } catch (e: Exception) {
            pfd.close()
            throw IOException("Cannot map the file", e)
        }
        pages = try {
            listImages()
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException(e)
        }
        if (pages.isEmpty()) {
            close()
            throw IOException("No images in the archive")
        }
    }

    override val pageCount get() = pages.size

    override fun loadPage(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        val name = pages.getOrNull(index) ?: return null
        val bytes = lock.read {
            if (closed) return null
            readEntry(name)
        } ?: return null
        return PageDecoder.decode(bytes, maxWidth, maxHeight)
    }

    override fun readPageSizes(): List<Pair<Int, Int>?> = lock.read {
        if (closed) return emptyList()
        val indexOf = pages.withIndex().associate { it.value to it.index }
        val sizes = arrayOfNulls<Pair<Int, Int>>(pages.size)
        withArchive { archive ->
            val buffer = ByteBuffer.allocateDirect(64 * 1024)
            var head = ByteArray(HEADER_BYTES)
            while (true) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                val index = indexOf[entryName(entry) ?: continue] ?: continue
                // The header is at the start of the file; only fall back to the whole file if needed.
                var length = 0
                var size: Pair<Int, Int>? = null
                while (size == null) {
                    buffer.clear()
                    Archive.readData(archive, buffer)
                    buffer.flip()
                    val read = buffer.remaining()
                    if (read <= 0) break
                    if (length + read > head.size) head = head.copyOf(maxOf(head.size * 2, length + read))
                    buffer.get(head, length, read)
                    length += read
                    if (length >= HEADER_BYTES) size = PageDecoder.size(head, length)
                }
                sizes[index] = size ?: PageDecoder.size(head, length)
                if (head.size > HEADER_BYTES * 4) head = ByteArray(HEADER_BYTES)
            }
        }
        sizes.toList()
    }

    override fun close() {
        lock.write {
            if (closed) return
            closed = true
            try {
                Os.munmap(address, size)
            } catch (_: Exception) {
            }
            pfd.close()
        }
    }

    private fun listImages(): List<String> = withArchive { archive ->
        val names = ArrayList<String>()
        while (true) {
            val entry = Archive.readNextHeader(archive)
            if (entry == 0L) break
            if (ArchiveEntry.filetype(entry) != ArchiveEntry.AE_IFREG) continue
            val name = entryName(entry) ?: continue
            if (PageDecoder.isImageName(name)) names += name
        }
        names.sortedWith(NaturalOrder)
    }

    private fun readEntry(wanted: String): ByteArray? = withArchive { archive ->
        var entry = Archive.readNextHeader(archive)
        while (entry != 0L && entryName(entry) != wanted) {
            entry = Archive.readNextHeader(archive)
        }
        if (entry == 0L) {
            null
        } else {
            val expected = if (ArchiveEntry.sizeIsSet(entry)) ArchiveEntry.size(entry).toInt() else 0
            val out = ByteArrayOutputStream(if (expected > 0) expected else 256 * 1024)
            val buffer = ByteBuffer.allocateDirect(64 * 1024)
            val chunk = ByteArray(buffer.capacity())
            while (true) {
                // readData writes at the buffer position and advances it, like a channel read.
                buffer.clear()
                Archive.readData(archive, buffer)
                buffer.flip()
                val read = buffer.remaining()
                if (read <= 0) break
                buffer.get(chunk, 0, read)
                out.write(chunk, 0, read)
            }
            out.toByteArray()
        }
    }

    private inline fun <T> withArchive(block: (Long) -> T): T {
        val archive = Archive.readNew()
        try {
            Archive.setCharset(archive, "UTF-8".toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenMemoryUnsafe(archive, address, size)
            return block(archive)
        } catch (e: ArchiveException) {
            throw IOException(e.message, e)
        } finally {
            try {
                Archive.readFree(archive)
            } catch (_: ArchiveException) {
            }
        }
    }

    private companion object {
        const val HEADER_BYTES = 64 * 1024
    }

    private fun entryName(entry: Long): String? =
        ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.toString(Charsets.ISO_8859_1)
}

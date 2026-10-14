package com.readcomic.data

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * CBZ / CBR (including RAR5) / CB7 / CBT comics, read with libarchive straight from a
 * memory-mapped file, so nothing is copied and pages can be decoded in parallel.
 *
 * ZIP and TAR give quick access to any page. RAR and 7z archives are usually "solid": to reach a
 * page everything before it has to be decompressed. Those are unpacked once into [unpackDir]
 * (one file per page) and pages are read from there afterwards.
 */
class ArchiveComicSource(
    private val pfd: ParcelFileDescriptor,
    private val unpackDir: File?,
) : ComicSource {
    private val size = pfd.statSize
    private val address: Long
    private val lock = ReentrantReadWriteLock()
    private var closed = false

    /** Set before taking the write lock so a long pass over the archive stops early. */
    @Volatile
    private var closing = false
    private val pages: List<String>
    private val indexOf: Map<String, Int>
    private var solid = false

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
        indexOf = pages.withIndex().associate { it.value to it.index }
    }

    override val pageCount get() = pages.size

    override val needsPreparation: Boolean
        get() = solid && unpackDir != null && !File(unpackDir, COMPLETE).exists()

    override fun loadPage(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        val name = pages.getOrNull(index) ?: return null
        val bytes = lock.read {
            if (closed) return null
            unpacked(index)?.readBytes() ?: readEntry(name)
        } ?: return null
        return PageDecoder.decode(bytes, maxWidth, maxHeight)
    }

    override fun readPageSizes(onProgress: (Float) -> Unit): List<Pair<Int, Int>?> =
        if (solid && unpackDir != null) unpackAll(onProgress) else readHeaders(onProgress)

    override fun prepare(onProgress: (Float) -> Unit) {
        if (needsPreparation) unpackAll(onProgress)
    }

    override fun close() {
        closing = true
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

    private fun unpacked(index: Int): File? = unpackDir?.let { File(it, index.toString()) }?.takeIf { it.exists() }

    /** One pass reading only the start of each image: enough for its size. */
    private fun readHeaders(onProgress: (Float) -> Unit): List<Pair<Int, Int>?> = lock.read {
        if (closed) return emptyList()
        val sizes = arrayOfNulls<Pair<Int, Int>>(pages.size)
        var done = 0
        withArchive { archive ->
            val buffer = ByteBuffer.allocateDirect(64 * 1024)
            var head = ByteArray(HEADER_BYTES)
            while (!closing) {
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
                done++
                onProgress(done.toFloat() / pages.size)
            }
        }
        sizes.toList()
    }

    /** One pass that writes every page into [unpackDir] and measures it on the way. */
    private fun unpackAll(onProgress: (Float) -> Unit): List<Pair<Int, Int>?> = lock.read {
        val dir = unpackDir ?: return emptyList()
        if (closed) return emptyList()
        dir.mkdirs()
        val sizes = arrayOfNulls<Pair<Int, Int>>(pages.size)
        var done = 0
        withArchive { archive ->
            val buffer = ByteBuffer.allocateDirect(256 * 1024)
            val chunk = ByteArray(buffer.capacity())
            while (!closing) {
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                val index = indexOf[entryName(entry) ?: continue] ?: continue
                val bytes = readData(archive, entry, buffer, chunk)
                sizes[index] = PageDecoder.size(bytes)
                val target = File(dir, index.toString())
                if (!target.exists()) {
                    val partial = File(dir, "$index.part")
                    partial.writeBytes(bytes)
                    partial.renameTo(target)
                }
                done++
                onProgress(done.toFloat() / pages.size)
            }
        }
        if (!closing && done == pages.size) File(dir, COMPLETE).createNewFile()
        sizes.toList()
    }

    private fun listImages(): List<String> = withArchive { archive ->
        val names = ArrayList<String>()
        var first = true
        while (true) {
            val entry = Archive.readNextHeader(archive)
            if (entry == 0L) break
            if (first) {
                // Only formats with a central directory (ZIP) or no compression (TAR) allow quick random access.
                val format = Archive.format(archive) and Archive.FORMAT_BASE_MASK
                solid = format != Archive.FORMAT_ZIP && format != Archive.FORMAT_TAR
                first = false
            }
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
        if (entry == 0L) null else readData(archive, entry, ByteBuffer.allocateDirect(64 * 1024), ByteArray(64 * 1024))
    }

    private fun readData(archive: Long, entry: Long, buffer: ByteBuffer, chunk: ByteArray): ByteArray {
        val expected = if (ArchiveEntry.sizeIsSet(entry)) ArchiveEntry.size(entry).toInt() else 0
        val out = ByteArrayOutputStream(if (expected > 0) expected else 256 * 1024)
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
        return out.toByteArray()
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

    private fun entryName(entry: Long): String? =
        ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.toString(Charsets.ISO_8859_1)

    private companion object {
        const val HEADER_BYTES = 64 * 1024
        const val COMPLETE = ".complete"
    }
}

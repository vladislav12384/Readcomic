package com.readcomic.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/** A file or folder inside a folder the reader gave access to (Storage Access Framework). */
data class Document(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modified: Long,
) {
    val isFolder get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

object Documents {
    /** What is directly inside [folder]: a tree URI from the folder picker, or a folder inside one. */
    fun children(context: Context, folder: Uri): List<Document> {
        val parentId = if (DocumentsContract.isDocumentUri(context, folder)) {
            DocumentsContract.getDocumentId(folder)
        } else {
            DocumentsContract.getTreeDocumentId(folder)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folder, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<Document>()
        context.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                result += Document(
                    uri = DocumentsContract.buildDocumentUriUsingTree(folder, id),
                    name = cursor.getString(1) ?: "",
                    mimeType = cursor.getString(2) ?: "",
                    size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                    modified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                )
            }
        } ?: throw IOException("Cannot list $folder")
        return result
    }

    /** True for a folder reached through a folder the reader picked. */
    fun isFolder(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) return false
        if (!DocumentsContract.isDocumentUri(context, uri)) return true
        val columns = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
        } ?: false
    }
}

/** A folder of pictures read as one comic, pages in natural name order. */
class FolderComicSource(private val context: Context, folder: Uri) : ComicSource {
    private val pages: List<Uri>
    private val comicInfo: Uri?

    init {
        val children = Documents.children(context, folder).filter { !it.isFolder }
        pages = children.filter { PageDecoder.isImageName(it.name) }
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            .map { it.uri }
        comicInfo = children.firstOrNull { it.name.equals("ComicInfo.xml", ignoreCase = true) }?.uri
        if (pages.isEmpty()) throw IOException("No pictures in the folder")
    }

    override val pageCount get() = pages.size

    override val metadata: ComicMetadata? by lazy {
        comicInfo?.let { uri ->
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrNull()
                ?.let(ComicMetadata::fromComicInfo)
        }
    }

    override fun loadPage(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        val uri = pages.getOrNull(index) ?: return null
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return PageDecoder.decode(bytes, maxWidth, maxHeight)
    }

    override fun readPageSizes(onProgress: (Float) -> Unit): List<Pair<Int, Int>?> = pages.mapIndexed { index, uri ->
        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(HEADER_BYTES)
                var length = 0
                while (length < buffer.size) {
                    val read = input.read(buffer, length, buffer.size - length)
                    if (read <= 0) break
                    length += read
                }
                buffer to length
            }
        }.getOrNull()
        onProgress((index + 1f) / pages.size)
        head?.let { (bytes, length) -> PageDecoder.size(bytes, length) }
    }

    override fun close() = Unit

    private companion object {
        const val HEADER_BYTES = 64 * 1024
    }
}

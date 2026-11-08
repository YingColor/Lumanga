package com.yingcolor.comicviewer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.yingcolor.comicviewer.util.isImageName
import com.yingcolor.comicviewer.util.naturalCompare
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

/**
 * One opened book, regardless of how its pages are stored. A `.zip` / `.cbz`
 * archive and a plain folder of images look identical from here, so the reader,
 * the cover pipeline and the continuous-scroll mode need no special cases.
 */
interface PageSource : Closeable {
    val pageCount: Int

    fun pageName(index: Int): String

    /** Only the first few KB, enough for an image header. */
    fun readHeader(index: Int, limit: Int = HEADER_BYTES): ByteArray?

    fun readPage(index: Int): ByteArray

    companion object {
        const val HEADER_BYTES = 64 * 1024

        fun open(context: Context, book: Book): PageSource = when (book.kind) {
            BookKind.FOLDER -> FolderReader.open(context, book.uri)
            BookKind.ARCHIVE -> ArchiveReader.open(context, book.uri)
        }
    }
}

/**
 * Lists the images inside one directory of the user-picked tree.
 *
 * `DocumentFile` cannot list a sub-directory reliably (a child uri does not
 * carry the tree root id), so the child-documents uri is built from the document
 * id directly — this is what the Storage Access Framework expects, and it works
 * for every provider that exposes a directory as a document.
 */
object FolderPages {
    data class Entry(val uri: Uri, val name: String)

    fun images(context: Context, dir: Uri): List<Entry> {
        val docId = runCatching { DocumentsContract.getDocumentId(dir) }.getOrNull() ?: return emptyList()
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(dir, docId)
        }.getOrNull() ?: return emptyList()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val out = ArrayList<Entry>()
        runCatching {
            context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(projection[0])
                val nameIndex = cursor.getColumnIndex(projection[1])
                val mimeIndex = cursor.getColumnIndex(projection[2])
                if (idIndex < 0 || nameIndex < 0) return@use
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (!isImageName(name)) continue
                    out.add(Entry(DocumentsContract.buildDocumentUriUsingTree(dir, id), name))
                }
            }
        }
        out.sortWith { a, b -> naturalCompare(a.name, b.name) }
        return out
    }
}

/**
 * A book that is simply a folder of images (already-extracted comics, Webtoon
 * episode dumps, scanned pages exported as files).
 *
 * Pages are read one at a time through the provider and decoded in memory, the
 * same way archive pages are: nothing is copied to another directory.
 */
class FolderReader private constructor(
    private val context: Context,
    private val entries: List<FolderPages.Entry>
) : PageSource {

    override val pageCount: Int get() = entries.size

    override fun pageName(index: Int): String = entries[index].name

    override fun readHeader(index: Int, limit: Int): ByteArray? {
        val entry = entries[index]
        return runCatching {
            context.contentResolver.openInputStream(entry.uri)?.use { input ->
                val buffer = ByteArray(limit)
                var total = 0
                while (total < limit) {
                    val read = input.read(buffer, total, limit - total)
                    if (read < 0) break
                    total += read
                }
                when {
                    total <= 0 -> null
                    total == limit -> buffer
                    else -> buffer.copyOf(total)
                }
            }
        }.getOrNull()
    }

    @Throws(IOException::class)
    override fun readPage(index: Int): ByteArray {
        val entry = entries[index]
        val stream = context.contentResolver.openInputStream(entry.uri)
            ?: throw IOException("cannot open ${entry.name}")
        val out = ByteArrayOutputStream(INITIAL_BUFFER)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_PAGE_BYTES) {
                    throw ArchiveException(ArchiveException.Kind.PAGE_TOO_LARGE, "single page exceeds limit")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    override fun close() = Unit

    companion object {
        private const val MAX_PAGE_BYTES = 96L * 1024 * 1024
        private const val INITIAL_BUFFER = 256 * 1024

        fun open(context: Context, dir: Uri): FolderReader {
            val entries = FolderPages.images(context, dir)
            if (entries.isEmpty()) {
                throw ArchiveException(ArchiveException.Kind.NO_IMAGES, "no images in folder")
            }
            return FolderReader(context.applicationContext, entries)
        }
    }
}

package com.yingcolor.lumanga.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.yingcolor.lumanga.util.isImageName
import com.yingcolor.lumanga.util.naturalCompare
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel







interface PageSource : Closeable {
    val pageCount: Int

    fun pageName(index: Int): String

    
    fun readHeader(index: Int, limit: Int = HEADER_BYTES): ByteArray?

    fun readPage(index: Int): ByteArray

    companion object {
        const val HEADER_BYTES = 64 * 1024
        const val MAX_PAGE_BYTES = 96L * 1024 * 1024

        fun open(context: Context, book: Book): PageSource = when (book.kind) {
            BookKind.FOLDER -> FolderReader.open(context, book.uri)
            BookKind.RAR -> RarReader.open(context, book.uri)
            BookKind.SEVEN_ZIP -> SevenZReader.open(context, book.uri)
            BookKind.ARCHIVE -> ArchiveReader.open(context, book.uri)
        }
    }
}


internal class SeekableSource(
    val channel: SeekableByteChannel,
    private val handles: List<Closeable>
) : Closeable {
    override fun close() {
        runCatching { channel.close() }
        handles.forEach { runCatching { it.close() } }
    }
}






@Throws(IOException::class)
internal fun openSeekable(context: Context, uri: Uri): SeekableSource {
    val assetFd = context.contentResolver.openAssetFileDescriptor(uri, "r")
        ?: throw IOException("asset descriptor unavailable")
    val fd = assetFd.fileDescriptor ?: run {
        runCatching { assetFd.close() }
        throw IOException("file descriptor unavailable")
    }
    val fis = try {
        FileInputStream(fd)
    } catch (e: Exception) {
        runCatching { assetFd.close() }
        throw e
    }
    val offset = assetFd.startOffset
    val channel: SeekableByteChannel = if (offset != 0L) OffsetChannel(fis.channel, offset) else fis.channel
    return SeekableSource(channel, listOf(fis, assetFd))
}


internal class OffsetChannel(
    private val delegate: FileChannel,
    private val offset: Long
) : SeekableByteChannel {
    override fun read(dst: ByteBuffer): Int = delegate.read(dst)
    override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
    override fun position(): Long = (delegate.position() - offset).coerceAtLeast(0L)
    override fun position(newPosition: Long): SeekableByteChannel {
        delegate.position(newPosition + offset)
        return this
    }
    override fun size(): Long = (delegate.size() - offset).coerceAtLeast(0L)
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
    override fun isOpen(): Boolean = delegate.isOpen
    override fun close() = delegate.close()
}





class SevenZReader private constructor(
    private val file: SevenZFile,
    private val handle: Closeable,
    private val entries: List<SevenZArchiveEntry>
) : PageSource {

    private val ioLock = Any()

    override val pageCount: Int get() = entries.size

    override fun pageName(index: Int): String =
        entries[index].name.substringAfterLast('/').substringAfterLast('\\')

    override fun readHeader(index: Int, limit: Int): ByteArray? = synchronized(ioLock) {
        val entry = entries[index]
        val stream = runCatching { file.getInputStream(entry) }.getOrNull()
            ?: return@synchronized null
        runCatching {
            stream.use { input ->
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
    override fun readPage(index: Int): ByteArray = synchronized(ioLock) {
        val entry = entries[index]
        if (entry.size > PageSource.MAX_PAGE_BYTES) {
            throw ArchiveException(ArchiveException.Kind.PAGE_TOO_LARGE, "single page exceeds limit")
        }
        val stream = file.getInputStream(entry) ?: throw IOException("cannot open ${entry.name}")
        val out = ByteArrayOutputStream(if (entry.size > 0) entry.size.toInt() else 256 * 1024)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > PageSource.MAX_PAGE_BYTES) {
                    throw ArchiveException(ArchiveException.Kind.PAGE_TOO_LARGE, "inflated page exceeds limit")
                }
                out.write(buffer, 0, read)
            }
        }
        out.toByteArray()
    }

    override fun close() {
        runCatching { file.close() }
        runCatching { handle.close() }
    }

    companion object {
        fun open(context: Context, uri: Uri): SevenZReader {
            val source = openSeekable(context, uri)
            try {
                val file = SevenZFile(source.channel)
                val entries = file.entries
                    .filter { !it.isDirectory }
                    .filter { isImageName(it.name) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                if (entries.isEmpty()) {
                    runCatching { file.close() }
                    throw ArchiveException(ArchiveException.Kind.NO_IMAGES, "no images inside 7z")
                }
                return SevenZReader(file, source, entries)
            } catch (e: ArchiveException) {
                runCatching { source.close() }
                throw e
            } catch (e: Exception) {
                runCatching { source.close() }
                throw ArchiveException(ArchiveException.Kind.CORRUPT, "7z: ${e.message}", e)
            }
        }
    }
}










class RarReader private constructor(
    private val archive: Archive,
    private val closeables: List<Closeable>,
    private val headers: List<FileHeader>
) : PageSource {

    private val ioLock = Any()

    override val pageCount: Int get() = headers.size

    override fun pageName(index: Int): String =
        headers[index].fileName.substringAfterLast('/').substringAfterLast('\\')

    override fun readHeader(index: Int, limit: Int): ByteArray? = synchronized(ioLock) {
        val header = headers[index]
        if (header.isEncrypted) {
            throw ArchiveException(ArchiveException.Kind.ENCRYPTED, "encrypted rar entry")
        }
        val capped = CappedStream(limit)
        
        
        runCatching { archive.extractFile(header, capped) }
        val bytes = capped.toByteArray()
        if (bytes.isEmpty()) null else bytes
    }

    @Throws(IOException::class)
    override fun readPage(index: Int): ByteArray = synchronized(ioLock) {
        val header = headers[index]
        if (header.isEncrypted) {
            throw ArchiveException(ArchiveException.Kind.ENCRYPTED, "encrypted rar entry")
        }
        if (header.fullUnpackSize > PageSource.MAX_PAGE_BYTES) {
            throw ArchiveException(ArchiveException.Kind.PAGE_TOO_LARGE, "single page exceeds limit")
        }
        val out = ByteArrayOutputStream(
            if (header.fullUnpackSize > 0) header.fullUnpackSize.toInt() else 256 * 1024
        )
        try {
            archive.extractFile(header, out)
        } catch (e: Exception) {
            throw ArchiveException(ArchiveException.Kind.CORRUPT, "rar: ${e.message}", e)
        }
        out.toByteArray()
    }

    override fun close() {
        runCatching { archive.close() }
        closeables.forEach { runCatching { it.close() } }
    }

    
    private class CappedStream(private val limit: Int) : OutputStream() {
        private val buffer = ByteArray(limit)
        private var size = 0

        override fun write(b: Int) {
            if (size >= limit) throw StopExtraction()
            buffer[size++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (size + len > limit) throw StopExtraction()
            System.arraycopy(b, off, buffer, size, len)
            size += len
        }

        fun toByteArray(): ByteArray = if (size == 0) ByteArray(0) else buffer.copyOf(size)
    }

    private class StopExtraction : RuntimeException("header bytes captured")

    companion object {
        fun open(context: Context, uri: Uri): RarReader {
            
            val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            if (pfd != null) {
                val direct = runCatching { Archive(File("/proc/self/fd/${pfd.fd}")) }.getOrNull()
                if (direct != null) {
                    return build(direct, listOf(Closeable { pfd.close() }))
                }
                runCatching { pfd.close() }
            }
            
            
            val dir = File(context.cacheDir, "rar")
            runCatching { if (!dir.exists()) dir.mkdirs() }
            val temp = File(dir, "open-${System.nanoTime()}.rar")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { out -> input.copyTo(out, 128 * 1024) }
                } ?: throw IOException("cannot open rar")
                val archive = Archive(temp)
                return build(archive, listOf(Closeable { runCatching { temp.delete() } }))
            } catch (e: Exception) {
                runCatching { temp.delete() }
                throw when (e) {
                    is ArchiveException -> e
                    else -> ArchiveException(ArchiveException.Kind.CORRUPT, "rar: ${e.message}", e)
                }
            }
        }

        private fun build(archive: Archive, closeables: List<Closeable>): RarReader {
            val headers = archive.fileHeaders
                .asSequence()
                .filter { !it.isDirectory }
                .filter { isImageName(it.fileName) }
                .sortedWith { a, b -> naturalCompare(a.fileName, b.fileName) }
                .toList()
            if (headers.isEmpty()) {
                runCatching { archive.close() }
                closeables.forEach { runCatching { it.close() } }
                throw ArchiveException(ArchiveException.Kind.NO_IMAGES, "no images inside rar")
            }
            return RarReader(archive, closeables, headers)
        }
    }
}









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
                if (total > PageSource.MAX_PAGE_BYTES) {
                    throw ArchiveException(ArchiveException.Kind.PAGE_TOO_LARGE, "single page exceeds limit")
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    override fun close() = Unit

    companion object {
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

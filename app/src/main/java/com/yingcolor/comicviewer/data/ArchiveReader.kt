package com.yingcolor.comicviewer.data

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import com.yingcolor.comicviewer.util.isImageName
import com.yingcolor.comicviewer.util.naturalCompare
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonReadableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads a ZIP/CBZ archive in place through a seekable file descriptor.
 *
 * Nothing is extracted to disk: only the requested page is inflated into a byte array
 * in memory, decoded into a Bitmap, and then released. The archive itself is never copied,
 * so even multi-gigabyte books cost no extra storage.
 */
class ArchiveReader private constructor(
    private val zip: ZipFile,
    private val source: Closeable,
    val pages: List<ZipArchiveEntry>
) : PageSource {

    override val pageCount: Int get() = pages.size
    private val ioLock = Any()

    override fun pageName(index: Int): String = pages[index].name.substringAfterLast('/')

    /**
     * Inflates only the first few KB of an entry. Image headers (PNG IHDR, JPEG
     * SOF, WebP VP8) live at the very beginning, so a continuous-scroll reader
     * can learn the size of every page of a 300-strip comic without inflating
     * hundreds of multi-megabyte images. Nothing is written to disk.
     */
    override fun readHeader(index: Int, limit: Int): ByteArray? = synchronized(ioLock) {
        val stream = runCatching { zip.getInputStream(pages[index]) }.getOrNull()
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
    override fun readPage(index: Int): ByteArray {
        val entry = pages[index]
        // ZipFile.getInputStream shares the underlying channel, so reads must be serialised.
        synchronized(ioLock) {
            if (entry.size > MAX_PAGE_BYTES) {
                throw ArchiveException(ArchiveException.Kind.PAGE_TOO_LARGE, "single page exceeds limit")
            }
            val stream = try {
                zip.getInputStream(entry)
            } catch (e: UnsupportedZipFeatureException) {
                throw ArchiveException(ArchiveException.Kind.ENCRYPTED, "encrypted or unsupported compression", e)
            } ?: throw IOException("cannot open entry ${entry.name}")

            // ZIP entries know their uncompressed size. Pre-sizing the buffer
            // avoids the repeated grow-and-copy cycle that a 10 MB scan used to
            // trigger (256 KB -> 512 KB -> ... -> 10 MB).
            val declared = entry.size
            val initial = if (declared > 0) {
                declared.toInt().coerceIn(INITIAL_BUFFER, MAX_PREALLOC)
            } else INITIAL_BUFFER
            val out = ByteArrayOutputStream(initial)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            try {
                stream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PAGE_BYTES) {
                            throw ArchiveException(ArchiveException.Kind.PAGE_TOO_LARGE, "inflated page exceeds limit")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            } catch (e: UnsupportedZipFeatureException) {
                throw ArchiveException(ArchiveException.Kind.ENCRYPTED, "encrypted or unsupported compression", e)
            }
            return out.toByteArray()
        }
    }

    override fun close() {
        runCatching { zip.close() }
        runCatching { source.close() }
    }

    private class Candidate(
        val zip: ZipFile,
        val source: SeekableSource,
        val pages: List<ZipArchiveEntry>
    ) {
        fun closeQuietly() {
            runCatching { zip.close() }
            runCatching { source.close() }
        }
    }

    companion object {
        private const val MAX_PAGE_BYTES = 96L * 1024 * 1024
        private const val INITIAL_BUFFER = 256 * 1024
        private const val MAX_PREALLOC = 8 * 1024 * 1024
        private val ENCODINGS = arrayOf("UTF-8", "GBK", "Shift_JIS")

        /**
         * Parsing the central directory of a multi-GB archive is the single most
         * expensive step when opening a book. Remembering the winning codepage per
         * file means a re-opened book usually needs one parse instead of three.
         */
        private val encodingCache = ConcurrentHashMap<String, String>()

        private data class Best(val candidate: Candidate, val score: Int, val encoding: String)

        @Throws(IOException::class)
        fun open(context: Context, uri: Uri): ArchiveReader {
            val key = uri.toString()
            val known = encodingCache[key]
            if (known != null) {
                val quick = runCatching { tryOpen(context, uri, known) }.getOrNull()
                if (quick != null && quick.pages.isNotEmpty()) {
                    return ArchiveReader(quick.zip, quick.source, quick.pages)
                }
                quick?.closeQuietly()
                encodingCache.remove(key)
            }

            var best: Best? = null
            var failure: Exception? = null

            for (encoding in ENCODINGS) {
                val candidate = try {
                    tryOpen(context, uri, encoding)
                } catch (e: Exception) {
                    failure = e
                    null
                } ?: continue

                val score = garbledScore(candidate.pages)
                val current = best
                if (current == null || score < current.score) {
                    current?.candidate?.closeQuietly()
                    best = Best(candidate, score, encoding)
                } else {
                    candidate.closeQuietly()
                }
                if (score == 0) break
            }

            val winner = best ?: throw classifyFailure(failure)
            if (winner.candidate.pages.isEmpty()) {
                winner.candidate.closeQuietly()
                throw ArchiveException(ArchiveException.Kind.NO_IMAGES, "no images inside archive")
            }
            encodingCache[key] = winner.encoding
            return ArchiveReader(winner.candidate.zip, winner.candidate.source, winner.candidate.pages)
        }

        private fun tryOpen(context: Context, uri: Uri, encoding: String): Candidate {
            val source = openSeekable(context, uri)
            return try {
                val zip = ZipFile(source.channel, encoding)
                val pages = zip.entries.toList()
                    .asSequence()
                    .filter { !it.isDirectory }
                    .filter { isImageName(it.name) }
                    .filter { !it.name.contains("__MACOSX", ignoreCase = true) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    .toList()
                Candidate(zip, source, pages)
            } catch (e: Exception) {
                runCatching { source.close() }
                throw e
            }
        }

        /** Garbled names are the tell-tale sign of a wrongly decoded codepage. */
        private fun garbledScore(pages: List<ZipArchiveEntry>): Int =
            pages.sumOf { entry -> entry.name.count { it == '\uFFFD' || it == '?' } }

        private fun classifyFailure(error: Exception?): Exception {
            return when (error) {
                is UnsupportedZipFeatureException ->
                    ArchiveException(ArchiveException.Kind.ENCRYPTED, "encrypted or unsupported compression", error)
                is NonReadableChannelException, is IllegalArgumentException ->
                    ArchiveException(ArchiveException.Kind.NOT_SEEKABLE, "archive is not seekable", error)
                is IOException -> error.message?.let { msg ->
                    if (msg.contains("seek", ignoreCase = true) || msg.contains("central directory", ignoreCase = true)) {
                        ArchiveException(ArchiveException.Kind.NOT_SEEKABLE, msg, error)
                    } else null
                } ?: ArchiveException(ArchiveException.Kind.CORRUPT, "cannot read archive", error)
                else -> ArchiveException(ArchiveException.Kind.CORRUPT, "cannot read archive", error)
            }
        }
    }
}

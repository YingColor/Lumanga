package com.yingcolor.comicviewer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import com.yingcolor.comicviewer.util.isImageName
import com.yingcolor.comicviewer.util.naturalCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Process-wide, byte-bounded memory cache. It lives outside the ViewModel so
 * rotating the screen, returning from the reader or recreating the Activity
 * never throws away already decoded covers.
 */
private object CoverMemoryCache {
    private const val LIMIT_KB = 32 * 1024

    private val cache = object : LruCache<String, Bitmap>(LIMIT_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun clear() = cache.evictAll()
}

class ComicRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val thumbDir = File(appContext.cacheDir, "covers")

    // A five-column grid can have 25+ visible cards. Without a concurrency cap all
    // of them would open the same archive and decode on IO threads at once.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val coverDispatcher = Dispatchers.IO.limitedParallelism(2)

    fun listBooks(roots: List<Uri>): List<Book> {
        val found = mutableListOf<Book>()
        for (root in roots) {
            val dir = runCatching { DocumentFile.fromTreeUri(context, root) }.getOrNull() ?: continue
            runCatching { walk(dir, 0, "", root.toString(), found) }
            if (found.size >= MAX_BOOKS) break
        }
        // Overlapping roots (a folder and its parent both added) would list the
        // same book twice; the first sighting wins.
        val seen = HashSet<String>(found.size)
        return found
            .filter { seen.add(it.id) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }

    private fun walk(
        dir: DocumentFile,
        depth: Int,
        path: String,
        rootId: String,
        out: MutableList<Book>
    ) {
        // Large collections are a first-class case: no aggressive 2000-book cap.
        if (depth > 12 || out.size >= MAX_BOOKS) return
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())

        // A directory that directly holds images is a book of its own (an
        // already-extracted comic). The folder the user picked is never treated
        // this way: a library root with a few loose images must not collapse
        // into a single book.
        if (depth > 0) {
            folderBook(dir, children, path, rootId)?.let { out.add(it); return }
        }

        for (child in children) {
            if (child.isDirectory) {
                val childName = child.name ?: ""
                // Hidden system folders (.thumbnails and friends) are full of
                // JPEGs and would otherwise show up as one-page "books".
                if (childName.startsWith(".")) continue
                walk(
                    child,
                    depth + 1,
                    if (path.isEmpty()) childName else "$path/$childName",
                    rootId,
                    out
                )
            } else {
                val name = child.name ?: continue
                val kind = kindOf(name) ?: continue
                out.add(
                    Book(
                        id = child.uri.toString(),
                        uri = child.uri,
                        name = name.substringBeforeLast('.'),
                        sizeBytes = child.length(),
                        lastModified = child.lastModified(),
                        kind = kind,
                        group = firstSegment(path),
                        rootId = rootId
                    )
                )
            }
        }
    }

    /** `.zip` / `.cbz`, `.rar` and `.7z`; anything else is not a book. */
    private fun kindOf(name: String): BookKind? = when {
        name.endsWith(".zip", ignoreCase = true) || name.endsWith(".cbz", ignoreCase = true) -> BookKind.ARCHIVE
        name.endsWith(".rar", ignoreCase = true) -> BookKind.RAR
        name.endsWith(".7z", ignoreCase = true) -> BookKind.SEVEN_ZIP
        else -> null
    }

    /** The shelf groups books by the first folder below the library root. */
    private fun firstSegment(path: String): String = path.substringBefore('/')

    /**
     * Recognises "a folder of images" as a book. A lone image sitting next to
     * sub-folders is treated as a cover for a container directory
     * (`cover.jpg` + `第1话/`), so the chapters inside are still found.
     */
    private fun folderBook(
        dir: DocumentFile,
        children: Array<DocumentFile>,
        path: String,
        rootId: String
    ): Book? {
        var images = 0
        var subdirs = 0
        for (child in children) {
            if (child.isDirectory) {
                subdirs++
                continue
            }
            val childName = child.name ?: continue
            if (isImageName(childName)) images++
        }
        if (images == 0) return null
        if (images < 2 && subdirs > 0) return null
        val name = dir.name ?: return null
        return Book(
            id = dir.uri.toString(),
            uri = dir.uri,
            name = name,
            // The real size would mean walking every page; zero is shown as
            // "文件夹" in the grid instead of a made-up number.
            sizeBytes = 0L,
            lastModified = runCatching { dir.lastModified() }.getOrDefault(0L),
            kind = BookKind.FOLDER,
            // The category is the folder *above* this one: a folder book sitting
            // directly under the root has no category of its own.
            group = firstSegment(path.substringBeforeLast('/', "")),
            rootId = rootId
        )
    }

    /**
     * Covers are the only image that is persisted, and only as a small thumbnail
     * inside the app's own private cache directory (never in the comics folder,
     * never a full page, removed automatically when the app is uninstalled).
     * Comic pages themselves are still decoded in memory only.
     */
    /** Synchronous lookup: a cached cover needs neither a coroutine nor a thread hop. */
    fun peekCover(bookId: String, targetPx: Int): Bitmap? =
        CoverMemoryCache.get("$bookId@$targetPx")

    suspend fun cover(book: Book, targetPx: Int = 360): Bitmap? {
        val memoryKey = "${book.id}@$targetPx"
        // Fast path. Every visible card used to post a job onto a 2-thread
        // dispatcher even when the bitmap was already in memory, so scrolling a
        // large grid spent its time queueing coroutines instead of drawing.
        CoverMemoryCache.get(memoryKey)?.let { return it }
        return withContext(coverDispatcher) {
            CoverMemoryCache.get(memoryKey)?.let { return@withContext it }

            val file = File(thumbDir, book.id.hashCode().toString() + ".jpg")
            val base = decodeDisk(file, targetPx)
                ?: createThumbnail(book, file)
                ?: return@withContext null
            val result = if (base.width > targetPx) scaleToWidth(base, targetPx) else base
            CoverMemoryCache.put(memoryKey, result)
            result
        }
    }

    /** Decode the on-disk thumbnail straight to roughly the size shown on screen. */
    private fun decodeDisk(file: File, targetPx: Int): Bitmap? {
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > targetPx * 2 && sample < 8) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.path, options)
        }.getOrNull()
    }

    private fun createThumbnail(book: Book, file: File): Bitmap? {
        val reader = runCatching { PageSource.open(appContext, book) }.getOrNull()
            ?: return null
        val decoded = try {
            val bytes = reader.readPage(0)
            PageDecoder.decode(bytes, THUMB_WIDTH, (THUMB_WIDTH * 1.5f).toInt())
        } catch (_: Exception) {
            null
        } finally {
            reader.close()
        }
        val bitmap = decoded?.let { scaleToWidth(it, THUMB_WIDTH) } ?: return null
        saveThumbnail(file, bitmap)
        return bitmap
    }

    private fun scaleToWidth(src: Bitmap, width: Int): Bitmap {
        if (src.width <= 0) return src
        val height = (src.height.toFloat() * width / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, width, height, true)
    }

    private fun saveThumbnail(file: File, bitmap: Bitmap) {
        runCatching {
            if (!thumbDir.exists()) thumbDir.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    fun clearCoverCache() = CoverMemoryCache.clear()

    private companion object {
        const val THUMB_WIDTH = 320
        const val MAX_BOOKS = 100_000
    }
}

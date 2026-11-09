package com.yingcolor.lumanga.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A small local index of the bookshelf: file paths and metadata only, never comic
 * content. It lives in the app's private filesDir (not cacheDir), so the shelf can
 * be drawn the instant the app opens instead of walking the whole tree first.
 *
 * The folder URI is part of the index. Picking a different folder invalidates it
 * automatically, and a failed re-read simply falls back to a normal scan.
 */
class ShelfIndex(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun load(folder: String): List<Book>? {
        val text = runCatching { if (file.exists()) file.readText() else null }
            .getOrNull() ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optString(FOLDER) != folder) return null
        val array = root.optJSONArray(BOOKS) ?: return null
        val result = ArrayList<Book>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val uriText = item.optString(URI)
            if (uriText.isEmpty()) continue
            result.add(
                Book(
                    id = uriText,
                    uri = Uri.parse(uriText),
                    name = item.optString(NAME),
                    sizeBytes = item.optLong(SIZE),
                    lastModified = item.optLong(MODIFIED),
                    kind = kindOf(item.optString(KIND)),
                    group = item.optString(GROUP),
                    rootId = item.optString(ROOT)
                )
            )
        }
        return result
    }

    /** Atomic replace, so a killed process can never leave a half-written index. */
    fun save(folder: String, books: List<Book>) {
        runCatching {
            val array = JSONArray()
            for (book in books) {
                array.put(
                    JSONObject()
                        .put(URI, book.id)
                        .put(NAME, book.name)
                        .put(SIZE, book.sizeBytes)
                        .put(MODIFIED, book.lastModified)
                        .put(KIND, kindName(book.kind))
                        .put(GROUP, book.group)
                        .put(ROOT, book.rootId)
                )
            }
            val text = JSONObject()
                .put(FOLDER, folder)
                .put(VERSION, 1)
                .put(BOOKS, array)
                .toString()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    private fun kindName(kind: BookKind): String = when (kind) {
        BookKind.FOLDER -> "folder"
        BookKind.RAR -> "rar"
        BookKind.SEVEN_ZIP -> "7z"
        BookKind.ARCHIVE -> "zip"
    }

    private fun kindOf(name: String): BookKind = when (name) {
        "folder" -> BookKind.FOLDER
        "rar" -> BookKind.RAR
        "7z" -> BookKind.SEVEN_ZIP
        else -> BookKind.ARCHIVE
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "shelf_index.json"
        const val FOLDER = "folder"
        const val VERSION = "version"
        const val BOOKS = "books"
        const val URI = "uri"
        const val NAME = "name"
        const val SIZE = "size"
        const val MODIFIED = "modified"
        const val KIND = "kind"
        const val GROUP = "group"
        const val ROOT = "root"
    }
}

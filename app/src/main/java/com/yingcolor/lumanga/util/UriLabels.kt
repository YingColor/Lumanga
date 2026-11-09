package com.yingcolor.lumanga.util

import android.net.Uri

/**
 * A readable name for a library folder picked through the Storage Access
 * Framework. The uri carries the document id (`primary:Comics/作者A`), whose last
 * segment is the folder name; providers that do not expose a documents id fall
 * back to the last path segment.
 */
fun folderLabel(uri: String): String {
    val decoded = runCatching { Uri.decode(uri) }.getOrDefault(uri)
    val afterTree = decoded.substringAfter("/tree/", "")
    val documentId = afterTree.substringBefore("/document/")
    val fromId = documentId.substringAfterLast(':').substringAfterLast('/')
    if (fromId.isNotBlank()) return fromId
    val fallback = decoded.substringAfterLast('/')
    return fallback.ifBlank { decoded }
}

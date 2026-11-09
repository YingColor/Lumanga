package com.yingcolor.lumanga.util

import android.net.Uri







fun folderLabel(uri: String): String {
    val decoded = runCatching { Uri.decode(uri) }.getOrDefault(uri)
    val afterTree = decoded.substringAfter("/tree/", "")
    val documentId = afterTree.substringBefore("/document/")
    val fromId = documentId.substringAfterLast(':').substringAfterLast('/')
    if (fromId.isNotBlank()) return fromId
    val fallback = decoded.substringAfterLast('/')
    return fallback.ifBlank { decoded }
}

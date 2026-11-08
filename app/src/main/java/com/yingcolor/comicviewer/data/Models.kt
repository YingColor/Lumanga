package com.yingcolor.comicviewer.data

import android.net.Uri

data class Book(
    val id: String,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long
)

class ArchiveException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { CORRUPT, NOT_SEEKABLE, PAGE_TOO_LARGE, NO_IMAGES, ENCRYPTED, IO }
}

package com.yingcolor.comicviewer.data

import android.net.Uri

/** Where the pages of a book come from. */
enum class BookKind {
    /** A single .zip / .cbz archive. */
    ARCHIVE,

    /** A plain directory whose images are the pages, in name order. */
    FOLDER
}

data class Book(
    val id: String,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val kind: BookKind = BookKind.ARCHIVE
)

class ArchiveException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { CORRUPT, NOT_SEEKABLE, PAGE_TOO_LARGE, NO_IMAGES, ENCRYPTED, IO }
}

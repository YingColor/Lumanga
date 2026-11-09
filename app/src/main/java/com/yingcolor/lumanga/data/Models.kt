package com.yingcolor.lumanga.data

import android.net.Uri

/** Where the pages of a book come from. */
enum class BookKind {
    /** A single .zip / .cbz archive. */
    ARCHIVE,

    /** A single .rar archive (RAR 4 and RAR 5). */
    RAR,

    /** A single .7z archive. */
    SEVEN_ZIP,

    /** A plain directory whose images are the pages, in name order. */
    FOLDER
}

data class Book(
    val id: String,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val kind: BookKind = BookKind.ARCHIVE,
    /** First folder below the library root, used as the shelf's category chip. */
    val group: String = "",
    /** Which library folder this book was found in. */
    val rootId: String = ""
)

class ArchiveException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { CORRUPT, NOT_SEEKABLE, PAGE_TOO_LARGE, NO_IMAGES, ENCRYPTED, IO }
}

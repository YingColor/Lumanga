package com.yingcolor.lumanga.data

import android.net.Uri


enum class BookKind {
    
    ARCHIVE,

    
    RAR,

    
    SEVEN_ZIP,

    
    FOLDER
}

data class Book(
    val id: String,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val kind: BookKind = BookKind.ARCHIVE,
    
    val group: String = "",
    
    val rootId: String = ""
)

class ArchiveException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { CORRUPT, NOT_SEEKABLE, PAGE_TOO_LARGE, NO_IMAGES, ENCRYPTED, IO }
}

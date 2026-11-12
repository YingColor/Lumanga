package com.yingcolor.lumanga.data

/**
 * The language printed on the page. JAPANESE is the default and keeps every existing
 * filtering and reading-order rule untouched; the other two only relax them.
 */
enum class SourceLanguage(val id: Int, val label: String, val promptName: String) {
    JAPANESE(0, "日文", "日文"),
    CHINESE(1, "中文", "中文"),
    ENGLISH(2, "英文", "英文");

    /** Latin lettering runs left to right, one line at a time, not in vertical columns. */
    val horizontal: Boolean get() = this == ENGLISH

    companion object {
        val DEFAULT = JAPANESE
        fun from(id: Int): SourceLanguage = entries.firstOrNull { it.id == id } ?: DEFAULT
        fun coerce(id: Int): Int = from(id).id
    }
}

/**
 * The language the model should write. The endpoint is language agnostic — whether a pair
 * actually works depends on the model the user configured, which the settings screen says.
 */
enum class TargetLanguage(val id: Int, val label: String, val promptName: String) {
    CHINESE(0, "简体中文", "简体中文"),
    ENGLISH(1, "English", "英文"),
    JAPANESE(2, "日本語", "日文"),
    KOREAN(3, "한국어", "韩文");

    /** Latin output needs left-to-right lines and word-aware wrapping. */
    val latin: Boolean get() = this == ENGLISH

    /** Space is meaningful in these scripts; the CJK cleaner must not strip it. */
    val keepSpaces: Boolean get() = this == ENGLISH || this == KOREAN

    companion object {
        val DEFAULT = CHINESE
        fun from(id: Int): TargetLanguage = entries.firstOrNull { it.id == id } ?: DEFAULT
        fun coerce(id: Int): Int = from(id).id
    }
}

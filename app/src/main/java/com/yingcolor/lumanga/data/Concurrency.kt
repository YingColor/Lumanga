package com.yingcolor.lumanga.data

/**
 * How many pages pre-translation may work on at once.
 *
 * These are deliberately small. OCR is CPU bound and a phone has only a handful of big cores, so
 * beyond three pages the extra workers fight for the same silicon and the battery pays for it.
 * Translation is up to four because a hosted endpoint answers in well under a second, which makes
 * the network part of a page cheap enough to overlap.
 *
 * Only pre-translation reads these; the reader always translates the page it is showing.
 */
object Concurrency {
    const val OCR_MIN = 1
    const val OCR_MAX = 3
    const val TRANSLATE_MIN = 1
    const val TRANSLATE_MAX = 4

    fun ocr(value: Int): Int = value.coerceIn(OCR_MIN, OCR_MAX)
    fun translate(value: Int): Int = value.coerceIn(TRANSLATE_MIN, TRANSLATE_MAX)
}

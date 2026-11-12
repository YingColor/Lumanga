package com.yingcolor.lumanga.data

/**
 * How translated text is drawn inside the erased bubble area.
 *
 * [VERTICAL] keeps the source orientation — a Japanese bubble keeps its right-to-left columns —
 * and [HORIZONTAL] lays the translation out as ordinary Chinese lines, left to right. The choice
 * is a reading preference, not a recognition one: stored translations hold the bubble layout and
 * the text, never pixels, so switching redraws every already-translated page without re-running
 * OCR or the network.
 */
object TextLayout {
    const val VERTICAL = 0
    const val HORIZONTAL = 1

    fun coerce(value: Int): Int = if (value == HORIZONTAL) HORIZONTAL else VERTICAL
}

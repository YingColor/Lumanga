package com.yingcolor.comicviewer.data

/**
 * How the reader animates from one page to the next.
 *
 * [SLIDE] is the pager's own horizontal push; the others move the page in place
 * (counter-translating the pager) so the transition reads as a turn rather than
 * a scroll.
 */
object PageAnimation {
    const val SLIDE = 0
    const val FADE = 1
    const val FLIP = 2
    const val ZOOM = 3

    const val COUNT = 4

    fun coerce(value: Int): Int = value.coerceIn(0, COUNT - 1)
}

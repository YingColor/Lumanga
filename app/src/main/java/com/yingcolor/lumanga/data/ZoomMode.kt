package com.yingcolor.lumanga.data

/**
 * How a page is fitted into the viewport.
 *
 * The order matches the choice list in the settings sheet, so the stored index can
 * be used directly as a UI position.
 */
object ZoomMode {
    /** One bitmap pixel per screen pixel; pan around when the page is bigger. */
    const val ORIGINAL = 0

    /** Whole page visible, aspect ratio kept (fits both width and height). */
    const val FIT_SCREEN = 1

    /** Width fills the screen; a taller page is panned vertically. */
    const val FIT_WIDTH = 2

    /** Height fills the screen; a wider page is panned horizontally. */
    const val FIT_HEIGHT = 3

    /** User chosen percentage of the screen width. */
    const val FIXED = 4

    /** Fill the viewport and ignore the aspect ratio. */
    const val STRETCH = 5

    /** Fill the viewport, keep the aspect ratio, crop the overflow. */
    const val COVER = 6

    const val COUNT = 7

    val RANGE = ORIGINAL..COVER

    fun coerce(value: Int): Int = value.coerceIn(ORIGINAL, COVER)

    /** Modes that make a page larger than the viewport, i.e. need panning. */
    fun mayOverflow(mode: Int): Boolean = mode == ORIGINAL || mode == FIXED
}

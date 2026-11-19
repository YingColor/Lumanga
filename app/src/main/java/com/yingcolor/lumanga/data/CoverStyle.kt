package com.yingcolor.lumanga.data

/**
 * How far the plate behind a translated bubble reaches.
 *
 * [BALLOON] (the default) fills the shape the segmentation model found, pulled in by a couple of
 * pixels so the balloon's own stroke survives: the plate follows the bubble instead of drawing a
 * box inside it. [LETTERING] paints only the area the original lettering and the translation
 * actually occupy, which leaves more of the drawing visible but reads as a rounded rectangle.
 *
 * A reading preference, like [TextLayout]: stored pages keep their groups and text, never pixels,
 * so switching this redraws an already-translated page without another OCR pass or request.
 */
object CoverStyle {
    const val LETTERING = 0
    const val BALLOON = 1

    fun coerce(value: Int): Int = if (value == BALLOON) BALLOON else LETTERING
}

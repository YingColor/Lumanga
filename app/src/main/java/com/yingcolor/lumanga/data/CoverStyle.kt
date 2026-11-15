package com.yingcolor.lumanga.data

/**
 * How far the plate behind a translated bubble reaches.
 *
 * [LETTERING] paints only the area the original lettering and the translation actually occupy,
 * so the balloon's own outline, tail and shading stay untouched. [BALLOON] follows the whole
 * outline the segmentation model found, which hides stray kana the detector missed but also
 * paints over the outline itself.
 *
 * A reading preference, like [TextLayout]: stored pages keep their groups and text, never pixels,
 * so switching this redraws an already-translated page without another OCR pass or request.
 */
object CoverStyle {
    const val LETTERING = 0
    const val BALLOON = 1

    fun coerce(value: Int): Int = if (value == BALLOON) BALLOON else LETTERING
}

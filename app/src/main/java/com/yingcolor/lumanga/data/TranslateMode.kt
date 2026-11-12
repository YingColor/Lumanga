package com.yingcolor.lumanga.data

/**
 * How one page is handed to the model.
 *
 * The two modes are a real trade-off, not a preference about quality: a page request is cheaper
 * and keeps the whole conversation in one context, while a group request costs one round trip per
 * bubble but can be drawn the moment each bubble is ready.
 */
object TranslateMode {
    /** One request for the whole page. Faster, but the page stays Japanese until all of it lands. */
    const val PAGE = 0

    /** One request per bubble group: bubbles fill in one at a time, a failure costs one bubble. */
    const val GROUP = 1

    fun coerce(value: Int): Int = if (value == GROUP) GROUP else PAGE
}

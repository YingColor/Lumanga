package com.yingcolor.lumanga.data







object ZoomMode {
    
    const val ORIGINAL = 0

    
    const val FIT_SCREEN = 1

    
    const val FIT_WIDTH = 2

    
    const val FIT_HEIGHT = 3

    
    const val FIXED = 4

    
    const val STRETCH = 5

    
    const val COVER = 6

    const val COUNT = 7

    val RANGE = ORIGINAL..COVER

    fun coerce(value: Int): Int = value.coerceIn(ORIGINAL, COVER)

    
    fun mayOverflow(mode: Int): Boolean = mode == ORIGINAL || mode == FIXED
}

package com.yingcolor.lumanga.data








object PageAnimation {
    const val SLIDE = 0
    const val FADE = 1
    const val FLIP = 2
    const val ZOOM = 3

    const val COUNT = 4

    fun coerce(value: Int): Int = value.coerceIn(0, COUNT - 1)
}

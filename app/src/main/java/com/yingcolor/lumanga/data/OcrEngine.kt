package com.yingcolor.lumanga.data

import android.graphics.Bitmap

interface OcrEngine {
    /**
     * [parallel] lets pre-translation borrow one of several runtimes; the reader leaves it false
     * and always uses the single serialised runtime.
     */
    suspend fun recognize(bitmap: Bitmap, parallel: Boolean = false): List<TextRegion>
}

object NoOpOcrEngine : OcrEngine {
    override suspend fun recognize(bitmap: Bitmap, parallel: Boolean): List<TextRegion> =
        emptyList()
}

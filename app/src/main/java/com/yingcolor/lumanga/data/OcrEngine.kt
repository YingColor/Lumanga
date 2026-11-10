package com.yingcolor.lumanga.data

import android.graphics.Bitmap

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): List<TextRegion>
}

object NoOpOcrEngine : OcrEngine {
    override suspend fun recognize(bitmap: Bitmap): List<TextRegion> = emptyList()
}

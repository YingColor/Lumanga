package com.yingcolor.lumanga.data

import android.graphics.Bitmap

interface OcrEngine {
    /**
     * [parallel] lets pre-translation borrow one of several local runtimes; the reader leaves it
     * false and always uses the single serialised runtime. [token] cancels an online request that
     * is still in flight when the page is abandoned.
     */
    suspend fun recognize(
        bitmap: Bitmap,
        parallel: Boolean = false,
        token: ApiClient.CancelToken? = null
    ): List<TextRegion>
}

object NoOpOcrEngine : OcrEngine {
    override suspend fun recognize(
        bitmap: Bitmap,
        parallel: Boolean,
        token: ApiClient.CancelToken?
    ): List<TextRegion> = emptyList()
}

package com.yingcolor.lumanga.data

import android.content.Context
import android.graphics.Bitmap

/**
 * Sends each page to whichever recogniser the user picked.
 *
 * The reader and pre-translation both call [TranslationEngine.recognize], so routing here makes
 * the on/off switch apply everywhere at once — including a change made while a book is open.
 */
class RoutingOcrEngine(private val context: Context) : OcrEngine {
    override suspend fun recognize(
        bitmap: Bitmap,
        parallel: Boolean,
        token: ApiClient.CancelToken?
    ): List<TextRegion> {
        val prefs = Prefs(context)
        return if (prefs.ocrOnline) {
            OnlineOcr.recognize(prefs, bitmap, token)
        } else {
            PaddleOcr.engine(context).recognize(bitmap, parallel)
        }
    }
}

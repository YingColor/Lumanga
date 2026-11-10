package com.yingcolor.lumanga.data

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): List<TextRegion>
}

object MlKitOcrEngine : OcrEngine {
    override suspend fun recognize(bitmap: Bitmap): List<TextRegion> =
        suspendCancellableCoroutine { cont ->
            val recognizer =
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val regions = result.textBlocks.mapNotNull { block ->
                        val box = block.boundingBox ?: return@mapNotNull null
                        val text = block.text.replace('\n', ' ').trim()
                        if (text.isEmpty()) null else TextRegion(text, box)
                    }
                    recognizer.close()
                    if (cont.isActive) cont.resume(regions)
                }
                .addOnFailureListener {
                    recognizer.close()
                    if (cont.isActive) cont.resume(emptyList())
                }
        }
}

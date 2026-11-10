package com.yingcolor.lumanga

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File

object OcrProbe {
    private const val TAG = "OcrProbe"

    fun run(activity: Activity) {
        val name = activity.intent.getStringExtra("probe_file") ?: "ocr_probe.jpg"
        val dir = activity.getExternalFilesDir(null)
        val file = File(dir, name)
        if (!file.exists()) {
            Log.e(TAG, "OCRP missing=$file")
            activity.finish()
            return
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            Log.e(TAG, "OCRP decode_failed=$file")
            activity.finish()
            return
        }
        Log.i(TAG, "OCRP file=$name size=${bitmap.width}x${bitmap.height}")
        val started = System.currentTimeMillis()
        Handler(Looper.getMainLooper()).postDelayed({ activity.finish() }, 45000)
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks
                Log.i(TAG, "OCRP blocks=${blocks.size} ms=${System.currentTimeMillis() - started}")
                blocks.forEachIndexed { bi, block ->
                    Log.i(
                        TAG,
                        "OCRP B$bi box=${block.boundingBox} text=${block.text.replace('\n', '|')}"
                    )
                    block.lines.forEachIndexed { li, line ->
                        Log.i(TAG, "OCRP B${bi}L$li box=${line.boundingBox} text=${line.text}")
                    }
                }
                Log.i(TAG, "OCRP DONE")
                recognizer.close()
                activity.finish()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCRP FAILED ${e.message}")
                recognizer.close()
                activity.finish()
            }
    }
}

package com.yingcolor.lumanga.data

/**
 * The bundled text recognisers. SMALL and MEDIUM share the same detector input
 * (1x3x640x640), recogniser input (1x3x48x640) and the vocabulary in characters.json;
 * MANGA reuses SMALL's detector but reads a whole bubble with its own encoder/decoder.
 */
enum class OcrModel(
    val id: Int,
    val label: String,
    val det: String,
    val rec: String,
    val onnx: Boolean
) {
    SMALL(
        0,
        "PP-OCRv6_small",
        "ppocrv6_small_det_640_fp32.tflite",
        "ppocrv6_small_rec_640_fp32.tflite",
        false
    ),
    MEDIUM(
        1,
        "PP-OCRv6_medium",
        "ppocrv6_medium_det_640_fp16.onnx",
        "ppocrv6_medium_rec_640_fp16.onnx",
        true
    ),
    /**
     * Whole-bubble recognition. It still uses SMALL's detector and text boxes for grouping,
     * then [MangaOcr] re-reads each bubble as one sentence; its own files are copied by that
     * object rather than by PaddleOcrEngine.
     */
    MANGA(
        2,
        "MangaOCR_mobile",
        "ppocrv6_small_det_640_fp32.tflite",
        "ppocrv6_small_rec_640_fp32.tflite",
        false
    );

    val files: List<String>
        get() = listOf(det, rec, "characters.json")

    companion object {
        /** Fresh installs and any unknown stored id land on the whole-bubble reader. */
        val DEFAULT: OcrModel = MANGA

        fun from(id: Int): OcrModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

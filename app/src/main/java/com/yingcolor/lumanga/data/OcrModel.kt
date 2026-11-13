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

    /**
     * Whether [PaddleOcrEngine] may read several pages at once with this model.
     *
     * - SMALL: every runtime owns its own LiteRT interpreters (the 21 MB recogniser is copied per
     *   worker), so extra runtimes are safe and a page's columns stay independent.
     * - MEDIUM: each runtime would load its own 67 MB ONNX recogniser on top of the detector, so
     *   extra runtimes cost ~134 MB each. It stays serial.
     * - MANGA: [MangaOcr] is a single `synchronized` object, so parallel pages would only queue
     *   behind the same lock — no speed-up, just extra bitmaps in memory.
     */
    val supportsParallel: Boolean get() = this == SMALL

    companion object {
        /** Fresh installs and any unknown stored id land on the fast column reader. */
        val DEFAULT: OcrModel = SMALL

        fun from(id: Int): OcrModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

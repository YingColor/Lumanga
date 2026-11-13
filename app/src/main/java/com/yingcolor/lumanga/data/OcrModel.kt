package com.yingcolor.lumanga.data

/**
 * The bundled text recognisers. SMALL and MEDIUM share the same detector input
 * (1x3x640x640), recogniser input (1x3x48x640) and the vocabulary in characters.json;
 * MANGA reuses SMALL's detector but reads a whole bubble with its own encoder/decoder.
 * HYBRID mixes MEDIUM's detector (it finds faint lettering SMALL misses) with SMALL's
 * recogniser (about ten times faster than MEDIUM's) because the two halves are independent.
 */
enum class OcrModel(
    val id: Int,
    val label: String,
    val det: String,
    val rec: String,
    val detOnnx: Boolean,
    val recOnnx: Boolean
) {
    SMALL(
        0,
        "PP-OCRv6_small",
        "ppocrv6_small_det_640_fp32.tflite",
        "ppocrv6_small_rec_640_fp32.tflite",
        false,
        false
    ),
    MEDIUM(
        1,
        "PP-OCRv6_medium",
        "ppocrv6_medium_det_640_fp16.onnx",
        "ppocrv6_medium_rec_640_fp16.onnx",
        true,
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
        false,
        false
    ),
    /**
     * Detection is where SMALL loses whole bubbles on busy backgrounds; MEDIUM's detector
     * finds them. Recognition is where MEDIUM spends 30 s per page, so only the detector is
     * taken from it. Both halves use the same tensor shapes and the same characters.json.
     */
    HYBRID(
        3,
        "PP-OCRv6_small · 精确检测",
        "ppocrv6_medium_det_640_fp16.onnx",
        "ppocrv6_small_rec_640_fp32.tflite",
        true,
        false
    );

    val files: List<String>
        get() = listOf(det, rec, "characters.json")

    /**
     * Whether [PaddleOcrEngine] may read several pages at once with this model.
     *
     * - SMALL: every runtime owns its own LiteRT interpreters (the 21 MB recogniser is copied per
     *   worker), so extra runtimes are safe and a page's columns stay independent.
     * - MEDIUM and HYBRID: each runtime would load another 31 MB ONNX detector (and MEDIUM a
     *   38 MB recogniser too), so they stay serial.
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

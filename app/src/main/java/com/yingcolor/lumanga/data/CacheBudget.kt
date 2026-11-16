package com.yingcolor.lumanga.data

/**
 * How much of the heap each bitmap cache may hold, in KB.
 *
 * These were hand-written constants and their sum had grown to 213MB of a 256MB heap (page /4
 * clamped at 64MB, compressed bytes 48MB, tiles 32MB, translations 32MB, raw bytes 21MB, plus
 * 8MB each for thumbnails and placeholders). A full-page bitmap is about 17MB, so a page turn
 * could not allocate one: the VM ran a full copying GC, threw OutOfMemoryError several times per
 * page, and the window stopped answering touches for five seconds - the ANR seen on the reader
 * ("Waited 5005ms for MotionEvent", main thread parked in art::gc::Heap::AllocateInternalWithGc).
 *
 * The fractions below keep the whole set near a third of the heap. The remaining two thirds are
 * for the bitmaps that are genuinely in flight: the decoded page, the rendered translation, and
 * one partial render per arriving bubble group.
 */
internal data class CacheBudget(
    val pageKb: Int,
    val rawKb: Int,
    val bytesKb: Int,
    val tileKb: Int,
    val translationKb: Int,
    val thumbKb: Int,
    val placeholderKb: Int
) {
    companion object {
        fun of(maxMemoryBytes: Long): CacheBudget {
            val heapKb = (maxMemoryBytes / 1024L).toInt()
            return CacheBudget(
                pageKb = (heapKb / 7).coerceIn(24 * 1024, 48 * 1024),
                rawKb = (heapKb / 32).coerceIn(6 * 1024, 12 * 1024),
                bytesKb = (heapKb / 24).coerceIn(8 * 1024, 16 * 1024),
                tileKb = (heapKb / 24).coerceIn(8 * 1024, 16 * 1024),
                translationKb = (heapKb / 14).coerceIn(12 * 1024, 24 * 1024),
                thumbKb = (heapKb / 64).coerceIn(3 * 1024, 6 * 1024),
                placeholderKb = (heapKb / 64).coerceIn(3 * 1024, 6 * 1024)
            )
        }
    }
}

package com.yingcolor.comicviewer.util

/** Sorts "page2.jpg" before "page10.jpg" instead of using plain lexicographic order. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var endA = i
            while (endA < a.length && a[endA].isDigit()) endA++
            var endB = j
            while (endB < b.length && b[endB].isDigit()) endB++
            val numA = a.substring(i, endA).trimStart('0').ifEmpty { "0" }
            val numB = b.substring(j, endB).trimStart('0').ifEmpty { "0" }
            val cmp = when {
                numA.length != numB.length -> numA.length.compareTo(numB.length)
                else -> numA.compareTo(numB)
            }
            if (cmp != 0) return cmp
            i = endA
            j = endB
        } else {
            val la = ca.lowercaseChar()
            val lb = cb.lowercaseChar()
            if (la != lb) return la.compareTo(lb)
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

private val imageExtensions = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "jxl", "tif", "tiff"
)

fun isImageName(name: String): Boolean {
    val clean = name.substringAfterLast('/').substringAfterLast('\\')
    if (clean.startsWith(".") || clean.equals("Thumbs.db", ignoreCase = true)) return false
    val ext = clean.substringAfterLast('.', "").lowercase()
    return ext in imageExtensions
}

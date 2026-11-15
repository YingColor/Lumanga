package com.yingcolor.lumanga.data

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The face the translation is drawn with.
 *
 * The system bold sans makes a page look like a settings screen rather than a comic: its strokes
 * are uniform and its counters wide, where scanned manga lettering is hand-set and slightly
 * bouncy. ZCOOL KuaiLe (SIL OFL 1.1, see assets/fonts/OFL.txt) covers all 6763 GB2312 characters,
 * so the common page needs nothing else. It has no box-drawing or symbol glyphs - `♡ ・ ★` and
 * the like are missing - and a custom typeface is not backed by the system fallback chain, so
 * those characters would print as empty boxes. [covers] answers per character and the renderer
 * draws the leftovers with the system face instead.
 *
 * Loading is lazy and cached: the file is 1.5 MB, and a reader that never translates must not pay
 * for it. A missing or broken asset degrades to the system face rather than failing the page.
 */
object LetteringFont {

    private const val ASSET = "fonts/ZCOOLKuaiLe-Regular.ttf"

    private var loaded: Typeface? = null
    private var attempted = false
    private val covered = HashMap<Char, Boolean>()
    private val probe = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The bundled face, or null when the asset is unavailable. */
    fun of(context: Context): Typeface? {
        if (!attempted) {
            attempted = true
            loaded = runCatching {
                Typeface.createFromAsset(context.applicationContext.assets, ASSET)
            }.getOrNull()
            if (loaded == null) android.util.Log.w("Translate", "TR font missing, using system face")
        }
        return loaded
    }

    /**
     * True when [face] really has a glyph for [ch]. Only CJK goes to the comic face: Latin words
     * and `♡ ★ ・` keep the system face, which is where they read best anyway.
     */
    fun covers(face: Typeface?, ch: Char): Boolean {
        if (face == null || !isCjk(ch)) return false
        return covered.getOrPut(ch) {
            probe.typeface = face
            probe.hasGlyph(ch.toString())
        }
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3000..0x303F || code in 0x4E00..0x9FFF || code in 0xFF00..0xFF65
    }
}

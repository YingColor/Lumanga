package com.yingcolor.lumanga.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object TranslationConfig {
    /**
     * No service is bundled. The app talks to whichever endpoint the user configures — an
     * OpenAI-compatible one, Anthropic, Gemini, or a local Ollama — so nothing is sent anywhere
     * until they set it up themselves.
     */
    const val DEFAULT_API = ""
    const val DEFAULT_MODEL = ""
}

data class TextRegion(
    val text: String,
    val box: Rect,
    val confidence: Float = 1f,
    val vertical: Boolean = false
)

data class BubbleGroup(
    val regions: List<TextRegion>,
    val bubble: Rect,
    val source: String,
    val vertical: Boolean,
    /** Rejected low-confidence columns whose original lettering still has to be erased. */
    val eraseExtra: List<Rect> = emptyList(),
    /** Balloon outline from the segmentation model, in page pixels; null when none matched. */
    val contour: FloatArray? = null
)

object TranslationEngine {
    /**
     * Separates two vertical columns of one bubble. A plain space is used on purpose: the model
     * needs to see where a column ends, but any visible mark (「｜」) leaks into the translation
     * and is then drawn into the bubble, and the model also starts inserting ellipses at every
     * break. A space carries the same boundary and costs nothing if one survives.
     */
    private const val COLUMN_BREAK = " "

    private val COLUMN_MARKS = Regex("[｜|]")
    private val WHITESPACE = Regex("\\s+")
    private val ELLIPSIS_RUN = Regex("…+|⋯+|\\.{2,}|・{2,}")
    private val DOUBLE_PUNCT = Regex("([，、；：])\\1+")
    private val ELLIPSIS_CHARS = setOf('…', '⋯', '・')

    /**
     * The wording used before the language switch existed. It stays byte-identical so a
     * Japanese -> Chinese page behaves exactly as it always has; every other pair is built
     * from the templates below.
     */
    private const val SYSTEM_PROMPT_JA_ZH =
        "你是漫画翻译助手。输入按【编号】分组，每组是一个对话气泡里 OCR 出来的日文片段。" +
            "竖排气泡里用空格分开的就是一列，读法是列从右到左、列内从上到下。" +
            "漫画竖排文字常被切碎、顺序颠倒，也可能有识别错字，甚至整组只剩一两个字。" +
            "请先按漫画竖排的正确顺序（从右到左、从上到下）重排并纠正明显错字，" +
            "理解整句含义后，为每个气泡输出一句通顺自然的简体中文。" +
            "若一组只剩一两个字，按上下文和语气补成完整的一句，不要只译那两个字。" +
            "若某一组本身只有一个省略号，原样输出「……」，不要补全、不要猜内容。" +
            "无法确定的字宁可结合上下文意译，不要音译，也不要在译文里保留日文假名。" +
            "只输出译文，条数必须与气泡数一致，每行严格按「编号. 译文」格式。" +
            "译文要短，长度尽量接近原文：气泡放不下就要缩小字号，所以不要扩写、不要加原文没有的语气词和修饰。" +
            "原文里的省略号（……）要在译文对应位置保留；但不能用省略号表示停顿、分列或猜不出来的地方，" +
            "原文没有省略号时也不要自己加。" +
            "不要解释、不要复述原文、不要逐字硬译、不要添加原文没有的内容。" +
            "若有上一页译文，人名和称呼必须与之一致。"

    private const val GROUP_PROMPT_JA_ZH =
        "你是漫画翻译助手。用户每次只给你一个对话气泡里的日文，竖排可能被切碎、顺序颠倒，" +
            "也可能有识别错字，甚至只剩一两个字。" +
            "竖排里用空格分开的就是一列，读法是列从右到左、列内从上到下。" +
            "请先按正确顺序重排、纠正明显错字，再译成一句通顺自然的简体中文。" +
            "只剩一两个字时按语气补成完整的一句，不要只译那两个字。" +
            "若原文只有一个省略号，原样输出「……」，不要补全、不要猜。" +
            "只输出译文本身：不要编号、不要解释、不要复述原文，也不要在译文里保留日文假名。" +
            "译文要短，长度尽量接近原文；原文有省略号才保留，没有就不要自己加。" +
            "若给了前文，人名和称呼必须与之一致。"

    /** The original Japanese -> Chinese wording, used verbatim whenever that is the pair. */
    private fun systemPrompt(lang: SourceLanguage): String =
        if (lang == SourceLanguage.JAPANESE && target == TargetLanguage.CHINESE)
            SYSTEM_PROMPT_JA_ZH else buildSystemPrompt(lang)

    private fun groupPrompt(lang: SourceLanguage): String =
        if (lang == SourceLanguage.JAPANESE && target == TargetLanguage.CHINESE)
            GROUP_PROMPT_JA_ZH else buildGroupPrompt(lang)

    /** Published by [UiSettings] when the user picks a language, and read by the whole chain. */
    @Volatile
    var source: SourceLanguage = SourceLanguage.DEFAULT

    @Volatile
    var target: TargetLanguage = TargetLanguage.DEFAULT

    /**
     * Empty for the original Japanese -> Chinese pair. A non-empty suffix gives other pairs
     * their own translation files and cache keys, so switching languages never deletes or
     * silently reuses a volume's existing Chinese translations.
     */
    val languageKey: String
        // AUTO is not a separate pair: it resolves to Japanese on any page that carries kana, and
        // that is the old default. Giving AUTO its own key would make every existing translation
        // invisible the moment the default changed, so it keeps the unmodified key too.
        get() = if ((source == SourceLanguage.DEFAULT || source.detected) &&
            target == TargetLanguage.DEFAULT
        ) "" else "s${source.id}t${target.id}"

    private fun buildSystemPrompt(lang: SourceLanguage): String = buildString {
        val horizontalSource = lang.horizontal
        val order = if (horizontalSource)
            "横排气泡里用空格分开的就是一行，读法是从左到右、从上到下。"
        else
            "竖排气泡里用空格分开的就是一列，读法是列从右到左、列内从上到下。"
        val reorder = if (horizontalSource) "从左到右、从上到下" else "从右到左、从上到下"
        append("你是漫画翻译助手。输入按【编号】分组，每组是一个对话气泡里 OCR 出来的")
            .append(lang.promptName).append("片段。")
        append(order)
        append("漫画文字常被切碎、顺序颠倒，也可能有识别错字，甚至整组只剩一两个字。")
        append("请先按").append(reorder).append("的正确顺序重排并纠正明显错字，")
        append("理解整句含义后，为每个气泡输出一句通顺自然的").append(target.promptName).append("。")
        append("若一组只剩一两个字，按上下文和语气补成完整的一句，不要只译那两个字。")
        append("若某一组本身只有一个省略号，原样输出「……」，不要补全、不要猜内容。")
        append("无法确定的字宁可结合上下文意译，不要音译。")
        if (target.latin) append("英文单词之间只保留一个空格，不要把单词连写，也不要把单词从中间断开。")
        if (target == TargetLanguage.CHINESE) append("不要在译文里保留日文假名。")
        append("只输出译文，条数必须与气泡数一致，每行严格按「编号. 译文」格式。")
        append("译文要短，长度尽量接近原文：气泡放不下就要缩小字号，所以不要扩写、不要加原文没有的语气词和修饰。")
        append("原文里的省略号（……）要在译文对应位置保留；但不能用省略号表示停顿、分列或猜不出来的地方，原文没有省略号时也不要自己加。")
        append("不要解释、不要复述原文、不要逐字硬译、不要添加原文没有的内容。")
        append("若有上一页译文，人名和称呼必须与之一致。")
    }

    private fun buildGroupPrompt(lang: SourceLanguage): String = buildString {
        val horizontalSource = lang.horizontal
        val order = if (horizontalSource)
            "横排里用空格分开的就是一行，读法是从左到右、从上到下。"
        else
            "竖排里用空格分开的就是一列，读法是列从右到左、列内从上到下。"
        val reorder = if (horizontalSource) "从左到右、从上到下" else "从右到左、从上到下"
        append("你是漫画翻译助手。用户每次只给你一个对话气泡里的").append(lang.promptName)
            .append("，可能被切碎、顺序颠倒，也可能有识别错字，甚至只剩一两个字。")
        append(order)
        append("请先按").append(reorder).append("重排、纠正明显错字，再译成一句通顺自然的")
            .append(target.promptName).append("。")
        append("只剩一两个字时按语气补成完整的一句，不要只译那两个字。")
        append("若原文只有一个省略号，原样输出「……」，不要补全、不要猜。")
        append("只输出译文本身：不要编号、不要解释、不要复述原文。")
        if (target.latin) append("英文单词之间只保留一个空格，不要连写，也不要把单词从中间断开。")
        if (target == TargetLanguage.CHINESE) append("不要在译文里保留日文假名。")
        append("译文要短，长度尽量接近原文；原文有省略号才保留，没有就不要自己加。")
        append("若给了前文，人名和称呼必须与之一致。")
    }

    private val LEADING_INDEX = Regex("^\\s*\\d+\\s*[.、)]\\s*")
    /** List marker inside an answer: "7. 译文", "7、译文", "7)译文". Digits are pre-normalised. */
    private val NUMBER_MARK = Regex("(?:^|\\s)(\\d{1,2})\\s*[.、)]\\s*")
    private val REPLY_LABEL = Regex(
        "^(译文|翻译|中文|简体中文|翻译结果|Translation|English|Output)\\s*[:：]\\s*",
        RegexOption.IGNORE_CASE
    )

    /** How many earlier lines are handed to a per-group request as context. */
    private const val GROUP_CONTEXT_LINES = 3

    /** Groups kept in flight at once by [translateEach]; see there for why it is not one. */
    private const val GROUP_CONCURRENCY = 3

    @Volatile
    var ocr: OcrEngine = NoOpOcrEngine

    suspend fun recognize(
        bitmap: Bitmap,
        parallel: Boolean = false,
        token: ApiClient.CancelToken? = null
    ): List<TextRegion> = ocr.recognize(bitmap, parallel, token)

    /**
     * Turns the "auto" setting into a concrete language for one page.
     *
     * Detection only ever looks at the recognised text, never at the picture: the OCR model
     * already reports which glyphs it saw, and that is the same evidence a reader would use.
     * A page with too little lettering to judge keeps Japanese, which is the default pair and
     * therefore the behaviour that existed before this option.
     */
    fun detectSource(regions: List<TextRegion>): SourceLanguage {
        var kana = 0
        var han = 0
        var latin = 0
        for (region in regions) {
            for (ch in region.text) {
                when {
                    ch.code in 0x3040..0x309F || ch.code in 0x30A0..0x30FF -> kana++
                    ch.code in 0x3400..0x9FFF -> han++
                    (ch in 'A'..'Z') || (ch in 'a'..'z') || ch.code in 0x00C0..0x024F -> latin++
                }
            }
        }
        val total = kana + han + latin
        // Anything this short is a sound effect or a page number, and saying "English" because
        // four stray letters outnumbered two stray kanji would rewrite a Japanese page.
        if (total < 6) return SourceLanguage.JAPANESE
        val language = when {
            // Kana settles it: Chinese and English pages never contain it, and the reverse
            // mistake is the expensive one — a Chinese page judged Japanese is then thrown away
            // by looksJapanese(), which wants a share of kana. Two glyphs is enough evidence.
            kana >= 2 -> SourceLanguage.JAPANESE
            // Otherwise compare the counts rather than a ratio: a Chinese page still carries a
            // few Latin letters (logos, sound effects) and must not be read as English because
            // they happen to be on a short page.
            han > latin -> SourceLanguage.CHINESE
            latin > han -> SourceLanguage.ENGLISH
            else -> SourceLanguage.JAPANESE
        }
        android.util.Log.i(
            "Translate",
            "TR detect kana=$kana han=$han latin=$latin -> ${language.name}"
        )
        return language
    }

    /** The language a page is actually handled as; [SourceLanguage.AUTO] never leaves this. */
    fun resolveSource(regions: List<TextRegion>): SourceLanguage =
        if (source.detected) detectSource(regions) else source

    /** A character that counts as real lettering in the language printed on the page. */
    private fun isSourceLetter(ch: Char, lang: SourceLanguage): Boolean = when (lang) {
        // AUTO only reaches here when a caller had no page to judge from; Japanese rules are
        // the widest of the three (they accept kanji, kana and Latin noise), so they lose least.
        SourceLanguage.JAPANESE, SourceLanguage.AUTO -> isJapaneseLetter(ch)
        SourceLanguage.CHINESE -> ch.code in 0x3400..0x9FFF
        SourceLanguage.ENGLISH ->
            (ch in 'A'..'Z') || (ch in 'a'..'z') || ch.code in 0x00C0..0x024F
    }

    /** Drops detection noise, sound effects and single stray glyphs before translating. */
    fun isUsable(region: TextRegion, lang: SourceLanguage = source): Boolean {
        // 0.55 used to throw away real but faint lettering: a perfectly readable 「よし」 came
        // back at 0.51 and vanished from its bubble, cutting the sentence short.
        if (region.confidence < 0.45f) return false
        if (region.box.width() < 10 || region.box.height() < 10) return false
        if (region.vertical && region.box.height() < region.box.width() * 1.2f) return false
        var letters = 0
        for (ch in region.text) {
            if (isSourceLetter(ch, lang)) letters++
        }
        // A lone kana is common in manga (「は？」「え？」) and dropping it cuts the sentence in
        // half. Latin-only and digit-only boxes come out of artwork lettering and sound effects,
        // so they still need at least one Japanese character — except a column holding nothing
        // but 「…」, which is the pause at the end of a line and is reattached to its bubble later.
        if (letters < 1) return isEllipsisOnly(region)
        val meaningful = region.text.count { it !in PUNCTUATION && !it.isDigit() && !it.isWhitespace() }
        if (meaningful < 1) return false
        return true
    }

    /**
     * For the default Japanese source this also keeps pages that are already Chinese or English
     * out of the translator. When the user has named the printed language themselves that
     * heuristic no longer applies, and the only question is whether there is lettering at all.
     */
    fun looksJapanese(regions: List<TextRegion>, lang: SourceLanguage = source): Boolean {
        val language = if (lang.detected) detectSource(regions) else lang
        if (language != SourceLanguage.JAPANESE) {
            return regions.sumOf { r -> r.text.count { isSourceLetter(it, language) } } >= 2
        }
        var letters = 0
        var kana = 0
        for (region in regions) {
            for (ch in region.text) {
                if (!isJapaneseLetter(ch)) continue
                letters++
                val code = ch.code
                if (code in 0x3041..0x309F || code in 0x30A0..0x30FF) kana++
            }
        }
        if (letters < 16) return true
        return kana.toFloat() / letters >= 0.04f
    }

    /** Japanese columns run right to left; Latin lines run left to right, top to bottom. */
    private fun regionOrder(lang: SourceLanguage): Comparator<TextRegion> =
        if (lang.horizontal)
            compareBy<TextRegion> { it.box.top }.thenBy { it.box.centerX() }
        else
            compareByDescending<TextRegion> { it.box.centerX() }.thenBy { it.box.top }

    private fun groupOrder(lang: SourceLanguage): Comparator<BubbleGroup> =
        if (lang.horizontal)
            compareBy<BubbleGroup> { it.bubble.top }.thenBy { it.bubble.centerX() }
        else
            compareByDescending<BubbleGroup> { it.bubble.centerX() }.thenBy { it.bubble.top }

    fun groupBubbles(
        regions: List<TextRegion>,
        width: Int,
        height: Int,
        bitmap: Bitmap? = null,
        lang: SourceLanguage = source
    ): List<BubbleGroup> {
        // Balloon outlines come from the segmentation model. They are only used for drawing the
        // cover: grouping still follows the OCR boxes, so a failed or slow model cannot change
        // which text reaches the translator.
        val balloons = if (bitmap != null) {
            BubbleSegmenter.detect(bitmap).also { found ->
                if (found.isNotEmpty()) {
                    android.util.Log.i("Translate", "TR balloons=${found.size}")
                }
            }
        } else {
            emptyList()
        }
        // An "auto" setting is settled once, here, so every rule below agrees with the prompt
        // and with the cache key that the caller derived from the same page.
        val language = if (lang.detected) detectSource(regions) else lang
        val usable = ArrayList<TextRegion>(regions.size)
        val faint = ArrayList<TextRegion>()
        regions.forEach { region ->
            if (isUsable(region, language)) {
                usable.add(region)
            } else {
                // Whatever is dropped here never reaches the model, and its bubble then keeps
                // its Japanese. Logging the rejects is what tells a missing bubble apart from
                // a bubble the translator answered badly.
                android.util.Log.d(
                    "Translate",
                    "TR skip conf=${"%.2f".format(region.confidence)} " +
                        "box=${region.box.left},${region.box.top}," +
                        "${region.box.right},${region.box.bottom} text=${region.text}"
                )
                // A real column can miss the confidence floor by a hair (0.43 against 0.45).
                // It must not be sent to the translator, but its ink is still on the page and
                // has to be cleared, or the translation is drawn over the Japanese. Only a
                // near-miss with letters of the source language counts — the artwork "C", "K"
                // and "X" boxes on the same page must not become erase rectangles.
                if (region.confidence >= 0.38f &&
                    region.text.any { isSourceLetter(it, language) }
                ) faint.add(region)
            }
        }
        if (usable.isEmpty()) return emptyList()
        // A detector can report a patch of artwork as a text box, and because such a blob is
        // wide it can reach a real bubble: on one page a 384 px wide blob read as 「左小」 sat
        // close enough to a bubble for the clustering pass to swallow it, so the merged footprint
        // ran from x=56 to x=551. The bubble test then rejected that footprint, leaving the raw
        // box as the patch — and the translation was drawn across the drawing at font 69.
        // A column is about one character cell wide, a horizontal line about one cell tall, so
        // anything several times thicker is a blob. The test only looks at the picture, which
        // means it holds for any engine, local or online.
        val plausible = if (language.horizontal) {
            usable
        } else {
            val column = usable.map {
                if (it.vertical) it.box.width() else it.box.height()
            }.sorted().let { it[it.size / 2] }.coerceIn(10, 96)
            usable.filter { region ->
                // One Japanese column is about one character cell wide, and one horizontal
                // line about one cell tall. Anything thicker than that is a detector blob: on
                // this page such a box was wide enough to reach a real bubble, so the clustering
                // pass joined them and the merged footprint spanned the drawing.
                val keep = if (region.vertical) {
                    region.box.width() <= column * 3
                } else {
                    region.box.height() <= column * 2
                }
                if (!keep) {
                    android.util.Log.d(
                        "Translate",
                        "TR skip huge box=${region.box.width()}x${region.box.height()} " +
                            "text=${region.text}"
                    )
                }
                keep
            }
        }
        if (plausible.isEmpty()) return emptyList()
        val ordered = plausible.sortedWith(regionOrder(language))
        // How far apart two columns of ONE bubble sit is a property of the book, not a constant.
        // A tightly typeset black-and-white page has overlapping boxes (gap 0), while a colour CG
        // collection sets its columns 40-46 px apart with 132 px between bubbles. The old fixed
        // "0.75 of a column width" rule fitted the first book and shattered the second into one
        // group per column, which then squeezed every translation into a 40 px strip. Measure the
        // page's typical column gap instead, and let splitAtWidestGap() separate real bubbles.
        val mergeGap = columnMergeGap(plausible)
        android.util.Log.d("Translate", "TR mergeGap=$mergeGap")
        val clusters = ArrayList<MutableList<TextRegion>>()
        val clusterOwnerOf = ArrayList<Int>()
        for (region in ordered) {
            // Columns are only allowed to join a cluster that belongs to the same balloon. On
            // CG p.9 a small bubble, the bubble next to it and the free lettering at the page
            // edge were close enough to merge into one sentence, which then had no single place
            // to be drawn. The segmentation model already says which balloon is which.
            val owner = balloonOwner(region.box, balloons)
            var target: MutableList<TextRegion>? = null
            var bestGap = Float.MAX_VALUE
            for (index in clusters.indices) {
                if (clusterOwnerOf[index] != owner) continue
                val cluster = clusters[index]
                for (member in cluster) {
                    // columns of one bubble sit side by side and share the same vertical span;
                    // they often overlap slightly, so a negative gap counts as touching
                    val gap = horizontalGap(region.box, member.box)
                    val verticalOverlap = overlap(region.box, member.box)
                    if (verticalOverlap < 0.45f) continue
                    if (gap < mergeGap && gap < bestGap) {
                        target = cluster
                        bestGap = gap
                    }
                }
            }
            if (target != null) {
                target.add(region)
            } else {
                clusters.add(mutableListOf(region))
                clusterOwnerOf.add(owner)
            }
        }
        // When the detector drops a column between two others, the surviving columns end up too
        // far apart for the pass above and are translated as two bubbles. Both translations then
        // fill the same bubble and overwrite each other. Joining them here keeps one sentence per
        // bubble; the paper between them tells whether that is really one bubble.
        val groups = if (bitmap == null) {
            clusters
        } else {
            if (clusterOwnerOf.distinct().size > 1) {
                android.util.Log.i("Translate", "TR owners=${clusterOwnerOf.joinToString(",")}")
            }
            val merged = mergeNeighbours(clusters, bitmap, clusterOwnerOf.toMutableList())
            if (merged.size != clusters.size) {
                android.util.Log.i("Translate", "TR merge ${clusters.size}->${merged.size}")
            }
            merged
        }
        // A trailing 「…」 is often detected as its own column on the line below the text. It
        // carries no kana, so it survives the filter above but forms a group of its own; this
        // hands it back to the bubble it belongs to.
        val withMarks = absorbEllipsis(groups)
        // A fragment on its own is noise: one kana that lost the rest of its column would be
        // translated into a stray word floating over the artwork. Two or more letters are kept,
        // because a short but real line like 「よし」 must not disappear. The exception is a
        // single kana that sits inside a clean white bubble: 「ん…?」「は？」 are complete lines
        // of dialogue, and dropping them left the bubble in Japanese. The bubble test is what
        // separates them from lettering printed on the artwork, which is still filtered out.
        val kept = withMarks.filter { cluster ->
            val letters =
                cluster.sumOf { region -> region.text.count { isSourceLetter(it, language) } }
            val keep = letters >= 2 ||
                // A column of nothing but 「…」 still has to be kept: it covers lettering on the
                // page, and skipping it leaves the original dots showing between translated lines.
                cluster.all { isEllipsisOnly(it) } ||
                (bitmap != null && letters >= 1 && patchOf(bitmap, cluster) != null)
            if (!keep) {
                android.util.Log.d(
                    "Translate",
                    "TR skip group letters=$letters text=${cluster.joinToString(" ") { it.text }}"
                )
            }
            keep
        }
        val horizontalSource = language.horizontal
        val japaneseSource = language == SourceLanguage.JAPANESE
        // Columns of one bubble are packed: the detector pads its boxes, so neighbours overlap and
        // the gap between them is zero. Two bubbles that have been clustered together are the only
        // case where a real gap appears inside a group — on one page four columns came back with
        // gaps 0, 14, 0 and the 14 was the boundary between "舐めててもいいが" and "彼氏待たせるぞ
        // いいのか". Splitting at the widest gap keeps the two sentences apart.
        val separated = kept.flatMap { cluster -> splitAtWidestGap(cluster, language) }
        // A long column is sometimes returned as several boxes stacked on top of each other.
        // Their patches overlap, so each one would erase and repaint the same strip of page and
        // the translations end up printed on top of one another.
        val joined = mergeOverlapping(separated)
        // A full-width block of free lettering (a whole page of narration) is detected as one
        // very wide group. Keeping it whole produces a single translation which only fills a few
        // columns in the middle, so the rest of the Japanese stays visible next to the Chinese.
        // Such a block has no bubble outline and is made of many independent columns; each one
        // is its own reading unit, just like a separate speech balloon. The size and column
        // count gates keep ordinary multi-column dialogue bubbles intact.
        val split = joined.flatMap { cluster ->
            val union = unionOf(cluster)
            val unit = clusterUnit(cluster)
            if (cluster.size >= 5 && union.width() > unit * 6f &&
                bitmap != null && !insideWhiteBubble(bitmap, union)
            ) {
                android.util.Log.i(
                    "Translate", "TR split columns n=${cluster.size} w=${union.width()}"
                )
                cluster.map { mutableListOf(it) }
            } else listOf(cluster)
        }
        return split.map { cluster ->
            // Sort by column first, then by position inside the column. A column that the
            // detector split into an upper and a lower box has almost the same centre x, and a
            // plain (x, y) sort interleaved the two columns so the sentence read
            // 「俺と涼香さ 切り裂くよらに Cんの間の…」 instead of following each column down.
            val orderedRegions = orderByColumns(cluster)
            cluster.clear()
            cluster.addAll(orderedRegions)
            val union = Rect(cluster[0].box)
            cluster.forEach { union.union(it.box) }
            val vertical = if (horizontalSource) false
                else cluster.count { it.vertical } * 2 >= cluster.size
            // Columns (or, for Latin lettering, lines) are separated by a space so the model can
            // see where one ends. Without it a five-column sentence arrives as one run and is
            // reordered by guesswork, which looked like a recognition error.
            val columns = buildString {
                cluster.forEachIndexed { index, region ->
                    if (index > 0 && (vertical || horizontalSource) && !isEllipsisOnly(region)) {
                        append(COLUMN_BREAK)
                    }
                    append(region.text)
                }
            }
            // MangaOCR_mobile was trained on Japanese manga. For any other printed language the
            // column join is the honest fallback instead of guessing at an alien script.
            val text = if (japaneseSource)
                MangaOcr.readIfSelected(bitmap, union) ?: columns
            else columns
            val unit = clusterUnit(cluster)
            val extra = faint.filter { f ->
                val sharedY = min(union.bottom, f.box.bottom) - max(union.top, f.box.top)
                sharedY > 0 && sharedY >= f.box.height() * 0.5f &&
                    horizontalGap(union, f.box) <= unit * 2.2f
            }.map { Rect(it.box) }
            if (extra.isNotEmpty()) {
                android.util.Log.d("Translate", "TR faint erase=${extra.size} text=$text")
            }
            val balloon = balloons.maxByOrNull { areaOverlap(it.box, union) }
            val contour = balloon
                ?.takeIf { areaOverlap(it.box, union) >= 0.5f }
                ?.contour
                // A balloon next to a block of free lettering can overlap it just enough to be
                // picked, and its outline then covers the wrong part of the page. Only trust an
                // outline that actually spans the text it is supposed to hide.
                ?.takeIf { contourCovers(it, union) }
            if (contour != null) {
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                var at = 0
                while (at + 1 < contour.size) {
                    minX = minOf(minX, contour[at])
                    maxX = maxOf(maxX, contour[at])
                    minY = minOf(minY, contour[at + 1])
                    maxY = maxOf(maxY, contour[at + 1])
                    at += 2
                }
                android.util.Log.d(
                    "Translate",
                    "TR contour points=${contour.size / 2} " +
                        "box=${minX.toInt()},${minY.toInt()},${maxX.toInt()},${maxY.toInt()} " +
                        "union=${union.left},${union.top},${union.right},${union.bottom}"
                )
            }
            BubbleGroup(cluster, union, text, vertical, extra, contour)
        }.sortedWith(groupOrder(language))
            .also { list ->
                // Grouping decides whether one sentence reaches the model as one piece or as two
                // that overwrite each other on the page, so it is worth seeing in a bug report.
                list.forEachIndexed { i, g ->
                    android.util.Log.d(
                        "Translate",
                        "TR src$i box=${g.bubble.left},${g.bubble.top}," +
                            "${g.bubble.right},${g.bubble.bottom} v=${g.vertical} text=${g.source}"
                    )
                }
            }
    }

    /**
     * Joins groups whose footprints actually overlap in both directions.
     *
     * This is not another "how far apart are two columns" rule — those were tried and broke
     * other books. It only fires when two groups would be given overlapping patches, which is a
     * certainty of drawing one translation on top of another. The detector slices a tall column
     * of lettering into stacked boxes whose ends overlap by 10-20% of their height, while two
     * separate bubbles have their own outlines and do not overlap at all.
     */
    private fun mergeOverlapping(
        groups: List<MutableList<TextRegion>>
    ): List<MutableList<TextRegion>> {
        val out = groups.map { ArrayList(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in out.indices) {
                for (j in i + 1 until out.size) {
                    val a = unionOf(out[i])
                    val b = unionOf(out[j])
                    val overlapX = min(a.right, b.right) - max(a.left, b.left)
                    val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
                    if (overlapX <= 0 || overlapY <= 0) continue
                    val ratioX = overlapX.toFloat() / min(a.width(), b.width())
                    val ratioY = overlapY.toFloat() / min(a.height(), b.height())
                    if (ratioX < 0.70f || ratioY < 0.10f) continue
                    android.util.Log.i(
                        "Translate",
                        "TR stack merge x=${"%.2f".format(ratioX)} y=${"%.2f".format(ratioY)} " +
                            "a=${a.left},${a.top},${a.right},${a.bottom} " +
                            "b=${b.left},${b.top},${b.right},${b.bottom}"
                    )
                    out[i].addAll(out[j])
                    out.removeAt(j)
                    changed = true
                    break@outer
                }
            }
        }
        return out
    }

    /**
     * Cuts a group in two where one gap is unusually wide compared with the others.
     *
     * "Unusually wide" is relative to the same group, not an absolute number. On a tightly set
     * black-and-white page columns touch (0) while two bubbles leave 13; on a loosely set colour
     * book every column is 40-46 apart, so none of those gaps stands out and the bubble stays
     * whole. A fixed threshold cannot serve both books.
     */
    private fun splitAtWidestGap(
        cluster: List<TextRegion>,
        lang: SourceLanguage
    ): List<MutableList<TextRegion>> {
        if (cluster.size < 3 || lang.horizontal) return listOf(ArrayList(cluster))
        val ordered = cluster.sortedWith(regionOrder(lang))
        val widths = ordered.map { it.box.width() }.sorted()
        val unit = widths[widths.size / 2].coerceIn(10, 96).toFloat()
        val gaps = ArrayList<Float>()
        var at = -1
        var widest = -1f
        for (i in 0 until ordered.size - 1) {
            val gap = horizontalGap(ordered[i].box, ordered[i + 1].box)
            gaps.add(gap)
            if (gap > widest) {
                widest = gap
                at = i
            }
        }
        val runnerUp = gaps.sortedDescending().getOrElse(1) { 0f }
        // Too small to be a boundary; too large means a whole column was dropped (that is what
        // mergeNeighbours is for); or merely the widest of several similar gaps, which is normal
        // typesetting rather than a bubble outline.
        if (at < 0 || widest < maxOf(8f, unit * 0.3f) || widest > unit * 1.2f ||
            widest < runnerUp * 1.8f + unit * 0.15f
        ) {
            return listOf(ArrayList(cluster))
        }
        android.util.Log.i(
            "Translate",
            "TR cut gap=%.0f next=%.0f unit=%.0f n=%d|%d".format(
                widest, runnerUp, unit, at + 1, ordered.size - at - 1
            )
        )
        return listOf(
            ArrayList(ordered.subList(0, at + 1)),
            ArrayList(ordered.subList(at + 1, ordered.size))
        )
    }

    /**
     * How close two columns must be to count as one bubble, learned from this page.
     *
     * Adjacent columns that share most of their height give us a sample of the book's column
     * spacing; the median of the non-zero gaps is the typical gap inside a bubble. The gap
     * between two bubbles is much larger, so allowing 1.8x the typical gap gathers columns of
     * one bubble without reaching into the next. The old floor keeps tightly set pages working,
     * and the ceiling stops a noisy page from swallowing everything.
     */
    private fun columnMergeGap(regions: List<TextRegion>): Float {
        val widths = regions.map { it.box.width() }.sorted()
        val unit = widths[widths.size / 2].coerceIn(10, 96).toFloat()
        val floor = unit * 0.75f
        val ordered = regions.sortedByDescending { it.box.centerX() }
        val gaps = ArrayList<Float>()
        for (i in 0 until ordered.size - 1) {
            val a = ordered[i].box
            val b = ordered[i + 1].box
            if (overlap(a, b) < 0.45f) continue
            val gap = horizontalGap(a, b)
            if (gap > 0f) gaps.add(gap)
        }
        if (gaps.isEmpty()) return floor
        gaps.sort()
        val typical = gaps[gaps.size / 2]
        return maxOf(floor, minOf(typical * 1.8f, unit * 3f))
    }

    /** True for a detected column that holds only 「…」 and carries no letters of its own. */
    private fun isEllipsisOnly(region: TextRegion): Boolean {        val stripped = region.text.filter { it !in PUNCTUATION && it != '.' && !it.isWhitespace() }
        if (stripped.isNotEmpty()) return false
        return region.text.any { it in ELLIPSIS_CHARS } || region.text.count { it == '.' } >= 2
    }

    /**
     * Attaches each standalone 「…」 column to the bubble it sits beside. The pause belongs to
     * the sentence, but the detector reports it as a separate column, and a group of one
     * punctuation mark has no letters and would be filtered out as noise.
     */
    private fun absorbEllipsis(
        clusters: List<MutableList<TextRegion>>
    ): MutableList<MutableList<TextRegion>> {
        val rest = clusters.filter { cluster -> cluster.none { isEllipsisOnly(it) } }.toMutableList()
        val marks = clusters.filter { cluster -> cluster.all { isEllipsisOnly(it) } }
        if (marks.isEmpty()) return rest
        for (mark in marks) {
            val box = mark[0].box
            var best: MutableList<TextRegion>? = null
            var bestDistance = Float.MAX_VALUE
            for (cluster in rest) {
                for (member in cluster) {
                    val gapX = horizontalGap(box, member.box)
                    val gapY = verticalGap(box, member.box)
                    val unit = min(min(box.width(), member.box.width()).toFloat(), 96f)
                        .coerceAtLeast(12f)
                    // A column directly above/below, immediately alongside, or diagonally next
                    // to the text all belong to the same bubble; further away it is somebody
                    // else's pause and must stay where it is.
                    val reach = unit * 1.5f
                    if (gapX > reach || gapY > reach) continue
                    val distance = gapX + gapY
                    if (distance < bestDistance) {
                        best = cluster
                        bestDistance = distance
                    }
                }
            }
            if (best != null) best.addAll(mark) else rest.add(mark)
        }
        return rest
    }

    private fun mergeNeighbours(
        clusters: List<MutableList<TextRegion>>,
        source: Bitmap,
        owners: MutableList<Int> = MutableList(clusters.size) { -1 }
    ): List<MutableList<TextRegion>> {
        val groupOwners = owners.toMutableList()
        val groups = clusters.map { ArrayList(it) }.toMutableList()
        // The patch each cluster would get on its own, exactly as render() computes it. Two
        // halves of one bubble grow into the same white interior and therefore overlap; two
        // separate bubbles are each stopped by their own outline and stay apart. Testing the
        // patches rather than the gap means grouping and drawing can never disagree.
        val patches = groups.map { patchOf(source, it, neighboursOf(groups, it)) }.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            var i = 0
            while (i < groups.size && !merged) {
                val a = patches[i]
                if (a == null) { i++; continue }
                var j = i + 1
                while (j < groups.size) {
                    val b = patches[j]
                    if (b == null) { j++; continue }
                    // Different balloons stay separate even when their patches grow into each
                    // other; only text that shares a balloon (or has none) may be joined.
                    if (groupOwners[i] != groupOwners[j]) { j++; continue }
                    val union = unionOf(groups[i])
                    val other = unionOf(groups[j])
                    if (!sideBySide(union, other)) { j++; continue }
                    val shared = intersects(a, b)
                        .toFloat() / minOf(a.width() * a.height(), b.width() * b.height())
                    // Measured on real pages: two halves of one bubble grow into the same
                    // interior and score 0.34~0.44, while two separate bubbles sharing a white
                    // patch score about 0.12. 0.25 sits in the gap between them.
                    if (shared >= 0.25f) {
                        android.util.Log.d(
                            "Translate",
                            "TR merge pair share=${"%.2f".format(shared)} " +
                                "a=${a.left},${a.top},${a.right},${a.bottom} " +
                                "b=${b.left},${b.top},${b.right},${b.bottom}"
                        )
                        groups[i].addAll(groups[j])
                        patches[i] = patchOf(source, groups[i], neighboursOf(groups, groups[i]))
                        groups.removeAt(j)
                        patches.removeAt(j)
                        groupOwners.removeAt(j)
                        merged = true
                        break
                    }
                    j++
                }
                i++
            }
        }
        return groups
    }

    /** Footprints of the other clusters, so their lettering is not mistaken for artwork. */
    private fun neighboursOf(
        groups: List<MutableList<TextRegion>>,
        self: List<TextRegion>
    ): List<Rect> = groups.filter { it !== self }.map { unionOf(it) }

    private fun unionOf(cluster: List<TextRegion>): Rect {
        val union = Rect(cluster[0].box)
        cluster.forEach { union.union(it.box) }
        return union
    }

    /**
     * The patch a cluster would be given if it were rendered alone, or null when it sits on
     * artwork and is therefore not grown at all. Mirrors render() so the two stay consistent.
     */
    private fun patchOf(
        source: Bitmap,
        cluster: List<TextRegion>,
        others: List<Rect> = emptyList()
    ): Rect? {
        val seed = unionOf(cluster)
        if (medianLuminance(source, seed) < 100) return null
        if (!insideWhiteBubble(source, seed, others)) return null
        val area = bubbleSafeArea(source, seed, clusterUnit(cluster), emptyList())
        if (area.width() < seed.width() || area.height() < seed.height()) return null
        return area
    }

    /** Column thickness of a cluster, used as the yardstick when growing its patch. */
    private fun clusterUnit(cluster: List<TextRegion>): Int {
        val sizes = cluster.map {
            if (it.vertical) it.box.width() else it.box.height()
        }.sorted()
        if (sizes.isEmpty()) return 24
        return sizes[sizes.size / 2].coerceIn(10, 96)
    }

    /**
     * Coarse geometric gate: only clusters that sit beside each other and share most of their
     * height can be two halves of one bubble. Keeps a patch that happens to be large from
     * pairing with anything far away.
     */
    private fun sideBySide(a: Rect, b: Rect): Boolean {
        val shared = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (shared < minOf(a.height(), b.height()) * 0.4f) return false
        val gap = maxOf(0, maxOf(a.left - b.right, b.left - a.right))
        val unit = minOf(a.width(), b.width()).coerceAtLeast(8)
        return gap <= unit * 2.5f
    }

    private fun intersects(a: Rect, b: Rect): Int {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0
        return w * h
    }

    private fun horizontalGap(a: Rect, b: Rect): Float =
        maxOf(0f, maxOf(a.left - b.right, b.left - a.right).toFloat())

    private fun verticalGap(a: Rect, b: Rect): Float =
        maxOf(0f, maxOf(a.top - b.bottom, b.top - a.bottom).toFloat())

    private fun overlap(a: Rect, b: Rect): Float {
        val top = maxOf(a.top, b.top)
        val bottom = minOf(a.bottom, b.bottom)
        val shared = bottom - top
        val shorter = minOf(a.height(), b.height()).coerceAtLeast(1)
        return (shared.toFloat() / shorter).coerceAtLeast(0f)
    }

    /** Index of the balloon this text sits in, or -1 when it is lettering on the artwork. */
    private fun balloonOwner(union: Rect, balloons: List<BubbleRegion>): Int {
        var best = -1
        var score = 0f
        balloons.forEachIndexed { index, balloon ->
            val share = areaOverlap(balloon.box, union)
            if (share > score) {
                score = share
                best = index
            }
        }
        return if (score >= 0.5f) best else -1
    }

    /**
     * Groups regions into columns (by horizontal overlap) and reads each column from the top
     * down, columns from right to left. Keeps split chunks of one column in order.
     */
    private fun orderByColumns(regions: List<TextRegion>): List<TextRegion> {
        val columns = ArrayList<MutableList<TextRegion>>()
        for (region in regions.sortedByDescending { it.box.centerX() }) {
            val column = columns.firstOrNull { members ->
                val a = members[0].box
                val shared = minOf(a.right, region.box.right) - maxOf(a.left, region.box.left)
                val narrower = minOf(a.width(), region.box.width()).coerceAtLeast(1)
                shared.toFloat() / narrower > 0.5f
            }
            if (column == null) columns.add(mutableListOf(region)) else column.add(region)
        }
        return columns.flatMap { members -> members.sortedBy { it.box.top } }
    }

    /** True when the outline's bounding box covers at least half of the text footprint. */
    private fun contourCovers(contour: FloatArray, union: Rect): Boolean {
        if (contour.size < 6) return false
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var at = 0
        while (at + 1 < contour.size) {
            minX = minOf(minX, contour[at])
            maxX = maxOf(maxX, contour[at])
            minY = minOf(minY, contour[at + 1])
            maxY = maxOf(maxY, contour[at + 1])
            at += 2
        }
        val left = maxOf(minX, union.left.toFloat())
        val top = maxOf(minY, union.top.toFloat())
        val right = minOf(maxX, union.right.toFloat())
        val bottom = minOf(maxY, union.bottom.toFloat())
        if (right <= left || bottom <= top) return false
        val covered = (right - left) * (bottom - top)
        val area = union.width().toFloat() * union.height()
        return area > 0f && covered / area >= 0.5f
    }

    /** Two-dimensional intersection over the smaller area, used to match text to a balloon. */
    private fun areaOverlap(a: Rect, b: Rect): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0f
        val smaller = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return (w.toFloat() * h / smaller).coerceAtLeast(0f)
    }

    suspend fun translate(
        groups: List<BubbleGroup>,
        api: String,
        model: String,
        context: List<String> = emptyList(),
        apiKey: String = "",
        protocol: ApiProtocol = ApiProtocol.DEFAULT,
        sourceLang: SourceLanguage = source,
        token: ApiClient.CancelToken? = null
    ): List<String>? = withContext(Dispatchers.IO) {
        if (groups.isEmpty()) return@withContext emptyList()
        val numbered = buildString {
            if (context.isNotEmpty()) {
                append("【上一页译文，仅供保持人名、称呼和语气一致】\n")
                context.forEach { append(it).append('\n') }
                append("\n【本页原文】\n")
            }
            groups.forEachIndexed { i, group ->
                append(i + 1).append(". ").append(group.source).append('\n')
            }
        }
        var lastError = "unknown"
        repeat(2) { attempt ->
            // A wrong key or a closed port fails identically the second time, so only timeouts
            // and the service's own 429/5xx are retried.
            when (val result = ApiClient.chat(
                protocol, api, model, apiKey, systemPrompt(sourceLang), numbered, token
            )) {
                is ApiClient.ChatResult.Ok -> {
                    val parsed = parse(result.text, groups)
                    return@withContext fillBlanks(
                        parsed, groups, protocol, api, model, apiKey, context, sourceLang, token
                    )
                }
                is ApiClient.ChatResult.Fail -> {
                    lastError = result.reason
                    android.util.Log.w("Translate", "TR fail ${result.reason}")
                    if (token?.cancelled == true) return@withContext null
                    if (!result.retryable) {
                        android.util.Log.w("Translate", "TR no retry: $lastError")
                        return@withContext null
                    }
                    if (attempt == 0) delay(1000)
                }
            }
        }
        android.util.Log.w("Translate", "TR gave up: $lastError")
        null
    }

    /**
     * Fills the entries a whole-page answer skipped.
     *
     * A page request is one numbered list, and the model habitually drops a line: a lone 「ん…」,
     * a fragment it decided was not a sentence, or two short answers compressed into one entry.
     * Left alone, that index stays empty, [render] skips it, and the bubble keeps its Japanese —
     * which reads like the text was never recognised. Asking for those bubbles one at a time is
     * cheap (usually one or two per page) and almost never fails.
     */
    private suspend fun fillBlanks(
        lines: List<String>,
        groups: List<BubbleGroup>,
        protocol: ApiProtocol,
        api: String,
        model: String,
        apiKey: String,
        context: List<String>,
        sourceLang: SourceLanguage,
        token: ApiClient.CancelToken?
    ): List<String> {
        // A punctuation-only bubble needs no request at all: it has no sentence to translate,
        // and asking for one is what makes a small model recite the instructions back.
        val missing = lines.indices.filter {
            lines[it].isBlank() && !isTrivialSource(groups[it].source)
        }
        // Nothing parsed at all is a different failure (wrong format, empty answer): retrying
        // every group one by one would turn one bad page into a page of requests.
        if (missing.isEmpty() || missing.size == lines.size) return lines
        val hint = context.filter { it.isNotBlank() }
            .takeLast(GROUP_CONTEXT_LINES)
            .joinToString("\n")
        val fixed = lines.toMutableList()
        val gate = Semaphore(GROUP_CONCURRENCY)
        val write = Mutex()
        var repaired = 0
        coroutineScope {
            missing.forEach { index ->
                launch {
                    gate.withPermit {
                        if (token?.cancelled == true) return@withPermit
                        val text = askGroup(
                            protocol, api, model, apiKey, hint, groups[index].source,
                            index, sourceLang, token
                        ) ?: return@withPermit
                        write.withLock {
                            fixed[index] = text
                            repaired++
                        }
                    }
                }
            }
        }
        android.util.Log.i("Translate", "TR repair blank=${missing.size} ok=$repaired")
        return fixed
    }

    /**
     * Translates one group per request.
     *
     * A whole-page request has two costs: one dropped connection loses the page, and nothing can
     * be drawn until the model has answered for every bubble. Sending each group on its own lets
     * the reader paint bubbles one at a time as they arrive, and a group that fails leaves only
     * that bubble in Japanese.
     *
     * [GROUP_CONCURRENCY] requests are kept in flight. A hosted endpoint genuinely overlaps them;
     * a self-hosted one usually serves one at a time, so the surplus simply queues — either way
     * the first bubble arrives no later than it would in a strict sequence, and a page of 18
     * groups no longer takes 18 × the latency of one.
     *
     * [onGroup] is invoked under a lock, so callers may touch Compose state or a shared bitmap
     * from it without guarding anything themselves. It runs on an IO thread.
     * Returns null when every group failed.
     */
    suspend fun translateEach(
        groups: List<BubbleGroup>,
        api: String,
        model: String,
        context: List<String> = emptyList(),
        apiKey: String = "",
        protocol: ApiProtocol = ApiProtocol.DEFAULT,
        concurrency: Int = GROUP_CONCURRENCY,
        sourceLang: SourceLanguage = source,
        token: ApiClient.CancelToken? = null,
        onGroup: suspend (index: Int, text: String) -> Unit = { _, _ -> }
    ): List<String>? = withContext(Dispatchers.IO) {
        if (groups.isEmpty()) return@withContext emptyList()
        val hint = context.filter { it.isNotBlank() }
            .takeLast(GROUP_CONTEXT_LINES)
            .joinToString("\n")
        val lines = MutableList(groups.size) { "" }
        var answered = 0
        val gate = Semaphore(concurrency.coerceAtLeast(1))
        // Painting is serialised even though the requests are not: the caller updates Compose
        // state and a page bitmap, and neither tolerates writes from several threads at once.
        val paint = Mutex()
        coroutineScope {
            groups.forEachIndexed { index, group ->
                // Nothing to ask for a mark-only bubble; it keeps its original glyph. Counted as
                // handled so a page made entirely of marks is not reported as a failed request.
                if (isTrivialSource(group.source)) {
                    answered++
                    return@forEachIndexed
                }
                launch {
                    gate.withPermit {
                        val text = askGroup(
                            protocol, api, model, apiKey, hint, group.source, index,
                            sourceLang, token
                        ) ?: return@withPermit
                        paint.withLock {
                            lines[index] = text
                            answered++
                            onGroup(index, text)
                        }
                    }
                }
            }
        }
        android.util.Log.i("Translate", "TR each groups=${groups.size} answered=$answered")
        if (answered == 0) null else lines
    }

    /**
     * One request for one group, retried once.
     *
     * The retry covers both a dropped socket and an answer that carried nothing usable — an
     * echoed Japanese line, or just 「…」. Both used to leave the bubble in Japanese; the second
     * attempt says so in the prompt, which is usually enough to get a real translation.
     *
     * Returns null when the model gave nothing usable, so the caller keeps that bubble's original
     * text instead of erasing it and painting nothing.
     */
    private suspend fun askGroup(
        protocol: ApiProtocol,
        api: String,
        model: String,
        apiKey: String,
        hint: String,
        source: String,
        index: Int,
        sourceLang: SourceLanguage,
        token: ApiClient.CancelToken? = null
    ): String? {
        var lastError = "unknown"
        for (attempt in 0 until 2) {
            if (token?.cancelled == true) return null
            val user = buildString {
                if (hint.isNotEmpty()) {
                    append("【前文，仅供人名、称呼和语气参考】\n").append(hint).append("\n\n")
                }
                append("【要翻译的原文】\n").append(source)
                if (attempt > 0) {
                    append("\n\n上一次没有给出可用的译文。这次请直接给出")
                        .append(target.promptName)
                        .append("译文本身，不要照抄原文，也不要输出省略号占位。")
                }
            }
            when (val result = ApiClient.chat(
                protocol, api, model, apiKey, groupPrompt(sourceLang), user, token
            )) {
                is ApiClient.ChatResult.Ok -> {
                    val value = tidyGroup(result.text, source)
                    if (value.isNotEmpty()) return value
                    lastError = "empty answer"
                    android.util.Log.w("Translate", "TR group $index empty answer")
                    if (token?.cancelled == true) return null
                }
                is ApiClient.ChatResult.Fail -> {
                    lastError = result.reason
                    android.util.Log.w("Translate", "TR group $index fail ${result.reason}")
                    if (token?.cancelled == true) return null
                    if (!result.retryable) break
                    delay(800)
                }
            }
        }
        android.util.Log.w("Translate", "TR group $index gave up ($lastError)")
        return null
    }

    /**
     * Cleans a single-group answer. Models sometimes wrap it ("译文：…") or split a long answer
     * over two lines, and some simply echo the Japanese back — drawing that over the bubble is
     * worse than leaving it alone, so an echo becomes an empty result.
     */
    private fun tidyGroup(raw: String, source: String): String {
        val parts = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        // Keep every line that carries text: taking only the last one dropped the first half of
        // answers the model decided to wrap, and the bubble then read like a fragment.
        val body = buildString {
            parts.forEach { line ->
                if (line.startsWith("原文")) return@forEach
                append(LEADING_INDEX.replace(REPLY_LABEL.replace(line, ""), ""))
            }
        }
        // An echoed source, a leaked instruction or a made-up sentence is worse than no answer.
        return sanitize(tidy(body, source), source)
    }

    /**
     * True when the answer is just the original line handed back. Column marks and whitespace are
     * ignored, because a Latin target legitimately keeps spaces and the source marker is an
     * input-only cue.
     */
    private fun isEcho(value: String, source: String): Boolean {
        if (value.isEmpty()) return false
        val flat = { s: String -> s.replace(COLUMN_MARKS, "").replace(WHITESPACE, "") }
        return flat(value) == flat(source)
    }

    /**
     * Wording that belongs to our own instructions, never to a line of dialogue.
     *
     * A bubble holding nothing but 「！」 is often read by OCR as a stray 「一•」. Asked to
     * translate that on its own, a small model tends to answer by repeating the request back —
     * the 「【要翻译的原文】」 label, the retry note, or a whole paragraph of the system prompt.
     * [isEcho] only catches an exact copy of the source, so those 50-odd characters used to be
     * accepted and squeezed into a 147x98 bubble at 12 px, which is exactly the "mystery text"
     * on the page. Rejecting the answer leaves the original glyph in place, which is what a
     * punctuation-only bubble should show anyway.
     */
    private val PROMPT_LEAK = Regex(
        "【|】|不要照抄|不要输出|不要解释|不要复述|不要补全|译文本身|译文要短|" +
            "只输出译文|上一次没有|上次没有|请直接给出|仅供|前文|上一页译文|" +
            "要翻译的原文|省略号占位|条数必须|人名和称呼|纠正明显错字|按编号"
    )

    /**
     * Punctuation-only bubbles and a single stray kanji carry no sentence to translate. A lone
     * kana is real dialogue (「え？」「ん…」) and is deliberately not included here.
     */
    private fun isTrivialSource(text: String): Boolean {
        // Keep only characters that can carry a sentence. OCR turns 「！」 into things like
        // 「一•」 (a stray kanji plus a bullet), and that bullet is not in PUNCTUATION, so a
        // whitelist is safer than stripping known punctuation here.
        val meaningful = text.filter { ch ->
            ch.code in 0x3040..0x30FF || ch.code in 0x3400..0x9FFF ||
                ch in 'A'..'Z' || ch in 'a'..'z' || ch.isDigit()
        }
        if (meaningful.isEmpty()) return true
        if (meaningful.length > 1) return false
        val ch = meaningful[0]
        return !(ch.code in 0x3040..0x309F || ch.code in 0x30A0..0x30FF ||
            ch in 'A'..'Z' || ch in 'a'..'z')
    }

    /**
     * Last gate before an answer reaches the page or the cache. It is deliberately shared by
     * whole-page, per-group and cached answers: a bad reply must not be drawn just because it
     * came from a different code path (or from a translation saved before this check existed).
     */
    private fun sanitize(value: String, source: String): String {
        if (value.isEmpty() || isEcho(value, source)) return ""
        if (PROMPT_LEAK.containsMatchIn(value)) {
            android.util.Log.w("Translate", "TR reject prompt leak len=${value.length}")
            return ""
        }
        // Four times the source plus slack still allows the short line the prompt asks the model
        // to complete (「え？」 -> 「诶？」), but no instruction-sized paragraph fits a bubble.
        if (value.length > source.length * 4 + 12) {
            android.util.Log.w("Translate", "TR reject oversize len=${value.length} src=${source.length}")
            return ""
        }
        // A bubble holding only a mark must not receive a sentence the model made up for it.
        if (isTrivialSource(source) && !isTrivialSource(value)) {
            android.util.Log.w("Translate", "TR reject invented line for mark len=${value.length}")
            return ""
        }
        return value
    }

    /**
     * Lists the models the endpoint offers, using the route the selected protocol actually serves.
     * The failure reason is passed back to the UI: "Key 不对" and "端口没开" are different problems
     * and the user can only fix one of them.
     */
    suspend fun fetchModels(
        api: String,
        apiKey: String,
        protocol: ApiProtocol = ApiProtocol.DEFAULT
    ): ApiClient.ModelsResult = withContext(Dispatchers.IO) {
        if (api.isBlank()) return@withContext ApiClient.ModelsResult.Fail("没填服务地址")
        ApiClient.listModels(protocol, api, apiKey)
    }

    /**
     * Reads the numbered answer back into one line per group.
     *
     * A line-per-entry reading is what left bubbles in Japanese. Two habits of the model break
     * it: several short answers share one line ("3. 啊 4. 嗯…"), and the list marker comes back
     * with full-width digits or a full-width dot. Both used to make the later indexes vanish, so
     * their bubbles were never drawn. Markers are therefore found anywhere in the text, and only
     * an ascending index within range counts, which keeps a line like "1.5倍" from splitting.
     *
     * Text that carries no marker at all is treated as the continuation of the entry above —
     * that is what a wrapped translation looks like — and an answer with no markers whatsoever
     * falls back to plain line order.
     */
    private fun parse(content: String, groups: List<BubbleGroup>): List<String> {
        val count = groups.size
        val text = buildString(content.length) {
            content.forEach { ch ->
                append(
                    when {
                        ch in '０'..'９' -> '0' + (ch - '０')
                        ch == '．' -> '.'
                        else -> ch
                    }
                )
            }
        }
        // index (0-based), body start, marker start
        val entries = ArrayList<Triple<Int, Int, Int>>()
        var lastIndex = 0
        var cursor = 0
        while (cursor < text.length) {
            val match = NUMBER_MARK.find(text, cursor) ?: break
            val index = match.groupValues[1].toIntOrNull()
            val bodyStart = match.range.last + 1
            val followedByDigit = bodyStart < text.length && text[bodyStart].isDigit()
            if (index != null && index in 1..count && index > lastIndex && !followedByDigit) {
                entries.add(Triple(index - 1, bodyStart, match.range.first))
                lastIndex = index
            }
            cursor = match.range.last + 1
        }
        val sourceOf = { i: Int -> groups.getOrNull(i)?.source.orEmpty() }
        if (entries.isEmpty()) {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return List(count) { i ->
                val source = sourceOf(i)
                sanitize(tidy(lines.getOrNull(i).orEmpty(), source), source)
            }
        }
        val out = MutableList(count) { "" }
        entries.forEachIndexed { k, entry ->
            val end = entries.getOrNull(k + 1)?.third ?: text.length
            val source = sourceOf(entry.first)
            val value = tidy(text.substring(entry.second, end), source)
            // An echoed Japanese line is not a translation: treat it as unanswered so the
            // per-bubble repair gets a chance, instead of erasing the bubble and painting the
            // original text back on. The same gate drops leaked instructions and paragraphs.
            out[entry.first] = sanitize(value, source)
        }
        val blank = out.count { it.isEmpty() }
        if (blank > 0) {
            android.util.Log.i("Translate", "TR parse answered=${count - blank}/$count")
        }
        return out
    }

    /**
     * Strips what must never reach the page. The column marker is an input-only cue, and the
     * model likes to sprinkle 「……」 wherever it guessed at a break; drawn into a bubble those
     * read as leftover junk, so they are removed here instead of being trusted to the prompt.
     * Ellipses that the Japanese original actually contains are kept.
     */
    private fun tidy(text: String, source: String): String {
        // A CJK line has no word spaces, so any whitespace is a leaked column boundary and is
        // dropped along with the old marker. Latin and Korean output needs those spaces: fold
        // runs into one instead of gluing every word together.
        var out = if (target.keepSpaces) {
            text.replace(COLUMN_MARKS, "").replace(WHITESPACE, " ").trim()
        } else {
            text.replace(WHITESPACE, "").replace(COLUMN_MARKS, "").trim()
        }
        // The model scatters pauses of its own at guessed breaks. The original is the only
        // authority on how many there should be, so the count is capped by it — that keeps a
        // real 「…」 and stops the page filling up with dots.
        val allowed = ELLIPSIS_RUN.findAll(source).count()
        if (allowed == 0) {
            out = out.replace(ELLIPSIS_RUN, "")
        } else {
            var seen = 0
            out = ELLIPSIS_RUN.replace(out) { if (++seen <= allowed) "…" else "" }
        }
        // Removing a run can leave doubled or leading punctuation behind.
        out = out.replace(DOUBLE_PUNCT, "$1").trim()
        return out.trimStart('，', '、', '。', '；', '：', ',')
    }

    /**
     * Cover each patch with its sampled background colour and write the translation on top,
     * instead of erasing ink pixel by pixel. Coloured lettering, white outlines, halftone dots
     * and anti-aliased fringes all survived that eraser in some form; a solid cover hides them
     * at once. This only works because groupBubbles() now splits a full-width free-lettering
     * block into one patch per column: covering the unsplit 1300x1400 box hid half the artwork.
     * Set to false to restore the old erase path for A/B comparison.
     */
    private const val COVER_ORIGINAL = true

    private class Patch(
        val text: String,
        /** Area covered by the translation (and, with [COVER_ORIGINAL], by its background). */
        val area: Rect,
        /** Where the original lettering sat. The translation is anchored here, not centred in [area]. */
        val anchor: Rect,
        val background: Int,
        val vertical: Boolean,
        /** Font size the page was typeset at, measured from the source pixels. */
        val target: Float,
        /** Near-miss columns that were not translated but whose ink still has to go. */
        val extra: List<Rect> = emptyList(),
        /** Balloon outline in page pixels, when the segmentation model matched this group. */
        val contour: FloatArray? = null
    )

    /**
     * Draws [translated] over the original lettering, either by covering each patch with its
     * sampled background colour or, with [COVER_ORIGINAL] off, by erasing the ink pixels.
     *
     * [horizontal] forces ordinary left-to-right lines even when the source bubble was vertical.
     * Only the drawing direction changes: patch growth, covering and colour sampling all follow
     * the source geometry, so a page translated earlier renders correctly in either mode.
     */
    fun render(
        source: Bitmap,
        groups: List<BubbleGroup>,
        translated: List<String>,
        horizontal: Boolean = false
    ): Bitmap {
        // Latin words cannot be stacked in a vertical column, so English output always uses the
        // existing horizontal branch. The geometry (erase, patch, sampling) is untouched.
        val forceHorizontal = horizontal || target.latin
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val patches = ArrayList<Patch>()
        val seeds = groups.map { it.bubble }
        val placed = ArrayList<Rect>()
        groups.forEachIndexed { i, group ->
            // Cached translations predate this gate, so filter here too: a bad answer must not
            // be drawn just because it was saved by an older build.
            val value = sanitize(tidy(translated.getOrNull(i).orEmpty(), group.source), group.source)
            if (value.isEmpty()) return@forEachIndexed
            // Light lettering on dark artwork could not be *erased* without painting a bright
            // patch over the drawing. With a cover we sample that dark background instead and
            // write white text on it, so the block no longer has to be abandoned.
            if (!COVER_ORIGINAL && medianLuminance(source, group.bubble) < 100) {
                return@forEachIndexed
            }

            // The detector returns one tight box per Japanese column. A bubble is drawn around
            // all of its columns, but short and low-confidence columns are dropped, so using
            // those boxes as the patch left stray kana beside the translation and squeezed the
            // Chinese into a sliver (down to the 7 px floor, with the text truncated). Grow the
            // patch across the blank interior of the bubble, then clear all lettering in it.
            val unit = columnWidth(group)
            val clean = insideWhiteBubble(
                source, group.bubble,
                seeds.filterIndexed { j, _ -> j != i }
            )
            val area = if (clean) {
                bubbleSafeArea(
                    source, group.bubble, unit,
                    seeds.filterIndexed { j, _ -> j != i } + placed
                )
            } else {
                conservativeArea(source, group.bubble)
            }
            placed.add(area)
            // Sample from the patch itself: a halftone bubble needs its own grey as the fill,
            // otherwise erased lettering turns into bright white glyph-shaped holes.
            val background = sampleBubbleColor(out, area)
            val verticalText = if (forceHorizontal) false else group.vertical
            // The erasing area is the bubble, but the translation has to sit where the Japanese
            // sat and be about as large as the Japanese was. Both come from the source, not from
            // whatever room the bubble happens to offer.
            // Measure the lettering before anything is erased; out is still a copy of the page
            // at this point (patches are collected first, erased afterwards).
            val target = sourceGlyphSize(group, out, background)
            patches.add(
                Patch(
                    value, area, Rect(group.bubble), background, verticalText, target,
                    group.eraseExtra, group.contour
                )
            )
            android.util.Log.d(
                "Translate",
                "TR patch i=$i clean=$clean vertical=$verticalText " +
                    "seed=${group.bubble.width()}x${group.bubble.height()} " +
                    "area=${area.width()}x${area.height()} unit=$unit target=$target"
            )
        }
        // All backgrounds first, all text afterwards: a later patch overlapping an earlier one
        // must not wipe Chinese that was already drawn. A wide free-lettering block is split into
        // one column per group before reaching render(), so each patch here is a narrow strip or
        // one bubble interior — covering it whole hides the original lettering without painting
        // over unrelated artwork.
        if (COVER_ORIGINAL) {
            val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            patches.forEach { patch ->
                coverPaint.color = patch.background
                val contour = patch.contour
                if (contour != null && contour.size >= 6) {
                    // Follow the balloon outline instead of covering a rectangle: the shape is
                    // what keeps the cover off the artwork next to the bubble.
                    val path = Path()
                    path.moveTo(contour[0], contour[1])
                    var index = 2
                    while (index + 1 < contour.size) {
                        path.lineTo(contour[index], contour[index + 1])
                        index += 2
                    }
                    path.close()
                    canvas.drawPath(path, coverPaint)
                } else {
                    val rect = RectF(patch.area)
                    // Four percent of the short edge, not a fixed three pixels: the detector
                    // box stops at the glyph centres, so a fixed margin left the first and last
                    // column's outer strokes visible beside the cover on a long narration strip.
                    val pad = min(rect.width(), rect.height()) * 0.04f
                    rect.inset(-pad, -pad)
                    val radius = min(rect.width(), rect.height()) * 0.12f
                    canvas.drawRoundRect(rect, radius, radius, coverPaint)
                }
            }
        } else {
            patches.forEach { patch ->
                eraseLettering(out, patch.area, patch.background, patch.anchor)
                patch.extra.forEach { eraseColouredInk(out, it, patch.background) }
            }
        }
        patches.forEach { patch ->
            paint.color = if (luminance(patch.background) > 140) {
                Color.rgb(18, 18, 18)
            } else {
                Color.WHITE
            }
            drawFittedText(
                canvas, paint, patch.text, patch.anchor, patch.area, patch.vertical, patch.target
            )
        }
        return out
    }

    /** Typical width of one Japanese column, used as the yardstick for growing the patch. */
    private fun columnWidth(group: BubbleGroup): Int {
        val sizes = group.regions.map {
            if (group.vertical) it.box.width() else it.box.height()
        }.sorted()
        if (sizes.isEmpty()) return 24
        return sizes[sizes.size / 2].coerceIn(10, 96)
    }

    /**
     * Only a clean white bubble may be grown. Lettering printed directly on the artwork has no
     * outline to stop at, so growing it would paint a rectangle over the drawing.
     */
    private fun insideWhiteBubble(
        source: Bitmap,
        seed: Rect,
        ignore: List<Rect> = emptyList()
    ): Boolean {
        val pad = 10
        val l = (seed.left - pad).coerceAtLeast(0)
        val t = (seed.top - pad).coerceAtLeast(0)
        val r = (seed.right + pad).coerceAtMost(source.width)
        val b = (seed.bottom + pad).coerceAtMost(source.height)
        if (r - l < 2 || b - t < 2) return false
        val w = r - l
        val h = b - t
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, l, t, w, h)
        var dark = 0
        var total = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val gx = l + x
                val gy = t + y
                if (gx >= seed.left && gx < seed.right &&
                    gy >= seed.top && gy < seed.bottom
                ) continue
                // Lettering is what we are about to erase, and the columns of the same bubble
                // sit right next to each other; counting them here would make every half of a
                // split bubble look like text printed on the artwork.
                if (ignore.any { it.contains(gx, gy) }) continue
                total++
                if (luminance(pixels[y * w + x]) < 165) dark++
            }
        }
        return total > 0 && dark.toFloat() / total < 0.12f
    }

    /** Just enough room to clear the anti-aliased fringe when the text sits on artwork. */
    private fun conservativeArea(source: Bitmap, seed: Rect): Rect {
        val area = Rect(seed)
        area.inset(-2, -2)
        area.left = area.left.coerceAtLeast(0)
        area.top = area.top.coerceAtLeast(0)
        area.right = area.right.coerceAtMost(source.width)
        area.bottom = area.bottom.coerceAtMost(source.height)
        return area
    }

    /**
     * Expands the OCR footprint into the blank interior of its bubble. Each step samples the
     * strip just outside the rectangle and stops at a continuous dark line (the bubble outline
     * or an artwork stroke); scattered ink is lettering, which we cross and erase. Growth is
     * capped so a patch can never swallow a neighbouring panel.
     */
    private fun bubbleSafeArea(
        source: Bitmap,
        seed: Rect,
        unit: Int,
        obstacles: List<Rect>
    ): Rect {
        var left = seed.left
        var right = seed.right
        var top = seed.top
        var bottom = seed.bottom
        val step = 2
        val maxX = (unit * 1.8f).toInt().coerceIn(12, source.width / 5)
        val maxY = (unit * 0.5f).toInt().coerceIn(6, source.height / 12)
        var grown = 0
        while (grown + step <= maxX && left - step >= 0 &&
            blankStrip(source, left - step, left, top, bottom, true) &&
            !touchesObstacle(left - step, top, right, bottom, obstacles)
        ) { left -= step; grown += step }
        grown = 0
        while (grown + step <= maxX && right + step <= source.width &&
            blankStrip(source, right, right + step, top, bottom, true) &&
            !touchesObstacle(left, top, right + step, bottom, obstacles)
        ) { right += step; grown += step }
        grown = 0
        while (grown + step <= maxY && top - step >= 0 &&
            blankStrip(source, left, right, top - step, top, false) &&
            !touchesObstacle(left, top - step, right, bottom, obstacles)
        ) { top -= step; grown += step }
        grown = 0
        while (grown + step <= maxY && bottom + step <= source.height &&
            blankStrip(source, left, right, bottom, bottom + step, false) &&
            !touchesObstacle(left, top, right, bottom + step, obstacles)
        ) { bottom += step; grown += step }

        // Keep clear of the outline; never return less than what OCR actually found.
        val area = Rect(left + 2, top + 2, right - 2, bottom - 2)
        if (area.width() < seed.width() || area.height() < seed.height()) return Rect(seed)
        area.left = area.left.coerceAtLeast(0)
        area.top = area.top.coerceAtLeast(0)
        area.right = area.right.coerceAtMost(source.width)
        area.bottom = area.bottom.coerceAtMost(source.height)
        return area
    }

    /** A patch must never grow into a neighbouring bubble's lettering. */
    private fun touchesObstacle(
        l: Int, t: Int, r: Int, b: Int, obstacles: List<Rect>
    ): Boolean = obstacles.any {
        it.left < r + 3 && it.right > l - 3 && it.top < b + 3 && it.bottom > t - 3
    }

    /**
     * A bubble outline (or an artwork stroke) is one long continuous dark run, while letters
     * are scattered. Only scattered ink may be crossed.
     */
    private fun blankStrip(
        source: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int, alongY: Boolean
    ): Boolean {
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return false
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, x0, y0, w, h)
        val length = if (alongY) h else w
        val thickness = if (alongY) w else h
        var longest = 0
        var run = 0
        for (i in 0 until length) {
            var dark = false
            for (j in 0 until thickness) {
                val index = if (alongY) i * w + j else j * w + i
                if (luminance(pixels[index]) < 165) { dark = true; break }
            }
            if (dark) {
                run++
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }
        return longest.toFloat() / length < 0.5f
    }

    private fun medianLuminance(source: Bitmap, box: Rect): Int {
        val right = box.right.coerceAtMost(source.width)
        val bottom = box.bottom.coerceAtMost(source.height)
        if (right <= box.left || bottom <= box.top) return 255
        val stepX = ((right - box.left) / 12).coerceAtLeast(1)
        val stepY = ((bottom - box.top) / 12).coerceAtLeast(1)
        val samples = ArrayList<Int>(256)
        var y = box.top
        while (y < bottom) {
            var x = box.left
            while (x < right) {
                samples.add(luminance(source.getPixel(x, y)))
                x += stepX
            }
            y += stepY
        }
        if (samples.isEmpty()) return 255
        samples.sort()
        return samples[samples.size / 2]
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    private fun sampleBubbleColor(source: Bitmap, box: Rect): Int {
        val w = box.width().coerceAtLeast(1)
        val h = box.height().coerceAtLeast(1)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val bright = ArrayList<Int>(pixels.size / 4)
        for (pixel in pixels) {
            if (luminance(pixel) > 120) bright.add(pixel)
        }
        val pool = if (bright.size >= pixels.size / 6) bright else pixels.toList()
        val sorted = pool.sortedBy { luminance(it) }
        val picked = sorted[sorted.size / 2]
        return Color.rgb(Color.red(picked), Color.green(picked), Color.blue(picked))
    }

    /**
     * Clears every ink pixel inside the patch (not only the columns the detector found) plus
     * its dark fringe. Screen tones and light grey artwork must survive: erasing them produced
     * a conspicuous white rectangle inside halftone bubbles.
     */
    private fun eraseLettering(bitmap: Bitmap, box: Rect, background: Int, anchor: Rect? = null) {
        val w = box.width()
        val h = box.height()
        if (w < 2 || h < 2) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val backgroundLum = luminance(background)
        if (backgroundLum < 90) return
        // Halftone dots sit around luminance 200. Only genuinely black lettering and its dark
        // fringe may be cleared, otherwise the whole patch turns into a white rectangle.
        val coreThreshold = (backgroundLum * 0.46f).toInt()
        val haloThreshold = min(170, backgroundLum - 60)
        val core = BooleanArray(w * h)
        for (i in pixels.indices) {
            if (luminance(pixels[i]) < coreThreshold) core[i] = true
        }
        // Coloured dialogue (green shouts, blue narration, purple captions) is not dark enough
        // for a luminance rule. It is strongly saturated and much darker than the pale paper it
        // sits on, while skin, highlights and screen tones are either brighter or nearly grey.
        // Restrict this to the detector's own text footprint: outside it, the same saturated
        // colour may be a bubble outline or a piece of artwork that must survive.
        val colour = BooleanArray(w * h)
        if (anchor != null) {
            for (y in 0 until h) {
                val gy = box.top + y
                if (gy < anchor.top || gy >= anchor.bottom) continue
                for (x in 0 until w) {
                    val gx = box.left + x
                    if (gx < anchor.left || gx >= anchor.right) continue
                    val index = y * w + x
                    val p = pixels[index]
                    val r = Color.red(p)
                    val g = Color.green(p)
                    val b = Color.blue(p)
                    val saturation = maxOf(r, g, b) - minOf(r, g, b)
                    val lum = luminance(p)
                    if (saturation >= 55 && lum < backgroundLum * 0.72f && lum < 175) {
                        colour[index] = true
                    }
                }
            }
        }
        val reach = 1
        var changed = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                if (!colour[index] && luminance(pixels[index]) >= haloThreshold) continue
                var nearInk = core[index] || colour[index]
                if (!nearInk) {
                    for (dy in -reach..reach) {
                        if (nearInk) break
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -reach..reach) {
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue
                            val k = ny * w + nx
                            if (core[k] || colour[k]) { nearInk = true; break }
                        }
                    }
                }
                if (nearInk) {
                    pixels[index] = background
                    changed = true
                }
            }
        }
        if (changed) bitmap.setPixels(pixels, 0, w, box.left, box.top, w, h)
    }

    /**
     * Clears a rejected low-confidence column of coloured lettering. It never reaches the
     * translator, so it is not part of any patch, but leaving it on the page prints the Chinese
     * straight over the Japanese. Only saturated ink on a pale rectangle is replaced; a dark or
     * coloured piece of artwork fails the luminance gate and is left alone.
     */
    private fun eraseColouredInk(bitmap: Bitmap, rect: Rect, background: Int) {
        val box = Rect(rect)
        if (!box.intersect(0, 0, bitmap.width, bitmap.height)) return
        val w = box.width()
        val h = box.height()
        if (w < 6 || h < 6) return
        if (medianLuminance(bitmap, box) < 150) return
        val bgLum = luminance(background)
        if (bgLum < 90) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val core = BooleanArray(w * h)
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val index = y * w + x
                val p = pixels[index]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val saturation = maxOf(r, g, b) - minOf(r, g, b)
                val lum = luminance(p)
                if (saturation >= 55 && lum < bgLum * 0.72f && lum < 175) {
                    core[index] = true
                }
            }
        }
        // The anti-aliased edge of a coloured glyph is lighter and less saturated than its
        // centre. Clearing only the core left bright-green square outlines where the Japanese
        // had been, so take the one-pixel fringe with it.
        var changed = false
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val index = y * w + x
                var nearInk = core[index]
                if (!nearInk) {
                    for (dy in -1..1) {
                        if (nearInk) break
                        for (dx in -1..1) {
                            if (core[(y + dy) * w + x + dx]) { nearInk = true; break }
                        }
                    }
                }
                if (nearInk && luminance(pixels[index]) < 235) {
                    pixels[index] = background
                    changed = true
                }
            }
        }
        if (changed) bitmap.setPixels(pixels, 0, w, box.left, box.top, w, h)
    }

    private class Fit(
        val columns: List<String>,
        val lines: List<String>,
        val size: Float
    )

    /**
     * Draws [text] at the size the page was typeset at, centred on the spot the original
     * lettering occupied.
     *
     * [anchor] is the footprint of the original text, [bounds] the erased bubble interior. The
     * drawing box starts at [anchor] and grows symmetrically inside [bounds] only as far as the
     * translation needs, so the block stays centred where the Japanese was instead of drifting to
     * the middle of the bubble. The font shrinks only when the whole bubble is too small.
     */
    private fun drawFittedText(
        canvas: Canvas,
        paint: Paint,
        text: String,
        anchor: Rect,
        bounds: Rect,
        vertical: Boolean,
        target: Float
    ) {
        if (bounds.width() < 10 || bounds.height() < 10) return
        // A little slack, so a descender or a heavy last stroke is not clipped by the box edge.
        val margin = (target * 0.22f).toInt().coerceAtLeast(2)
        val box = Rect(anchor)
        box.inset(-margin, -margin)
        if (!box.intersect(bounds)) box.set(anchor)

        val sizeCap = target.coerceIn(7f, MAX_GLYPH)
        var fit = layoutIn(text, paint, box, sizeCap, vertical)
        // Borrow empty bubble before shrinking the text: a long line in a big bubble should use
        // the room that is there, not turn into tiny text floating in the middle of it. Growth is
        // stepped a quarter of a glyph at a time so the block stays tight around where the
        // original lettering was instead of being spread across the whole bubble.
        while (fit == null && growWithin(box, bounds, sizeCap * 0.25f)) {
            fit = layoutIn(text, paint, box, sizeCap, vertical)
        }
        var size = sizeCap
        while (fit == null && size > 7f) {
            size -= 1f
            fit = layoutIn(text, paint, box, size, vertical)
        }
        if (fit == null) {
            // nothing fits even at the floor: shorten instead of drawing outside the bubble
            var keep = text.length
            while (keep > 1) {
                keep -= 1
                fit = layoutIn(text.take(keep) + "…", paint, box, 7f, vertical)
                if (fit != null) break
            }
        }
        val chosen = fit ?: return
        paint.textSize = chosen.size
        android.util.Log.d(
            "Translate",
            "TR fit size=${chosen.size} target=$target box=${box.width()}x${box.height()} " +
                "anchor=${anchor.width()}x${anchor.height()} " +
                "chars=${chosen.columns.sumOf { it.length } + chosen.lines.sumOf { it.length }}"
        )

        canvas.save()
        canvas.clipRect(box)
        val metrics = paint.fontMetrics
        val glyphH = metrics.descent - metrics.ascent
        if (vertical) {
            val colW = chosen.size * 1.10f
            val lineH = chosen.size * 1.04f
            val totalW = chosen.columns.size * colW
            var x = box.left + (box.width() - totalW) / 2f + totalW - colW
            for (column in chosen.columns) {
                val blockH = column.length * lineH
                var y = box.top + (box.height() - blockH) / 2f
                for (ch in column) {
                    val gx = x + (colW - chosen.size) / 2f
                    val gy = y + (lineH - glyphH) / 2f - metrics.ascent
                    canvas.drawText(ch.toString(), gx, gy, paint)
                    y += lineH
                }
                x -= colW
            }
        } else {
            val lineH = chosen.size * 1.18f
            val blockH = chosen.lines.size * lineH
            var y = box.top + (box.height() - blockH) / 2f
            for (line in chosen.lines) {
                val width = paint.measureText(line)
                val gx = box.left + (box.width() - width) / 2f
                val gy = y + (lineH - glyphH) / 2f - metrics.ascent
                canvas.drawText(line, gx, gy, paint)
                y += lineH
            }
        }
        canvas.restore()
    }

    /** Lays out inside [box] after the same small padding the drawing uses. */
    private fun layoutIn(
        text: String,
        paint: Paint,
        box: Rect,
        size: Float,
        vertical: Boolean
    ): Fit? {
        val pad = maxOf(1.5f, minOf(box.width(), box.height()) * 0.035f)
        val availW = box.width() - 2f * pad
        val availH = box.height() - 2f * pad
        if (availW < 8f || availH < 8f) return null
        return layout(text, paint, size, availW, availH, vertical)
    }

    /** Grows [box] evenly towards [bounds]; false once it already fills them. */
    private fun growWithin(box: Rect, bounds: Rect, step: Float): Boolean {
        val s = step.coerceAtLeast(3f).toInt()
        var changed = false
        if (box.left > bounds.left) {
            box.left = maxOf(bounds.left, box.left - s)
            changed = true
        }
        if (box.right < bounds.right) {
            box.right = minOf(bounds.right, box.right + s)
            changed = true
        }
        if (box.top > bounds.top) {
            box.top = maxOf(bounds.top, box.top - s)
            changed = true
        }
        if (box.bottom < bounds.bottom) {
            box.bottom = minOf(bounds.bottom, box.bottom + s)
            changed = true
        }
        return changed
    }

    /** Ceiling for a glyph size; large enough for shout lettering, small enough to stay sane. */
    private const val MAX_GLYPH = 120f

    /** The drawing uses size * 1.04 as its vertical character advance. */
    private const val VERTICAL_ADVANCE = 1.04f

    /**
     * Distance between the centres of consecutive characters, read straight off the page ink.
     *
     * This is deliberately independent of the OCR boxes and of the character count. Both go
     * wrong on loosely typeset or coloured lettering: the detector returns a narrow box, OCR
     * reads a different number of glyphs, and height / count then understates the type size by
     * a third. A column has a strong vertical rhythm, so the *spacing between glyph blocks*
     * survives all of that. Only the y projection is needed, which also makes it cheap.
     *
     * Pixels are compared with the sampled bubble colour rather than a brightness cut, so
     * coloured lettering (the purple dialogue in the CG books) is ink just like black text.
     */
    private fun inkCharacterPitch(
        group: BubbleGroup,
        source: Bitmap,
        background: Int,
        unit: Float
    ): Float? {
        if (!group.vertical) return null
        val bgR = Color.red(background)
        val bgG = Color.green(background)
        val bgB = Color.blue(background)
        val bgLum = luminance(background)
        val gaps = ArrayList<Float>()
        for (region in group.regions) {
            if (!region.vertical) continue
            val l = (region.box.left - 2).coerceAtLeast(0)
            val r = (region.box.right + 2).coerceAtMost(source.width)
            val t = region.box.top.coerceAtLeast(0)
            val b = region.box.bottom.coerceAtMost(source.height)
            val w = r - l
            val h = b - t
            if (w < 6 || h < 24) continue
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, l, t, w, h)
            // A row counts as ink when a meaningful part of the column width is covered.
            val minInk = maxOf(1, w / 10)
            val ink = BooleanArray(h)
            for (y in 0 until h) {
                var count = 0
                val row = y * w
                for (x in 0 until w) {
                    val p = pixels[row + x]
                    val diff = abs(Color.red(p) - bgR) +
                        abs(Color.green(p) - bgG) + abs(Color.blue(p) - bgB)
                    if (luminance(p) < bgLum * 0.72f || diff > 90) count++
                }
                ink[y] = count >= minInk
            }
            // Join the strokes of one glyph (a vertical morphological closing), otherwise a
            // thin kana splits into two runs. A real gap between glyphs is wider than this.
            val join = maxOf(2, (w * 0.10f).toInt())
            var blank = 0
            var fillFrom = -1
            for (y in 0 until h) {
                if (ink[y]) {
                    if (blank in 1..join && fillFrom >= 0) {
                        for (k in fillFrom until y) ink[k] = true
                    }
                    blank = 0
                    fillFrom = -1
                } else {
                    if (blank == 0) fillFrom = y
                    blank++
                }
            }
            val centres = ArrayList<Float>()
            val minGlyph = maxOf(6, (w * 0.30f).toInt())
            // Screen tones, panel borders and speed lines merge into very tall ink blocks.
            // They are not glyphs, and letting them in is what blew the type size up on
            // black-and-white pages.
            val maxGlyph = (unit * 2.2f).toInt().coerceAtLeast(minGlyph + 1)
            var start = -1
            for (y in 0..h) {
                val on = y < h && ink[y]
                if (on && start < 0) start = y
                else if (!on && start >= 0) {
                    val height = y - start
                    if (height >= minGlyph && height <= maxGlyph) centres.add((start + y) / 2f)
                    start = -1
                }
            }
            for (i in 1 until centres.size) {
                val gap = centres[i] - centres[i - 1]
                if (gap >= unit * 0.5f && gap <= unit * 2.6f) gaps.add(gap)
            }
        }
        if (gaps.size < 2) return null
        gaps.sort()
        val median = gaps[gaps.size / 2]
        // One missed glyph doubles a single gap; keep the values that agree with the rhythm.
        val near = gaps.filter { it >= median * 0.75f && it <= median * 1.35f }
        return if (near.isEmpty()) median else (near.sum() / near.size)
    }

    /**
     * Font size the original lettering was set in.
     *
     * The old estimate (column height / OCR character count, clamped to 1.05 × column width)
     * is right on tightly set black-and-white pages and is left completely alone there. It
     * under-measures loose, coloured lettering by about a third, because the detector box is
     * narrower than the glyphs. Ink pitch fixes that case, but only when it plausibly says the
     * old estimate was *too small*: a black-and-white page full of screen tones produces bogus
     * large ink blocks, and trusting them made the translation cover the artwork. Anything
     * outside that narrow window falls back to the old behaviour.
     */
    private fun sourceGlyphSize(group: BubbleGroup, source: Bitmap, background: Int): Float {
        val unit = columnWidth(group).toFloat()
        val estimates = ArrayList<Float>()
        for (region in group.regions) {
            val chars = region.text.count { !it.isWhitespace() }
            if (chars < 1) continue
            estimates.add(
                if (region.vertical) region.box.height().toFloat() / chars
                else region.box.height().toFloat()
            )
        }
        estimates.sort()
        val geometric = if (estimates.isEmpty()) unit else estimates[estimates.size / 2]
        val ink = inkCharacterPitch(group, source, background, unit)
        // Only step in when the ink says the geometric estimate is too small by a meaningful
        // amount, and never by more than roughly double. Everything else keeps the older,
        // well-tested size — including any page where the ink measurement is noise.
        val plausible = ink != null &&
            ink >= geometric * 1.12f &&
            ink <= geometric * 1.9f &&
            ink >= unit * 0.6f &&
            ink <= unit * 2.2f
        // Round 41 only handled "the ink says the geometric estimate is too small". Pages with
        // big, loosely set coloured lettering hit the other failure: the estimate is right, but
        // the 1.05 x column-width ceiling then throws a third of it away, and the ink agrees
        // with the estimate so the round-41 gate says there is nothing to fix. When both
        // independent measurements agree, the ceiling is the thing that is wrong. On a halftone
        // page the ink reading is far larger than the geometric one, so this branch cannot fire
        // there and the old size stays intact.
        val ceiling = unit * 1.05f
        val clipped = geometric > ceiling
        val corroborated = ink != null &&
            ink >= geometric * 0.75f &&
            ink <= geometric * 1.35f &&
            ink >= unit * 0.6f &&
            ink <= unit * 2.2f
        val target = when {
            plausible -> ink!! / VERTICAL_ADVANCE
            clipped && corroborated ->
                min(geometric, ink!! / VERTICAL_ADVANCE).coerceIn(unit * 0.6f, unit * 2.2f)
            else -> geometric.coerceIn(unit * 0.6f, ceiling)
        }
        android.util.Log.d(
            "Translate",
            "TR glyph unit=$unit geo=$geometric ink=${ink ?: 0f} " +
                "plausible=$plausible clipped=$clipped corroborated=$corroborated target=$target"
        )
        return target.coerceIn(7f, MAX_GLYPH)
    }

    private fun layout(
        text: String,
        paint: Paint,
        size: Float,
        availW: Float,
        availH: Float,
        vertical: Boolean
    ): Fit? {
        if (text.isEmpty()) return null
        if (vertical) {
            val lineH = size * 1.04f
            val colW = size * 1.10f
            val per = floor(availH / lineH).toInt()
            if (per < 1) return null
            val columns = chunk(text, per)
            if (columns.size * colW > availW + 0.5f) return null
            val longest = columns.maxOf { it.length }
            if (longest * lineH > availH + 0.5f) return null
            return Fit(columns, emptyList(), size)
        }
        paint.textSize = size
        val lineH = size * 1.18f
        // At the smallest font a hard character split is the only way to draw anything; at
        // every larger size a word wider than the bubble means "shrink the font", not "cut
        // the word in half". CJK output never reaches wrapWords, so this only touches Latin.
        val hard = size <= 7f
        val lines = wrap(text, paint, availW, hard)
        if (lines.size * lineH > availH + 0.5f) return null
        if (!hard && lines.any { paint.measureText(it) > availW + 0.5f }) return null
        return Fit(emptyList(), lines, size)
    }

    private val PUNCTUATION = "，。！？、；：,.!?;:…‥「」『』（）()[]【】〈〉《》—―-~～·・"

    private fun isJapaneseLetter(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x3041..0x309F) || (code in 0x30A0..0x30FF) || (code in 0x4E00..0x9FFF)
    }

    private fun isPunctuation(ch: Char): Boolean = ch in PUNCTUATION

    private fun chunk(text: String, per: Int): List<String> {
        val columns = ArrayList<String>(4)
        var i = 0
        while (i < text.length) {
            val end = min(i + per, text.length)
            columns.add(text.substring(i, end))
            i = end
        }
        if (columns.size > 1) {
            val last = columns.last()
            if (last.length == 1 && isPunctuation(last[0])) {
                columns[columns.size - 2] = columns[columns.size - 2] + last
                columns.removeAt(columns.size - 1)
            }
        }
        return columns
    }

    private fun wrap(
        text: String,
        paint: Paint,
        maxWidth: Float,
        hard: Boolean = false
    ): List<String> {
        if (target.latin) return wrapWords(text, paint, maxWidth, hard)
        val lines = ArrayList<String>(4)
        val current = StringBuilder()
        for (ch in text) {
            val exceeds = current.isNotEmpty() &&
                paint.measureText(current.toString() + ch) > maxWidth
            if (exceeds && !isPunctuation(ch)) {
                lines.add(current.toString())
                current.setLength(0)
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    /**
     * Word-aware wrapping for Latin output. Character wrapping cut "weather" into "wea / ther";
     * a single token that is still wider than the bubble at this font size is broken anyway so
     * the layout loop always has something it can fit.
     */
    private fun wrapWords(
        text: String,
        paint: Paint,
        maxWidth: Float,
        hard: Boolean
    ): List<String> {
        val lines = ArrayList<String>(4)
        var current = ""
        for (word in text.split(WHITESPACE).filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                lines.add(current)
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        // layout() retries with a smaller font when a whole word does not fit. Only at the
        // 7 px floor do we split a token by character, so a normal page never reads "kno / w".
        if (!hard) return lines
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (paint.measureText(line) <= maxWidth) {
                out.add(line)
                continue
            }
            var piece = ""
            for (ch in line) {
                if (piece.isNotEmpty() && paint.measureText(piece + ch) > maxWidth) {
                    out.add(piece)
                    piece = ""
                }
                piece += ch
            }
            if (piece.isNotEmpty()) out.add(piece)
        }
        return out
    }
}

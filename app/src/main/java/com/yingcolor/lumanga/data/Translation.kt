package com.yingcolor.lumanga.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
    val vertical: Boolean
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

    /** Callers keep using the old names; the getter picks the wording for the current pair. */
    private val SYSTEM_PROMPT: String
        get() = if (source == SourceLanguage.JAPANESE && target == TargetLanguage.CHINESE)
            SYSTEM_PROMPT_JA_ZH else buildSystemPrompt()

    private val GROUP_PROMPT: String
        get() = if (source == SourceLanguage.JAPANESE && target == TargetLanguage.CHINESE)
            GROUP_PROMPT_JA_ZH else buildGroupPrompt()

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
        get() = if (source == SourceLanguage.DEFAULT && target == TargetLanguage.DEFAULT) ""
        else "s${source.id}t${target.id}"

    private fun buildSystemPrompt(): String = buildString {
        val order = if (source.horizontal)
            "横排气泡里用空格分开的就是一行，读法是从左到右、从上到下。"
        else
            "竖排气泡里用空格分开的就是一列，读法是列从右到左、列内从上到下。"
        val reorder = if (source.horizontal) "从左到右、从上到下" else "从右到左、从上到下"
        append("你是漫画翻译助手。输入按【编号】分组，每组是一个对话气泡里 OCR 出来的")
            .append(source.promptName).append("片段。")
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

    private fun buildGroupPrompt(): String = buildString {
        val order = if (source.horizontal)
            "横排里用空格分开的就是一行，读法是从左到右、从上到下。"
        else
            "竖排里用空格分开的就是一列，读法是列从右到左、列内从上到下。"
        val reorder = if (source.horizontal) "从左到右、从上到下" else "从右到左、从上到下"
        append("你是漫画翻译助手。用户每次只给你一个对话气泡里的").append(source.promptName)
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

    suspend fun recognize(bitmap: Bitmap): List<TextRegion> = ocr.recognize(bitmap)

    /** A character that counts as real lettering in the language printed on the page. */
    private fun isSourceLetter(ch: Char): Boolean = when (source) {
        SourceLanguage.JAPANESE -> isJapaneseLetter(ch)
        SourceLanguage.CHINESE -> ch.code in 0x3400..0x9FFF
        SourceLanguage.ENGLISH ->
            (ch in 'A'..'Z') || (ch in 'a'..'z') || ch.code in 0x00C0..0x024F
    }

    /** Drops detection noise, sound effects and single stray glyphs before translating. */
    fun isUsable(region: TextRegion): Boolean {
        // 0.55 used to throw away real but faint lettering: a perfectly readable 「よし」 came
        // back at 0.51 and vanished from its bubble, cutting the sentence short.
        if (region.confidence < 0.45f) return false
        if (region.box.width() < 10 || region.box.height() < 10) return false
        if (region.vertical && region.box.height() < region.box.width() * 1.2f) return false
        var letters = 0
        for (ch in region.text) {
            if (isSourceLetter(ch)) letters++
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
    fun looksJapanese(regions: List<TextRegion>): Boolean {
        if (source != SourceLanguage.JAPANESE) {
            return regions.sumOf { r -> r.text.count { isSourceLetter(it) } } >= 2
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
    private fun regionOrder(): Comparator<TextRegion> =
        if (source.horizontal)
            compareBy<TextRegion> { it.box.top }.thenBy { it.box.centerX() }
        else
            compareByDescending<TextRegion> { it.box.centerX() }.thenBy { it.box.top }

    private fun groupOrder(): Comparator<BubbleGroup> =
        if (source.horizontal)
            compareBy<BubbleGroup> { it.bubble.top }.thenBy { it.bubble.centerX() }
        else
            compareByDescending<BubbleGroup> { it.bubble.centerX() }.thenBy { it.bubble.top }

    fun groupBubbles(
        regions: List<TextRegion>,
        width: Int,
        height: Int,
        bitmap: Bitmap? = null
    ): List<BubbleGroup> {
        val usable = regions.filter { isUsable(it) }
        if (usable.isEmpty()) return emptyList()
        val ordered = usable.sortedWith(regionOrder())
        val clusters = ArrayList<MutableList<TextRegion>>()
        for (region in ordered) {
            var target: MutableList<TextRegion>? = null
            var bestGap = Float.MAX_VALUE
            for (cluster in clusters) {
                for (member in cluster) {
                    // columns of one bubble sit side by side and share the same vertical span;
                    // they often overlap slightly, so a negative gap counts as touching
                    val gap = horizontalGap(region.box, member.box)
                    val verticalOverlap = overlap(region.box, member.box)
                    if (verticalOverlap < 0.45f) continue
                    val unit = min(min(region.box.width(), member.box.width()).toFloat(), 96f)
                        .coerceAtLeast(12f)
                    if (gap < unit * 0.75f && gap < bestGap) {
                        target = cluster
                        bestGap = gap
                    }
                }
            }
            if (target != null) target.add(region) else clusters.add(mutableListOf(region))
        }
        // When the detector drops a column between two others, the surviving columns end up too
        // far apart for the pass above and are translated as two bubbles. Both translations then
        // fill the same bubble and overwrite each other. Joining them here keeps one sentence per
        // bubble; the paper between them tells whether that is really one bubble.
        val groups = if (bitmap == null) {
            clusters
        } else {
            val merged = mergeNeighbours(clusters, bitmap)
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
        // because a short but real line like 「よし」 must not disappear.
        val kept = withMarks.filter { cluster ->
            cluster.sumOf { region -> region.text.count { isSourceLetter(it) } } >= 2 ||
                // A column of nothing but 「…」 still has to be kept: it covers lettering on the
                // page, and skipping it leaves the original dots showing between translated lines.
                cluster.all { isEllipsisOnly(it) }
        }
        val horizontalSource = source.horizontal
        val japaneseSource = source == SourceLanguage.JAPANESE
        return kept.map { cluster ->
            cluster.sortWith(regionOrder())
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
            val source = if (japaneseSource)
                MangaOcr.readIfSelected(bitmap, union) ?: columns
            else columns
            BubbleGroup(cluster, union, source, vertical)
        }.sortedWith(groupOrder())
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

    /** True for a detected column that holds only 「…」 and carries no letters of its own. */
    private fun isEllipsisOnly(region: TextRegion): Boolean {
        val stripped = region.text.filter { it !in PUNCTUATION && it != '.' && !it.isWhitespace() }
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
        source: Bitmap
    ): List<MutableList<TextRegion>> {
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

    suspend fun translate(
        groups: List<BubbleGroup>,
        api: String,
        model: String,
        context: List<String> = emptyList(),
        apiKey: String = "",
        protocol: ApiProtocol = ApiProtocol.DEFAULT
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
            when (val result = ApiClient.chat(protocol, api, model, apiKey, SYSTEM_PROMPT, numbered)) {
                is ApiClient.ChatResult.Ok -> return@withContext parse(result.text, groups)
                is ApiClient.ChatResult.Fail -> {
                    lastError = result.reason
                    android.util.Log.w("Translate", "TR fail ${result.reason}")
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
                launch {
                    gate.withPermit {
                        val text = askGroup(protocol, api, model, apiKey, hint, group.source, index)
                            ?: return@withPermit
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
     * One request for one group, retried once when the socket drops.
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
        index: Int
    ): String? {
        val user = buildString {
            if (hint.isNotEmpty()) {
                append("【前文，仅供人名、称呼和语气参考】\n").append(hint).append("\n\n")
            }
            append("【要翻译的原文】\n").append(source)
        }
        var lastError = "unknown"
        for (attempt in 0 until 2) {
            when (val result = ApiClient.chat(protocol, api, model, apiKey, GROUP_PROMPT, user)) {
                is ApiClient.ChatResult.Ok -> {
                    val value = tidyGroup(result.text, source)
                    if (value.isNotEmpty()) return value
                    break
                }
                is ApiClient.ChatResult.Fail -> {
                    lastError = result.reason
                    android.util.Log.w("Translate", "TR group $index fail ${result.reason}")
                    if (!result.retryable) break
                    delay(800)
                }
            }
        }
        android.util.Log.w("Translate", "TR group $index empty ($lastError)")
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
        val cleaned = tidy(body, source)
        // An echoed source is worse than no answer. For a Latin target the answer legitimately
        // has spaces, so both sides are compared with whitespace removed.
        val echoed = if (target.keepSpaces)
            source.replace(COLUMN_MARKS, "").replace(WHITESPACE, "")
        else source.replace(WHITESPACE, "")
        if (cleaned.isNotEmpty() &&
            cleaned.replace(WHITESPACE, "") == echoed.replace(WHITESPACE, "")
        ) return ""
        return cleaned
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

    private fun parse(content: String, groups: List<BubbleGroup>): List<String> {
        val count = groups.size
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val byIndex = HashMap<Int, String>()
        val numbered = Regex("^(\\d+)[.、)]\\s*(.+)$")
        lines.forEach { line ->
            val match = numbered.find(line)
            if (match == null) {
                // A long translation is sometimes wrapped onto a second line. Keep it as the
                // continuation of the entry above instead of dropping that half of the sentence.
                val last = byIndex.keys.maxOrNull() ?: return@forEach
                byIndex[last] = byIndex[last] + line
                return@forEach
            }
            val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return@forEach
            byIndex[index] = match.groupValues[2].trim()
        }
        val sourceOf = { i: Int -> groups.getOrNull(i)?.source.orEmpty() }
        if (byIndex.isNotEmpty()) {
            return List(count) { i -> tidy(byIndex[i].orEmpty(), sourceOf(i)) }
        }
        return List(count) { i -> tidy(lines.getOrNull(i).orEmpty(), sourceOf(i)) }
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

    private class Patch(
        val text: String,
        val area: Rect,
        val background: Int,
        val vertical: Boolean,
        val maxGlyph: Float
    )

    /**
     * Draws [translated] over the erased bubble areas.
     *
     * [horizontal] forces ordinary left-to-right lines even when the source bubble was vertical.
     * Only the drawing direction changes: patch growth, erasing and sampling all follow the
     * source geometry, so a page translated earlier renders correctly in either mode.
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
            val value = tidy(translated.getOrNull(i).orEmpty(), group.source)
            if (value.isEmpty()) return@forEachIndexed
            // light lettering on dark artwork (titles, black bubbles) cannot be erased without
            // painting a bright patch over the drawing, so it is left alone
            if (medianLuminance(source, group.bubble) < 100) return@forEachIndexed

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
            patches.add(Patch(value, area, background, verticalText, unit * 1.45f))
            android.util.Log.d(
                "Translate",
                "TR patch i=$i clean=$clean vertical=$verticalText " +
                    "seed=${group.bubble.width()}x${group.bubble.height()} " +
                    "area=${area.width()}x${area.height()} unit=$unit"
            )
        }
        // Erase every patch before drawing any text: a later patch overlapping an earlier one
        // must not wipe the already-drawn Chinese off the page.
        patches.forEach { eraseLettering(out, it.area, it.background) }
        patches.forEach { patch ->
            paint.color = if (luminance(patch.background) > 140) {
                Color.rgb(18, 18, 18)
            } else {
                Color.WHITE
            }
            drawFittedText(
                canvas, paint, patch.text, patch.area, patch.vertical, patch.maxGlyph
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
    private fun eraseLettering(bitmap: Bitmap, box: Rect, background: Int) {
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
        val reach = 1
        var changed = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                if (luminance(pixels[index]) >= haloThreshold) continue
                var nearInk = core[index]
                if (!nearInk) {
                    for (dy in -reach..reach) {
                        if (nearInk) break
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -reach..reach) {
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue
                            if (core[ny * w + nx]) { nearInk = true; break }
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

    private class Fit(
        val columns: List<String>,
        val lines: List<String>,
        val size: Float
    )

    private fun drawFittedText(
        canvas: Canvas,
        paint: Paint,
        text: String,
        box: Rect,
        vertical: Boolean,
        maxGlyph: Float
    ) {
        if (box.width() < 10 || box.height() < 10) return
        val pad = maxOf(1.5f, minOf(box.width(), box.height()) * 0.035f)
        val availW = box.width() - 2f * pad
        val availH = box.height() - 2f * pad
        if (availW < 8f || availH < 8f) return

        var fit: Fit? = null
        // never blow the lettering up beyond what the original text occupied
        var size = min(min(min(availW, availH) * 1.15f, 96f), maxGlyph.coerceAtLeast(9f))
        while (size >= 7f) {
            fit = layout(text, paint, size, availW, availH, vertical)
            if (fit != null) break
            size -= 1f
        }
        if (fit == null) {
            // nothing fits even at the floor: shorten instead of drawing outside the bubble
            var keep = text.length
            while (keep > 1) {
                keep -= 1
                fit = layout(text.take(keep) + "…", paint, 7f, availW, availH, vertical)
                if (fit != null) break
            }
        }
        val chosen = fit ?: return
        paint.textSize = chosen.size
        android.util.Log.d(
            "Translate",
            "TR fit size=${chosen.size} box=${box.width()}x${box.height()} " +
                "chars=${chosen.columns.sumOf { it.length } + chosen.lines.sumOf { it.length }}"
        )

        canvas.save()
        canvas.clipRect(box)
        val metrics = paint.fontMetrics
        val glyphH = metrics.descent - metrics.ascent
        if (vertical) {
            val colW = chosen.size * 1.10f
            val lineH = chosen.size * 1.12f
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
            val lineH = size * 1.12f
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

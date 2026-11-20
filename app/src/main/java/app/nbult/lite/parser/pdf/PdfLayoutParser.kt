package app.nbult.lite.parser.pdf

import app.nbult.lite.parser.FieldExtractor
import app.nbult.lite.parser.ParseSource
import app.nbult.lite.parser.ParsedSlot
import app.nbult.lite.parser.ParseResult
import kotlin.math.abs
import kotlin.math.floor

/**
 * 把带坐标的文字片段还原成课表格子。
 *
 * 这一步是**纯算法**，不碰 PDF 库也不碰 Android —— 取词交给 [PdfTextExtractor]。
 * 分开的理由很直接：坐标归列的逻辑最容易写错，而它完全可以用构造数据测；
 * 真拿 PDF 跑只能人工看结果。
 *
 * 三条规则来自 `docs/parser/xtu-pdf.md`，都是踩过坑写的：
 * 1. 列按表头 x 分，且要回退半列宽（表头居中、正文左对齐）；
 * 2. 行不能用节次数字切 —— 一门课跨两行、单元格垂直居中，按数字切必然劈开；
 * 3. 表头过滤逐页判断 —— 续表页从页顶就是内容，固定 y 阈值会整页丢失。
 */
internal object PdfLayoutParser {

    private const val WEEKDAY_COUNT = 7

    fun parse(fragments: List<TextFragment>, params: PdfTableParams): ParseResult {
        if (fragments.isEmpty()) return ParseResult.EMPTY
        val cells = mutableListOf<ParsedSlot>()
        val warnings = mutableListOf<String>()

        // 续表页没有表头：沿用上一页的列锚
        var anchors: List<Float> = emptyList()

        fragments.groupBy { it.page }.toSortedMap().forEach { (page, items) ->
            val header = items.filter { it.text.trim().startsWith(params.columnAnchor) }
            if (header.size >= 2) {
                anchors = header.sortedBy { it.x }.map { it.x }
            } else if (params.headerPerPage && anchors.isEmpty()) {
                warnings.add("第 ${page + 1} 页没有表头，且此前也没读到表头，该页跳过")
                return@forEach
            }
            if (anchors.size < 2) return@forEach

            val step = (anchors.last() - anchors.first()) / (anchors.size - 1).coerceAtLeast(1)
            if (step <= 0f) return@forEach
            // 表头居中、正文左对齐：列左边界要回退半个列宽
            val left = anchors.first() - step / 2f

            val body = items.filter { f ->
                !f.text.trim().startsWith(params.columnAnchor) &&
                    f.text.trim() !in params.noiseDrop
            }

            // 先归列，再在每列内按 y 聚类
            body.groupBy { columnOf(it.x, left, step) }.forEach { (column, columnItems) ->
                if (column !in 0 until WEEKDAY_COUNT) return@forEach
                // 课程名可能被行距阈值切到上一块（民政样本里名字两行相隔 13.5pt，
                // 而格子边界是 25.5pt，单靠阈值两边都不好过）。改成以「(n-m节)」行为锚点：
                // 前面那些不含节次的块是**这门课名字的前半截**，并进来再解析。
                if (!params.namePrefixMerge) {
                    clusterRows(columnItems, params.rowGapPt).forEach { block ->
                        cells.addAll(toSlots(block.text, column + 1, params))
                    }
                    return@forEach
                }
                val blocks = clusterRows(columnItems, params.rowGapPt)
                // 只有"紧挨着"的前一块才算名字前半截；相隔一整个格子（约 25pt）的
                // 那是上一门课的尾巴，绝不能并进来
                val nearGap = params.rowGapPt * 1.6f
                var i = 0
                while (i < blocks.size) {
                    if (!hasSectionMarker(blocks[i].text)) { i++; continue }
                    // 从锚点往回看：只有"挨着"的无节次块才是名字前半截
                    val parts = ArrayDeque<String>()
                    var refY = blocks[i].y
                    var j = i - 1
                    while (j >= 0 && !hasSectionMarker(blocks[j].text) &&
                        abs(blocks[j].y - refY) <= nearGap
                    ) {
                        parts.addFirst(blocks[j].text)
                        refY = blocks[j].y
                        j--
                    }
                    val text = buildString {
                        parts.forEach { append(it) }
                        append(blocks[i].text)
                    }
                    cells.addAll(toSlots(text, column + 1, params))
                    i++
                }
            }
        }

        if (cells.isEmpty()) return ParseResult(ParseSource.UNKNOWN, emptyList(), warnings)
        return ParseResult(ParseSource.PDF_TABLE, cells, warnings)
    }

    /**
     * 「(1-2节)」与「(第 1-2 节)」都算节次标记。
     *
     * 湘潭写前者、中南民大写后者；只认一种会导致整份课表**一个格子都取不到**，
     * 而且日志里只看到"未识别的表格结构"，很难指向这一行。
     */
    private val SECTION_MARKER = Regex("\\(\\s*(?:第)?\\s*\\d+\\s*(?:[-—~]\\s*\\d+\\s*)?节\\s*\\)")

    /** 这一块里有没有「(1-2节)」这种节次标记 —— 有它才是格子的起点。 */
    private fun hasSectionMarker(text: String): Boolean =
        SECTION_MARKER.containsMatchIn(text)

    private fun columnOf(x: Float, left: Float, step: Float): Int = floor((x - left) / step).toInt()

    /**
     * 同一列内按 y 从大到小排序，相邻片段 y 差超过阈值就断开。
     * 拼接时**不加分隔符**：中文换行处会切断词语（`信息科技大楼北-` + `401`）。
     */
    private fun clusterRows(items: List<TextFragment>, gap: Float): List<RowBlock> {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedWith(compareByDescending<TextFragment> { it.y }.thenBy { it.x })
        val cells = mutableListOf<RowBlock>()
        val buffer = StringBuilder()
        var lastY: Float? = null
        var topY = 0f

        sorted.forEach { f ->
            val y = lastY
            if (y != null && abs(y - f.y) > gap) {
                if (buffer.isNotEmpty()) cells.add(RowBlock(topY, buffer.toString()))
                buffer.setLength(0)
            }
            if (buffer.isEmpty()) topY = f.y
            buffer.append(f.text.trim())
            lastY = f.y
        }
        if (buffer.isNotEmpty()) cells.add(RowBlock(topY, buffer.toString()))
        return cells
    }

    /** 一「块」：同一格子内被 y 聚类到一起的若干行。 */
    private data class RowBlock(val y: Float, val text: String)

    /**
     * 一格可能有多门课。
     *
     * 中南民大的打印页里，周三 5-6 节同时排着「3-18 周(双)」与「5-20 周(单)」两门，
     * 用 `find` 只会取到第一门，第二门静默消失 —— 用户不会知道课表少了一节课。
     * 所以这里改成遍历**全部**匹配；对只有一门课的模板，行为与之前完全一致。
     */
    private fun toSlots(cellText: String, dayOfWeek: Int, params: PdfTableParams): List<ParsedSlot> {
        val regex = params.cellRegex?.let { runCatching { Regex(it, RegexOption.DOT_MATCHES_ALL) }.getOrNull() }
            ?: return emptyList()
        val chunks = if (params.multiCourse) splitByCourseAnchors(cellText) else listOf(cellText)
        return chunks.mapNotNull { chunk ->
            regex.find(chunk)?.let { m -> toSlot(m, chunk, dayOfWeek, params) }
        }
    }

    /**
     * 把「一格多课」的长文本切成每门课一段。
     *
     * 文本长这样（无分隔符）：
     * `法律逻辑学[01]4-19周(第1-2节)教师01 15号楼15609 人工智能…[02]3-18周(双)(第5-6节)教师02 16号楼…`
     *
     * 每门课**只有一个**「(n-m 节)」标记，夹在课名之后、教师之前。所以：
     * ① 找出所有节次标记；
     * ② 每个标记向前回溯：最近的 `[课号]`，再往前一段中文 → 就是这门课的起点；
     * ③ 第 i 段 = [起点_i, 起点_{i+1})，最后一段到结尾。
     *
     * 这样不依赖分隔符，也不怕地点是中文（「模拟法庭」）。
     */
    private fun splitByCourseAnchors(cellText: String): List<String> {
        val markers = SECTION_MARKER.findAll(cellText).toList()
        if (markers.size <= 1) return listOf(cellText)

        val starts = markers.mapNotNull { m -> courseStartBefore(cellText, m.range.first) }
        if (starts.size != markers.size) return listOf(cellText)   // 回溯失败就退回老路径

        return starts.mapIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: cellText.length
            cellText.substring(start, end).trim()
        }.filter { it.isNotBlank() }
    }

    /** 从节次标记往前找「课名 + [课号]」的起点；找不到返回 null。 */
    private fun courseStartBefore(text: String, markerStart: Int): Int? {
        val head = text.substring(0, markerStart)
        val code = CODE_TAIL.findAll(head).lastOrNull() ?: return null
        // 从 [课号] 往前吃，直到撞上数字或 ASCII 字母（那是上一门的教室/房间号）。
        // 括号必须算名字的一部分：「人工智能与计算思维(实验)」里 ')' 紧挨着 [课号]，
        // 把它当阻断字符会让回溯失败，整格退回单门解析 —— 第二门课就此消失。
        var i = code.range.first
        while (i > 0) {
            val c = text[i - 1]
            if (c.isDigit() || c in 'a'..'z' || c in 'A'..'Z') break
            i--
        }
        while (i < code.range.first && text[i].isWhitespace()) i++
        return if (i < code.range.first) i else null
    }

    /** 文本里所有「[课号]」的位置，用来回溯课程起点。 */
    private val CODE_TAIL = Regex("\\[[^\\]]{0,12}\\]")

    private fun toSlot(
        m: MatchResult,
        cellText: String,
        dayOfWeek: Int,
        params: PdfTableParams
    ): ParsedSlot? {
        val name = m.groups["name"]?.value?.trim().orEmpty()
        val start = m.groups["s1"]?.value?.toIntOrNull()
        val end = m.groups["s2"]?.value?.toIntOrNull()
        val weeks = m.groups["weeks"]?.value.orEmpty()
        if (name.isEmpty() || start == null || end == null) return null
        val mask = app.nbult.lite.parser.WeekParser.mask(weeks, params.weekSyntax) ?: return null

        return ParsedSlot(
            name = name,
            teacher = m.groups["teacher"]?.value?.trim().orEmpty(),
            room = m.groups["room"]?.value?.trim().orEmpty(),
            dayOfWeek = dayOfWeek,
            startSection = start,
            endSection = end,
            weekMask = mask,
            raw = cellText,
            confidence = 0.9f,
            issues = emptyList(),
            // 只有声明了该组的模板才有性质（湘潭的模板没有），取不到就留空
            category = runCatching { m.groups["category"]?.value }.getOrNull()?.trim().orEmpty()
        )
    }
}

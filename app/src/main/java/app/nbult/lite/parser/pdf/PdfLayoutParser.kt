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
                clusterRows(columnItems, params.rowGapPt).forEach { cellText ->
                    toSlot(cellText, column + 1, params)?.let { cells.add(it) }
                }
            }
        }

        if (cells.isEmpty()) return ParseResult(ParseSource.UNKNOWN, emptyList(), warnings)
        return ParseResult(ParseSource.PDF_TABLE, cells, warnings)
    }

    private fun columnOf(x: Float, left: Float, step: Float): Int = floor((x - left) / step).toInt()

    /**
     * 同一列内按 y 从大到小排序，相邻片段 y 差超过阈值就断开。
     * 拼接时**不加分隔符**：中文换行处会切断词语（`信息科技大楼北-` + `401`）。
     */
    private fun clusterRows(items: List<TextFragment>, gap: Float): List<String> {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedWith(compareByDescending<TextFragment> { it.y }.thenBy { it.x })
        val cells = mutableListOf<String>()
        val buffer = StringBuilder()
        var lastY: Float? = null

        sorted.forEach { f ->
            val y = lastY
            if (y != null && abs(y - f.y) > gap) {
                if (buffer.isNotEmpty()) cells.add(buffer.toString())
                buffer.setLength(0)
            }
            buffer.append(f.text.trim())
            lastY = f.y
        }
        if (buffer.isNotEmpty()) cells.add(buffer.toString())
        return cells
    }

    private fun toSlot(cellText: String, dayOfWeek: Int, params: PdfTableParams): ParsedSlot? {
        val regex = params.cellRegex?.let { runCatching { Regex(it, RegexOption.DOT_MATCHES_ALL) }.getOrNull() }
            ?: return null
        val m = regex.find(cellText) ?: return null
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
            issues = emptyList()
        )
    }
}

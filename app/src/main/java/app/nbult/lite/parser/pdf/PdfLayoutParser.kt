package app.nbult.lite.parser.pdf

import app.nbult.lite.util.AppLog
import app.nbult.lite.parser.FieldExtractor
import app.nbult.lite.parser.ParseSource
import app.nbult.lite.parser.ParsedSlot
import app.nbult.lite.parser.ParseResult
import kotlin.math.abs
import kotlin.math.roundToInt
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

    /** 表格线判定：厚 ≤2.5pt、长 ≥15pt；更宽的就是底色块。 */
    private const val MAX_RULE_THICKNESS = 2.5f
    private const val MIN_RULE_LENGTH = 15f
    /** 双边框会造出 ~10pt 窄缝，低于这个宽/高的不算格子。 */
    private const val MIN_CELL_WIDTH = 20f
    private const val MIN_CELL_HEIGHT = 14f

    /** 行首的节次/时间前缀，例如 (08:00-08:45)、第1节、(第1-2节)。只裁这些。 */
    private val SECTION_TIME_PREFIX =
        Regex("""^\s*(?:\(?\d{1,2}:\d{2}\s*[-~—]\s*\d{1,2}:\d{2}\)?|第?\s*\d+\s*[-~—]?\s*\d*\s*节)""")

    /** 整个片段就是节次列内容（裁完没有剩下课程名时用来判断是否丢弃）。 */
    /** 地点：`15号楼15609`、`体育场馆南湖田径场`、`S160211` 这几种写法。 */
    private val ROOM_PATTERN = Regex(
        """\d+\s*号楼\s*[A-Za-z]?[\d\-]*|[^\s，,]{0,6}(?:场馆|教室)[^\s，,]{0,10}|S\d{4,}"""
    )

    private val SECTION_ONLY =
        Regex("""^\s*(?:\(?\d{1,2}:\d{2}[^)]*\)?|第?\s*\d+\s*[-~—]?\s*\d*\s*节)\s*$""")

    /** 判定「两个片段是否在同一视觉行」的 y 容差。 */
    private const val LINE_TOLERANCE = 3.5f

    /** 回溯课名时遇到这些字就停：它们是房间后缀，属于上一门课。 */
    private const val ROOM_TAIL_CHARS = "楼室庭场馆"

    fun parse(
        fragments: List<TextFragment>,
        params: PdfTableParams,
        rules: List<PdfRule> = emptyList()
    ): ParseResult {
        if (fragments.isEmpty()) return ParseResult.EMPTY
        // 有表格线且声明了 lines：按网格切，不再依赖"表头 + 半列宽"的假设
        if (params.cellSource == "lines" && rules.isNotEmpty()) {
            parseByRules(fragments, rules, params)?.let { return it }
        }
        val cells = mutableListOf<ParsedSlot>()
        val warnings = mutableListOf<String>()

        // 续表页没有表头：沿用上一页的列锚
        var anchors: List<Float> = emptyList()
        var anchorCenters: List<Float> = emptyList()
        var headerY = Float.POSITIVE_INFINITY

        fragments.groupBy { it.page }.toSortedMap().forEach { (page, items) ->
            val header = items.filter { it.text.trim().startsWith(params.columnAnchor) }
            if (header.size >= 2) {
                val sortedHeader = header.sortedBy { it.x }
                anchors = sortedHeader.map { it.x }
                // 表头是**居中**文字：按中心归列时要用 x + 宽/2，
                // 但 header 模式的左边界仍按 x 算 —— 两套都留着，别互相干扰
                anchorCenters = sortedHeader.map { it.x + halfWidth(it) }
                // 表头之上的都是页眉（「报表打印页面」「XX 同学课表」），不能并进第一门课
                headerY = sortedHeader.maxOf { it.y }
            } else if (params.headerPerPage && anchors.isEmpty()) {
                warnings.add("第 ${page + 1} 页没有表头，且此前也没读到表头，该页跳过")
                return@forEach
            }
            if (anchors.size < 2) return@forEach

            val step = (anchors.last() - anchors.first()) / (anchors.size - 1).coerceAtLeast(1)
            if (step <= 0f) return@forEach
            // 表头居中、正文左对齐：列左边界要回退半个列宽
            val left = anchors.first() - step / 2f

            val rawBody = items.filter { f ->
                !f.text.trim().startsWith(params.columnAnchor) &&
                    f.text.trim() !in params.noiseDrop &&
                    f.y < headerY - 1f
            }
            // PDFBox 按**视觉行**取词：一行的起点若在「节次/时间」列，整段文字会从那里开始，
            // 中心点就落到星期一里，于是 "(08:00-08:45)  法律逻辑学[01]…" 被当成课名，
            // 后面缺教师与地点 → 正则不匹配 → 整门课丢掉（中南民大周一全空的成因）。
            // 处理：把越过表格左边界的前缀按字符宽度裁掉，剩下的部分重新归属。
            val body = rawBody.mapNotNull { f -> clipToTableLeft(f, left) }

            // 先归列，再在每列内按 y 聚类
            val useCenter = params.columnEdge == "center"
            val right = anchors.last() + step / 2f
            body.groupBy { f ->
                if (useCenter && anchorCenters.size == anchors.size) {
                    columnByCenter(f, anchorCenters, left, right)
                } else {
                    columnOf(f.x, left, step)
                }
            }.forEach { (column, columnItems) ->
                AppLog.log("Parse", "归列：第 ${column + 1} 列 ${columnItems.size} 段")
                if (column !in 0 until WEEKDAY_COUNT) return@forEach
                // 课程名可能被行距阈值切到上一块（民政样本里名字两行相隔 13.5pt，
                // 而格子边界是 25.5pt，单靠阈值两边都不好过）。改成以「(n-m节)」行为锚点：
                // 前面那些不含节次的块是**这门课名字的前半截**，并进来再解析。
                if (params.groupByCourseCode) {
                    // 以课号为边界重新归组：无论行距多大，一门课的所有行都跟着它的课号走
                    mergeByCourseCode(clusterRows(columnItems, params.rowGapPt)).forEach { text ->
                        cells.addAll(toSlots(text, column + 1, params))
                    }
                    return@forEach
                }
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
     * 按文字中心就近归列。
     *
     * 正文常比表头更靠左（表头居中、正文左对齐），只按左边界整除会整体错列；
     * 用中心点比较更稳，但**必须给左右边界**：否则「节次」列（第1节 / 08:00~08:45）
     * 也会被算进星期一。
     */
    private fun columnByCenter(f: TextFragment, anchors: List<Float>, left: Float, right: Float): Int {
        val center = f.x + halfWidth(f)
        if (center < left || center > right) return -1
        return anchors.indices.minByOrNull { abs(anchors[it] - center) } ?: -1
    }

    /**
     * 裁掉越过表格左边界的文字前缀。
     *
     * 只对"起点在左边界之外"的片段生效；裁完为空就整段丢弃（那是纯节次列内容）。
     * 裁剪按**平均字宽**估算，允许偏一两个字 —— 后面的 [sanitizeName] 会把残留的
     * 数字、括号、空格去掉。
     */
    private fun clipToTableLeft(f: TextFragment, left: Float): TextFragment? {
        // 只在「起点在表格左侧」且「开头确实像节次/时间」时才裁。
        // 早先按几何位置一刀切，把湘潭、民政那些起点略偏左的正文也裁了，测试立刻挂掉。
        if (f.x >= left - 1f) return f
        val trimmed = f.text.trimStart()
        val prefix = SECTION_TIME_PREFIX.find(trimmed)
        if (prefix == null) {
            // 整段都是节次列内容（没有课程名）→ 丢弃；否则原样保留
            return if (SECTION_ONLY.matches(trimmed)) null else f
        }
        val rest = trimmed.substring(prefix.range.last + 1).trim()
        if (rest.isEmpty()) return null
        return f.copy(x = left, text = rest)
    }
    /**
     * 去掉课名里混进来的房间前缀。
     *
     * 实测样例：`16号楼 S160211人工智能与计算思维(实验)` —— 上一门课的地点被并进了
     * 下一门的课名，因为两段文字在 PDF 里紧邻。
     */
    private fun sanitizeName(raw: String): String {
        val text = raw.trim()
        val idx = text.lastIndexOf("号楼")
        if (idx < 0 || idx + 2 >= text.length) return text
        val rest = text.substring(idx + 2).trimStart { c ->
            c.isDigit() || c == ' ' || (c.code < 128 && c.isLetter())
        }
        return if (rest.isBlank()) text else rest
    }

    /** 半个字宽：优先用真实宽度，没有就按字号估。 */
    private fun halfWidth(f: TextFragment): Float =
        (if (f.width > 0f) f.width else estimatedWidth(f.text)) / 2f

    /**
     * 同一列内按 y 从大到小排序，相邻片段 y 差超过阈值就断开。
     * 拼接时**不加分隔符**：中文换行处会切断词语（`信息科技大楼北-` + `401`）。
     */
    private fun clusterRows(items: List<TextFragment>, gap: Float): List<RowBlock> {
        if (items.isEmpty()) return emptyList()
        // 同一视觉行里的片段 y 会差 1~2pt（基线对齐差异），直接按 y 排会把
        // "4-19周" 和 "(第1-2节)" 交错成 "(第1-24-19周节)"。
        // 所以先把 y 相近的归成一行，行内按 x 排，再按 y 从下往上拼。
        val rows = mutableListOf<MutableList<TextFragment>>()
        items.sortedByDescending { it.y }.forEach { fragment ->
            val row = rows.lastOrNull()
            if (row != null && abs(row.first().y - fragment.y) <= LINE_TOLERANCE) row.add(fragment)
            else rows.add(mutableListOf(fragment))
        }
        val sorted = rows.flatMap { row -> row.sortedBy { it.x } }
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

    /** 课号：`[01]`、`[04]` 这类。用它当课程边界比行距可靠得多。 */
    private val COURSE_CODE = Regex("""\[[^\]]{1,8}\]""")

    /**
     * 按课号把行块拼成"一门课"。
     *
     * 规则：遇到带课号的块就开新的一门，其后的块（哪怕隔得很远）都归它，
     * 直到下一个带课号的块出现。没有课号的块在开头时（页脚之类）单独成组。
     */
    private fun mergeByCourseCode(blocks: List<RowBlock>): List<String> {
        val out = mutableListOf<String>()
        val buffer = StringBuilder()
        var started = false
        blocks.forEach { block ->
            val hasCode = COURSE_CODE.containsMatchIn(block.text)
            if (hasCode && started) {
                out += buffer.toString()
                buffer.clear()
            }
            buffer.append(block.text)
            if (hasCode) started = true
        }
        if (buffer.isNotEmpty()) out += buffer.toString()
        return out.filter { it.isNotBlank() }
    }

    /**
     * 一格可能有多门课。
     *
     * 中南民大的打印页里，周三 5-6 节同时排着「3-18 周(双)」与「5-20 周(单)」两门，
     * 用 `find` 只会取到第一门，第二门静默消失 —— 用户不会知道课表少了一节课。
     * 所以这里改成遍历**全部**匹配；对只有一门课的模板，行为与之前完全一致。
     */
    private fun toSlots(rawCellText: String, dayOfWeek: Int, params: PdfTableParams): List<ParsedSlot> {
        // 归一化必须在正则之前：OCR 会把「号棱」认成「号楼」、把「—」当成连字符，
        // 这些字符肉眼几乎看不出差别，但正则一条都匹配不上
        val cellText = app.nbult.lite.parser.TextNormalizer.normalizeTimetable(rawCellText)
        val regex = params.cellRegex?.let { runCatching { Regex(it, RegexOption.DOT_MATCHES_ALL) }.getOrNull() }
            ?: return emptyList()
        if (!regex.containsMatchIn(cellText)) {
            // 匹配不上时把原文打出来：否则只会看到"少了几门课"，无从下手
            AppLog.log("Parse", "未匹配：周$dayOfWeek «${cellText.take(80)}»")
        }
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
            // 房间后缀（楼/室/庭/场/馆）属于**上一门课**，继续往前吃会把
            // "号楼模拟法庭" 拼进下一门的课名里
            if (c in ROOM_TAIL_CHARS) break
            i--
        }
        while (i < code.range.first && text[i].isWhitespace()) i++
        return if (i < code.range.first) i else null
    }

    /** 文本里所有「[课号]」的位置，用来回溯课程起点。 */
    private val CODE_TAIL = Regex("\\[[^\\]]{0,12}\\]")

    /**
     * 按表格线切格。
     *
     * 中南民大的课表每门课画一个框，行高随跨节次数变化（44 / 51 / 82pt）。
     * 文字聚类用的行距是**一个全局常数**，不可能同时适配 1 节格与 3 节格；
     * 而格子边界 PDF 自己画了，读出来即可。
     */
    private fun parseByRules(
        fragments: List<TextFragment>,
        rules: List<PdfRule>,
        params: PdfTableParams
    ): ParseResult? {
        val usable = rules.filter { it.thickness <= MAX_RULE_THICKNESS && it.length >= MIN_RULE_LENGTH }
        if (usable.isEmpty()) return null
        val xs = clusterValues(usable.filter { it.isVertical }.map { (it.x1 + it.x2) / 2f })
        val ys = clusterValues(usable.filter { !it.isVertical }.map { (it.y1 + it.y2) / 2f })
        if (xs.size < 3 || ys.size < 3) return null

        val lefts = mutableListOf<Float>()
        val rights = mutableListOf<Float>()
        val tops = mutableListOf<Float>()
        val bottoms = mutableListOf<Float>()
        // ⚠️ ys 是升序（y 越大越靠上，所以数值上是"大"的在后）：top 取后一个、bottom 取前一个。
        // 反过来写会算出负高度，格子全被当成窄缝丢掉 —— 表现是"网格一个都没有、悄悄退回文字聚类"。
        for (jy in 0 until ys.size - 1) {
            val top = ys[jy + 1]
            val bottom = ys[jy]
            if (top - bottom < MIN_CELL_HEIGHT) continue
            for (ix in 0 until xs.size - 1) {
                val l = xs[ix]
                val r = xs[ix + 1]
                if (r - l < MIN_CELL_WIDTH) continue
                lefts.add(l); rights.add(r); tops.add(top); bottoms.add(bottom)
            }
        }
        if (lefts.isEmpty()) return null

        // 文字按**重叠面积最大**归格。按"起点在格内"会漏掉整行长文本
        // （长课名的起点常在左边界之外，实测 13 门只剩 4 条）。
        val bucket = Array(lefts.size) { mutableListOf<TextFragment>() }
        for (f in fragments) {
            val fRight = f.x + if (f.width > 0f) f.width else estimatedWidth(f.text)
            val fTop = f.y + 2f
            val fBottom = f.y - 12f
            var best = -1
            var bestArea = 0f
            for (i in lefts.indices) {
                val ox = minOf(fRight, rights[i]) - maxOf(f.x, lefts[i])
                if (ox <= 0f) continue
                val oy = minOf(fTop, tops[i]) - maxOf(fBottom, bottoms[i])
                if (oy <= 0f) continue
                val area = ox * oy
                if (area > bestArea) { bestArea = area; best = i }
            }
            if (best >= 0) bucket[best].add(f)
        }

        val anchors = fragments.filter { it.text.trim().startsWith(params.columnAnchor) }.sortedBy { it.x }
        if (anchors.size < 2) return null

        val cells = mutableListOf<ParsedSlot>()
        for (i in lefts.indices) {
            val inside = bucket[i]
            if (inside.isEmpty()) continue
            val text = inside.sortedByDescending { it.y }.joinToString("") { it.text.trim() }
            if (!text.contains("周")) continue          // 节次列、表头、备注行都没有「周」
            val cx = (lefts[i] + rights[i]) / 2f
            val dayIndex = anchors.indices.minByOrNull { abs(anchors[it].x - cx) } ?: continue
            cells.addAll(toSlots(text, dayIndex + 1, params))
        }
        if (cells.isEmpty()) return null
        AppLog.log("Parse", "按表格线解析：${xs.size - 1} 列 × ${ys.size - 1} 行 → ${cells.size} 个时段")
        return ParseResult(ParseSource.PDF_TABLE, cells, emptyList())
    }

    /** 估算一行文字宽度：中文 12pt、其它 6pt。长文本的起点常在格左边界之外，必须算宽度。 */
    private fun estimatedWidth(text: String): Float =
        text.sumOf { c -> if (c.code > 0x2E80) 12.0 else 6.0 }.toFloat()

    /** 合并相距很近的线坐标（双边框、抗锯齿重叠）。 */
    private fun clusterValues(values: List<Float>, tolerance: Float = 2.5f): List<Float> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val out = mutableListOf<Float>()
        var start = sorted.first()
        var sum = 0f
        var count = 0
        for (v in sorted) {
            if (v - start > tolerance) {
                out.add(sum / count); start = v; sum = 0f; count = 0
            }
            sum += v; count++
        }
        out.add(sum / count)
        return out
    }

    private fun toSlot(
        m: MatchResult,
        cellText: String,
        dayOfWeek: Int,
        params: PdfTableParams
    ): ParsedSlot? {
        val name = sanitizeName(m.groups["name"]?.value.orEmpty())
        val start = m.groups["s1"]?.value?.toIntOrNull()
        val end = m.groups["s2"]?.value?.toIntOrNull()
        val weeks = m.groups["weeks"]?.value.orEmpty()
        if (name.isEmpty() || start == null || end == null) return null
        val mask = app.nbult.lite.parser.WeekParser.mask(weeks, params.weekSyntax) ?: return null

        return ParsedSlot(
            name = name,
            teacher = m.groups["teacher"]?.value?.trim().orEmpty(),
            // 房间号只取像房间的那一段：单元格尾部的周次/噪声会被并进来
            // （实测「15号楼155121-3」「10号楼10302李雄平」）
            room = ROOM_PATTERN.find(m.groups["room"]?.value.orEmpty())?.value?.trim().orEmpty(),
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

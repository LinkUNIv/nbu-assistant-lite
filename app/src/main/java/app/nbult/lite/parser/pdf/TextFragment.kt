package app.nbult.lite.parser.pdf

/** 从 PDF 取到的一段文字，带它所在页与坐标。坐标系是 PDF 原生方向：y 越大越靠上。 */
internal data class TextFragment(
    val page: Int,
    val x: Float,
    val y: Float,
    val text: String
)

/**
 * PDF 课表的取格参数。默认值来自湘潭样本的实测（`docs/parser/xtu-pdf.md`）。
 */
internal data class PdfTableParams(
    /** 表头锚：以「星期」开头的片段，其 x 作为该列中心。 */
    val columnAnchor: String = "星期",
    /** 同列相邻片段的 y 差超过它才算新单元格。实测行距 12pt、格间距 15.5pt。 */
    val rowGapPt: Float = 14f,
    /** 续表页没有表头，要沿用上一页的列锚，所以每页都要重新判断。 */
    val headerPerPage: Boolean = true,
    /** 单元格文本的模板正则（含命名分组）。为空则无法解析单元格内容。 */
    val cellRegex: String? = null,
    /**
     * 一格多课：按「(n-m 节)」锚点把一串文本切成多门课。
     *
     * 不能用正则预查切分 —— 拼出来的文本没有分隔符，预查会在每个中文字符处命中
     * （实测把「法律逻辑学」切成「学」）。这里改为按锚点位置切。
     */
    val multiCourse: Boolean = false,
    /** 噪声行：整段等于这些词就丢掉（学时统计之类）。 */
    val noiseDrop: List<String> = emptyList(),
    /** 周次写法，来自学校规则。换一种写法只改 JSON。 */
    val weekSyntax: app.nbult.lite.parser.WeekSyntax = app.nbult.lite.parser.WeekSyntax(),
    /**
     * 课程名跨块时是否并回。
     *
     * 有些学校的 PDF 把课程名排成两行、行距 13.5pt，而格子之间 25.5pt —— 单靠 [rowGapPt]
     * 两边都不好过。打开后以「(n-m节)」行为锚点，把**紧邻的无节次块**并回名字。
     * 默认关：湘潭那类一格一段的 PDF 不需要，开了反而有风险。
     */
    val namePrefixMerge: Boolean = false
)

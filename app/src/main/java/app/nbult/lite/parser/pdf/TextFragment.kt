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
    /** 噪声行：整段等于这些词就丢掉（学时统计之类）。 */
    val noiseDrop: List<String> = emptyList()
)

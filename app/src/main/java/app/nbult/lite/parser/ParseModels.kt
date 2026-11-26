package app.nbult.lite.parser

enum class ParseSource { HTML_TABLE, TEXT_GRID, EXCEL, WORD, PDF_TABLE, UNKNOWN }

data class ParsedSlot(
    val name: String,
    val teacher: String,
    val room: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val weekMask: Int,
    val raw: String,
    val confidence: Float,
    val issues: List<String>,
    /** 课程性质原文（公必 / 公限 / 专必…）。抽不到就是空串，不影响导入。 */
    val category: String = ""
)

/**
 * 一格没能解析出课时的原文。
 *
 * 为什么单独留：日志里只有一行「未匹配」，而导出快照里没有原文 —— 用户说"少了一门课"时，
 * 开发者只能靠截图反推是哪一格。带上页/列/坐标，就能在快照里直接定位。
 */
data class UnmatchedCell(
    val page: Int,
    val dayOfWeek: Int,
    /** 该格在页面上的纵坐标（PDF 原生方向，越大越靠上）。合并出来的块取起点那一行。 */
    val y: Float,
    val text: String
)

data class ParseResult(
    val source: ParseSource,
    val slots: List<ParsedSlot>,
    val warnings: List<String>,
    /** 取到但没解析成课的格子。默认空，非 PDF 路径不会填。 */
    val unmatched: List<UnmatchedCell> = emptyList()
) {
    val confident: List<ParsedSlot> get() = slots.filter { it.confidence >= 0.8f }
    val needsReview: List<ParsedSlot> get() = slots.filter { it.confidence < 0.8f }
    val courseCount: Int get() = slots.map { it.name }.distinct().size

    companion object {
        val EMPTY = ParseResult(ParseSource.UNKNOWN, emptyList(), emptyList())
    }
}

class ParseException(message: String) : Exception(message)

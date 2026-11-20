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

data class ParseResult(
    val source: ParseSource,
    val slots: List<ParsedSlot>,
    val warnings: List<String>,
    /**
     * 课表里提到、但没有安排时间的课程名（如中南民大页脚的「劳动教育」）。
     *
     * 不导入它们：没有节次的课塞进格子只会变成一格无时间的课。但也不能装作没看见 ——
     * 用户会以为导入丢了课。
     */
    val unscheduled: List<String> = emptyList()
) {
    val confident: List<ParsedSlot> get() = slots.filter { it.confidence >= 0.8f }
    val needsReview: List<ParsedSlot> get() = slots.filter { it.confidence < 0.8f }
    val courseCount: Int get() = slots.map { it.name }.distinct().size

    companion object {
        val EMPTY = ParseResult(ParseSource.UNKNOWN, emptyList(), emptyList())
    }
}

class ParseException(message: String) : Exception(message)

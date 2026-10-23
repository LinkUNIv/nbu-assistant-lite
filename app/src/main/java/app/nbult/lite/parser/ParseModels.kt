package app.nbult.lite.parser

enum class ParseSource { HTML_TABLE, TEXT_GRID, EXCEL, WORD, UNKNOWN }

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
    val issues: List<String>
)

data class ParseResult(
    val source: ParseSource,
    val slots: List<ParsedSlot>,
    val warnings: List<String>
) {
    val confident: List<ParsedSlot> get() = slots.filter { it.confidence >= 0.8f }
    val needsReview: List<ParsedSlot> get() = slots.filter { it.confidence < 0.8f }
    val courseCount: Int get() = slots.map { it.name }.distinct().size

    companion object {
        val EMPTY = ParseResult(ParseSource.UNKNOWN, emptyList(), emptyList())
    }
}

class ParseException(message: String) : Exception(message)

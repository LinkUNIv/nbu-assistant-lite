package app.nbult.lite.parser

object TextTimetableParser {

    fun parse(raw: String): ParseResult {
        val text = raw.replace("\r", "")
        if (text.isBlank()) return ParseResult(ParseSource.UNKNOWN, emptyList(), listOf("内容为空"))

        val attempts = listOfNotNull(
            gridStrategy(text),
            lineStrategy(text),
            blockStrategy(text)
        )

        return attempts.maxByOrNull { it.slots.size }
            ?: ParseResult(ParseSource.UNKNOWN, emptyList(), listOf("未能识别任何课程"))
    }

    private fun gridStrategy(text: String): ParseResult? {
        val lines = text.split("\n").map { it.trimEnd() }.filter { it.isNotBlank() }
        val headerIndex = lines.indexOfFirst { headerDayCount(it) >= 3 }
        if (headerIndex < 0) return null

        val rows = mutableListOf<List<String>>()
        rows += ParseUtils.splitCells(lines[headerIndex])
        for (index in headerIndex + 1 until lines.size) {
            rows += ParseUtils.splitCells(lines[index])
        }
        return GridParser.parse(ParseUtils.normalizeRows(rows), ParseSource.TEXT_GRID)
            .takeIf { it.slots.isNotEmpty() }
    }

    private fun lineStrategy(text: String): ParseResult? {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val slots = lines.mapNotNull { line ->
            if (FieldExtractor.dayOfWeek(line) == null) return@mapNotNull null
            if (FieldExtractor.sections(line) == null) return@mapNotNull null
            TextBlockParser.parseCell(line, null, null)
        }
        if (slots.isEmpty()) return null
        return ParseResult(ParseSource.TEXT_GRID, ParseUtils.dedupe(slots), emptyList())
    }

    private fun blockStrategy(text: String): ParseResult? {
        val blocks = text.split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (blocks.size <= 1) return null

        val slots = blocks.mapNotNull { TextBlockParser.parseCell(it, null, null) }
        if (slots.isEmpty()) return null
        return ParseResult(ParseSource.TEXT_GRID, ParseUtils.dedupe(slots), emptyList())
    }

    private fun headerDayCount(line: String): Int =
        Regex("""(?:星期|周)\s*[一二三四五六日天]""").findAll(line).count()
}

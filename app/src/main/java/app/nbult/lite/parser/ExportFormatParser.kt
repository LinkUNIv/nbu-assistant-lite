package app.nbult.lite.parser

object ExportFormatParser {

    fun parse(raw: String, source: ParseSource): ParseResult? {
        val lines = raw.replace("\r", "")
            .split('\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (lines.size < 3) return null

        val slots = mutableListOf<ParsedSlot>()
        var pendingName: String? = null
        var pendingTeacher: String? = null

        for (line in lines) {
            val fields = FieldExtractor.scheduleFields(line)
            if (fields != null) {
                val nameRaw = pendingName
                if (nameRaw != null) {
                    val name = FieldExtractor.normalizeCourseName(nameRaw)
                    if (name.isNotBlank()) {
                        slots += ParsedSlot(
                            name = name,
                            teacher = FieldExtractor.normalizeTeacher(pendingTeacher.orEmpty()),
                            room = fields.room,
                            dayOfWeek = fields.dayOfWeek,
                            startSection = fields.startSection,
                            endSection = fields.endSection,
                            weekMask = fields.weekMask,
                            raw = line,
                            confidence = 1f,
                            issues = emptyList()
                        )
                    }
                }
                pendingName = null
                pendingTeacher = null
                continue
            }

            if (pendingName != null && FieldExtractor.looksLikeTeacher(line)) {
                pendingTeacher = line
                continue
            }

            if (FieldExtractor.looksLikeCourseName(line)) {
                pendingName = line
                pendingTeacher = null
            }
        }

        if (slots.isEmpty()) return null
        return ParseResult(source, ParseUtils.dedupe(slots), emptyList())
    }
}

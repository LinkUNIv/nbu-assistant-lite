package app.nbult.lite.parser

import app.nbult.lite.domain.WeekMask

object TextBlockParser {

    fun parseCell(
        text: String,
        dayHint: Int?,
        sectionHint: Pair<Int, Int>?,
        defaultWeeks: Int = WeekMask.range(1, 16)
    ): ParsedSlot? {
        val normalized = text.replace('\u00A0', ' ').replace("\r", "")
        if (normalized.isBlank()) return null

        FieldExtractor.scheduleFields(normalized)?.let { fields ->
            val lines = normalized.split('\n').map { it.trim() }.filter { it.isNotBlank() }
            val nameLine = lines.firstOrNull { FieldExtractor.looksLikeCourseName(it) }
                ?: lines.firstOrNull()
                ?: return@let
            val teacherLine = lines.firstOrNull {
                it != nameLine && FieldExtractor.looksLikeTeacher(it)
            }
            val name = FieldExtractor.normalizeCourseName(nameLine)
            if (name.isBlank()) return@let
            return ParsedSlot(
                name = name,
                teacher = teacherLine?.let { FieldExtractor.normalizeTeacher(it) }.orEmpty(),
                room = fields.room,
                dayOfWeek = fields.dayOfWeek,
                startSection = fields.startSection,
                endSection = fields.endSection,
                weekMask = fields.weekMask,
                raw = normalized,
                confidence = 1f,
                issues = emptyList()
            )
        }

        val issues = mutableListOf<String>()
        var score = 0.4f

        val day = FieldExtractor.dayOfWeek(normalized) ?: dayHint
        if (day != null) score += 0.2f else issues += "缺少星期"

        val sections = FieldExtractor.sections(normalized) ?: sectionHint
        if (sections != null) score += 0.2f else issues += "缺少节次"

        val weeks = FieldExtractor.weekMask(normalized)
        val weeksMissing = weeks == null
        if (weeks != null) score += 0.2f else issues += "缺少周次，已按 1-16 周估算"

        val room = FieldExtractor.room(normalized).orEmpty()

        val name = extractName(normalized)
        if (name.isBlank()) {
            issues += "未识别课程名"
            return null
        }

        val teacher = FieldExtractor.teacherNear(normalized, name)
        if (teacher.isNotBlank()) score += 0.1f
        if (room.isNotBlank()) score += 0.1f

        if (day == null || sections == null) return null

        val confidence = if (weeksMissing) minOf(score, 0.7f) else score

        return ParsedSlot(
            name = name,
            teacher = teacher,
            room = room,
            dayOfWeek = day,
            startSection = sections.first,
            endSection = sections.second,
            weekMask = weeks ?: defaultWeeks,
            raw = normalized,
            confidence = confidence.coerceAtMost(1f),
            issues = issues
        )
    }

    private fun extractName(text: String): String {
        val candidates = text.split(Regex("""[\n\t]+|\s{2,}"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { FieldExtractor.looksLikeFieldLine(it) }

        if (candidates.isNotEmpty()) {
            return candidates.first().take(40)
        }

        val tokens = text.split(Regex("""[\n\t]+|\s+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        tokens.firstOrNull { !FieldExtractor.looksLikeFieldLine(it) }?.let { return it.take(40) }

        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        val stripped = firstLine
            .replace(Regex("""(?:星期|周)\s*[一二三四五六日天]"""), "")
            .replace(Regex("""第?\s*\d{1,2}\s*[-~—－]?\s*\d{0,2}\s*节"""), "")
            .replace(Regex("""\d{1,2}\s*[-~—－]?\s*\d{0,2}\s*周(?:\([单双]\))?"""), "")
            .trim()
        return stripped.take(40)
    }
}

package app.nbult.lite.parser

import app.nbult.lite.domain.WeekMask

object FieldExtractor {

    private val weekRange = Regex("""(\d{1,2})\s*[-~—－至]\s*(\d{1,2})\s*周""")
    private val weekList = Regex("""((?:\d{1,2}\s*[,，、]\s*)+\d{1,2})\s*周""")
    private val weekSingle = Regex("""(\d{1,2})\s*周""")
    private val oddFlag = Regex("""单周|\(\s*单\s*\)|（\s*单\s*）""")
    private val evenFlag = Regex("""双周|\(\s*双\s*\)|（\s*双\s*）""")

    private val sectionRange = Regex("""第?\s*(\d{1,2})\s*[-~—－至]\s*(\d{1,2})\s*节""")
    private val sectionSingle = Regex("""第?\s*(\d{1,2})\s*节""")

    private val dayPattern = Regex("""(?:星期|周)\s*([一二三四五六日天])""")

    private val teacherLabelled = Regex("""(?:教师|老师|任课教师)\s*[:：]?\s*([\u4e00-\u9fa5]{2,4})""")
    private val teacherSuffix = Regex("""([\u4e00-\u9fa5]{2,4})\s*老师""")

    private val roomPattern = Regex(
        """([\u4e00-\u9fa5]{2,10}(?:楼|馆|室|中心|操场|体育馆|场)[A-Za-z]?\s*\d{0,4}|[A-Za-z]{1,2}\s?\d{2,4}(?:[-—]\d{2,4})?)"""
    )

    fun dayOfWeek(text: String): Int? {
        Regex("""(?:星期|周)\s*([1-7])""").find(text)?.let { return it.groupValues[1].toInt() }
        return when (dayPattern.find(text)?.groupValues?.get(1)) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日", "天" -> 7
            else -> null
        }
    }

    data class ScheduleFields(
        val weekMask: Int,
        val dayOfWeek: Int,
        val startSection: Int,
        val endSection: Int,
        val room: String
    )

    fun scheduleFields(text: String): ScheduleFields? {
        val match = SCHEDULE_LINE.find(text) ?: return null
        val weeks = weekMask(match.groupValues[1]) ?: return null
        val day = dayFromToken(match.groupValues[2]) ?: return null
        val sections = sections(match.groupValues[3]) ?: return null
        val room = match.groupValues[4].substringBefore('\t').trim()
        return ScheduleFields(
            weekMask = weeks,
            dayOfWeek = day,
            startSection = sections.first,
            endSection = sections.second,
            room = room
        )
    }

    private fun dayFromToken(token: String): Int? {
        val trimmed = token.trim()
        trimmed.toIntOrNull()?.let { if (it in 1..7) return it }
        return when (trimmed) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日", "天" -> 7
            else -> null
        }
    }

    fun looksLikeCourseName(line: String): Boolean {
        val t = line.trim()
        if (t.length < 2) return false
        if (t.contains("周") || t.contains("节") || t.contains("星期")) return false
        if (t.startsWith("注意") || t.startsWith("备注")) return false
        if (COURSE_CODE.containsMatchIn(t)) return true
        if (t.contains('【') || t.contains('[')) return true
        val cjk = t.count { it in '\u4e00'..'\u9fa5' }
        return cjk >= 4
    }

    fun looksLikeTeacher(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 24) return false
        if (t.contains("周") || t.contains("节") || t.contains("星期")) return false
        if (t.contains("楼") || t.contains("室") || t.contains("场") || t.contains("中心")) return false
        val allowed = t.all { it in '\u4e00'..'\u9fa5' || it == ',' || it == '，' || it == '、' || it.isDigit() || it == ' ' }
        if (!allowed) return false
        val cjk = t.count { it in '\u4e00'..'\u9fa5' }
        return cjk in 2..12
    }

    fun normalizeCourseName(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("""^【[^】]*】\s*"""), "")
        s = s.replace(Regex("""\[\s*\d+\s*]\s*$"""), "")
        s = s.replace(Regex("""\s{2,}"""), " ")
        return s.trim()
    }

    fun courseCode(raw: String): String {
        val match = Regex("""^([A-Za-z0-9]{4,14})\s*[-_]\s*\S""").find(raw.trim()) ?: return ""
        return match.groupValues[1]
    }

    fun normalizeTeacher(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("""\d+$"""), "")
        s = s.replace(Regex("""[,，]"""), "、")
        return s.trim()
    }

    fun sections(text: String): Pair<Int, Int>? {
        sectionRange.find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a in 1..12 && b in 1..12) return minOf(a, b) to maxOf(a, b)
        }
        sectionSingle.find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            if (a in 1..12) return a to a
        }
        return null
    }

    fun weekMask(text: String): Int? {
        var mask: Int? = null
        weekRange.find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a in 1..WeekMask.MAX_WEEKS && b in 1..WeekMask.MAX_WEEKS) {
                mask = WeekMask.range(minOf(a, b), maxOf(a, b))
            }
        }
        if (mask == null) {
            weekList.find(text)?.let { m ->
                val weeks = m.groupValues[1].split(Regex("""[,，、]"""))
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 1..WeekMask.MAX_WEEKS }
                if (weeks.isNotEmpty()) mask = weeks.fold(0) { acc, w -> acc or WeekMask.bit(w) }
            }
        }
        if (mask == null) {
            weekSingle.find(text)?.let { m ->
                val a = m.groupValues[1].toInt()
                if (a in 1..WeekMask.MAX_WEEKS) mask = WeekMask.bit(a)
            }
        }
        val base = mask ?: return null
        return when {
            oddFlag.containsMatchIn(text) -> base and oddMask()
            evenFlag.containsMatchIn(text) -> base and evenMask()
            else -> base
        }
    }

    private fun oddMask(): Int = WeekMask.oddOf(1, WeekMask.MAX_WEEKS)

    private fun evenMask(): Int = WeekMask.evenOf(2, WeekMask.MAX_WEEKS)

    fun teacher(text: String): String? {
        teacherLabelled.find(text)?.let { return it.groupValues[1] }
        teacherSuffix.find(text)?.let { return it.groupValues[1] }
        return null
    }

    fun teacherNear(text: String, name: String): String {
        teacher(text)?.takeIf { it.isNotBlank() }?.let { return it }

        val tokens = text.split(Regex("""[\n\t]+|\s+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val nameIndex = tokens.indexOfFirst { it == name }
        val start = if (nameIndex >= 0) nameIndex + 1 else 0

        for (index in start until tokens.size) {
            val token = tokens[index]
            if (token.length !in 2..4) continue
            if (token.any { it !in '\u4e00'..'\u9fa5' }) continue
            if (dayOfWeek(token) != null) continue
            if (sections(token) != null) continue
            if (weekMask(token) != null) continue
            if (room(token) != null) continue
            return token
        }
        return ""
    }

    fun room(text: String): String? =
        roomPattern.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    fun looksLikeFieldLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return true
        if (dayOfWeek(t) != null) return true
        if (sections(t) != null) return true
        if (weekMask(t) != null) return true
        if (teacher(t) != null) return true
        if (room(t) != null && t.length <= 16) return true
        return false
    }

    private val SCHEDULE_LINE = Regex(
        """(\d{1,2}\s*[-~—－]\s*\d{1,2}\s*周(?:\(\s*[单双]\s*\)|（\s*[单双]\s*）)?|\d{1,2}\s*周)""" +
            """\s*[,，]\s*(?:星期|周)\s*([1-7一二三四五六日天])""" +
            """\s*[,，]\s*(第?\s*\d{1,2}\s*[-~—－]?\s*\d{0,2}\s*节)""" +
            """\s*[,，]\s*(.+)"""
    )

    private val COURSE_CODE = Regex("""^[A-Za-z0-9]{4,12}\s*[-_]""")
}

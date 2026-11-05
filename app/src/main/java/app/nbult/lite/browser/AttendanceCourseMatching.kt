package app.nbult.lite.browser

import java.text.Normalizer
import java.util.Locale

internal fun attendanceCourseName(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    .trim()
    .replace(Regex("""^(?:[A-Za-z0-9]{4,20}\s*[-_—–:：]+\s*|[\[(][A-Za-z0-9]{4,20}[\])]\s*)"""), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("""[^\p{L}\p{N}]"""), "")

internal data class AttendanceTarget(val name: String, val start: String, val end: String, val sessionKey: String = "")

/** A fuzzy name requires matching start and end times; ambiguity never selects a course. */
internal fun matchingAttendanceCourse(rows: List<CourseRow>, targets: List<AttendanceTarget>): CourseRow? {
    fun minute(raw: String): Int? {
        val parts = raw.split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, x ->
            val current = IntArray(b.length + 1); current[0] = i + 1
            b.forEachIndexed { j, y -> current[j+1] = minOf(current[j]+1, previous[j+1]+1, previous[j]+if(x==y) 0 else 1) }
            previous = current
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }
    val scored = rows.filter { it.open && !it.signed }.mapNotNull { row ->
        val name = attendanceCourseName(row.title)
        val times = Regex("""(?<![0-9])([01]?[0-9]|2[0-3]):([0-5][0-9])(?![0-9])""")
            .findAll(Normalizer.normalize(row.details, Normalizer.Form.NFKC)).mapNotNull { minute(it.value) }.toList()
        val score = targets.maxOfOrNull { target ->
            val expected = attendanceCourseName(target.name)
            val start = minute(target.start); val end = minute(target.end)
            val sameTime = times.zipWithNext().any { (s,e) -> s == start && e == end }
            val conflictingTime = times.size >= 2 && !sameTime
            when {
                name.isEmpty() || expected.isEmpty() || conflictingTime -> 0
                name == expected -> if (sameTime) 3 else 2
                sameTime && minOf(name.length,expected.length) >= 4 && similarity(name,expected) >= 0.8 -> 1
                else -> 0
            }
        } ?: 0
        if (score > 0) row to score else null
    }
    val best = scored.maxOfOrNull { it.second } ?: return null
    return scored.filter { it.second == best }.singleOrNull()?.first
}

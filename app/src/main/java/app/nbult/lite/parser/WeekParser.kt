package app.nbult.lite.parser

import app.nbult.lite.domain.WeekMask

/**
 * 周次写法的描述。语法进 JSON，算法留代码 —— 这样学校换一种写法时改规则即可。
 */
internal data class WeekSyntax(
    /** 段分隔符，中文逗号与顿号都要认。 */
    val separators: String = ",，、",
    /** 区间：`1-16周`、`第1-16周`。 */
    val range: String = "第?(\\d{1,2})\\s*[-~—－至]\\s*(\\d{1,2})\\s*周",
    /** 单周：`3周`、`第3周`。 */
    val single: String = "第?(\\d{1,2})\\s*周",
    /** 单周标记。 */
    val odd: String = "(?:[(（]\\s*单\\s*[)）]|单周)",
    /** 双周标记。 */
    val even: String = "(?:[(（]\\s*双\\s*[)）]|双周)"
)

/**
 * 周次 → 掩码。
 *
 * **必须按段解析**：`2-5周,7-18周` 是两段，`3-4周,10-11周,13-17周(单),18周` 是四段，
 * 且 `(单)` 只作用于它所在的那一段。
 *
 * 旧实现用 `find()` 取第一处匹配、奇偶作用于整串，遇到复合写法会**静默丢掉后半段** ——
 * 湘潭课表第 10 周因此少了 15 门课。
 */
internal object WeekParser {

    fun mask(text: String, syntax: WeekSyntax = WeekSyntax()): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val range = Regex(syntax.range)
        val single = Regex(syntax.single)
        val odd = Regex(syntax.odd)
        val even = Regex(syntax.even)

        var mask = 0
        var matched = false

        // 先按分隔符切段；每一段独立判奇偶，避免"(单)"污染其它段
        for (segment in trimmed.split(*syntax.separators.toCharArray())) {
            val seg = segment.trim()
            if (seg.isEmpty()) continue

            val parity = when {
                odd.containsMatchIn(seg) -> WeekMask.Parity.ODD
                even.containsMatchIn(seg) -> WeekMask.Parity.EVEN
                else -> WeekMask.Parity.ALL
            }

            val rangeMatch = range.find(seg)
            if (rangeMatch != null) {
                val a = rangeMatch.groupValues[1].toIntOrNull()
                val b = rangeMatch.groupValues[2].toIntOrNull()
                if (a != null && b != null) {
                    val from = minOf(a, b).coerceAtLeast(1)
                    val to = maxOf(a, b).coerceAtMost(WeekMask.MAX_WEEKS)
                    if (from <= to) {
                        mask = mask or WeekMask.build(from, to, parity)
                        matched = true
                    }
                }
                continue   // 区间命中就不再当单周处理，否则 "1-16周" 会被抓成 16
            }

            val singleMatch = single.find(seg) ?: continue
            val week = singleMatch.groupValues[1].toIntOrNull() ?: continue
            if (week in 1..WeekMask.MAX_WEEKS) {
                mask = mask or WeekMask.bit(week)
                matched = true
            }
        }

        return if (matched) mask else null
    }
}

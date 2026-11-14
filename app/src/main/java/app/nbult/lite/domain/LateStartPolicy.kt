package app.nbult.lite.domain

/**
 * 「某些楼栋推迟上课」的判定。
 *
 * 湘潭的逸夫楼、信息科技大楼比作息表晚 10 分钟打铃，但作息表是全校一张，
 * 不能为两栋楼改整张表 —— 所以做成**按地点**的偏移：卡片上标一句，提醒跟着顺延。
 *
 * 数据来自学校规则的 `lateStart`（湘潭的规则随资源包下发，改关键词不用发版）：
 * ```
 * "lateStart": [
 *   { "contains": ["逸夫楼", "信息科技大楼"], "minutes": 10, "label": "推迟 10 分钟上课" }
 * ]
 * ```
 *
 * 匹配用**子串**：课表里的地点会写成「逸夫楼A301」「信息科技大楼 302」这种形态。
 * 归一化只做两件事：去掉所有空白（含全角空格）、转小写 —— 不做分词也不做模糊匹配，
 * 免得「信息楼」被「信息科技大楼」误命中。
 */
data class LateStartRule(
    val contains: List<String> = emptyList(),
    val minutes: Int = 0,
    val label: String = ""
) {
    val effective: Boolean get() = minutes > 0 && contains.any { it.isNotBlank() }
}

object LateStartPolicy {

    /** 命中返回规则，未命中返回 null。 */
    fun of(room: String, rules: List<LateStartRule>): LateStartRule? {
        if (room.isBlank() || rules.isEmpty()) return null
        val target = normalize(room)
        if (target.isEmpty()) return null
        return rules.firstOrNull { rule ->
            rule.effective && rule.contains.any { key ->
                val k = normalize(key)
                k.isNotEmpty() && target.contains(k)
            }
        }
    }

    /** 直接要分钟数，方便塞给提醒推算。 */
    fun minutesOf(room: String, rules: List<LateStartRule>): Int =
        of(room, rules)?.minutes ?: 0

    internal fun normalize(text: String): String =
        text.replace('\u3000', ' ').replace(Regex("\\s+"), "").lowercase()
}

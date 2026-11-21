package app.nbult.lite.parser

/**
 * 解析前的文本归一化。
 *
 * 两类噪声都来自同一个源头：**OCR 与排版用的不是标准字符**。
 * 不加这一步，正则会在"看起来一样"的字符上失配 —— 这类失败最难查，
 * 因为日志里文字是对的，眼睛看不出差别。
 */
internal object TextNormalizer {

    /** 形近字：只收"在课表语境里几乎不可能出现原字"的那些，避免误伤。 */
    private val CONFUSABLES = mapOf(
        '棱' to '楼',   // 5 号楼 → 5 号棱
        '萘' to '第',   // 第 1 节 → 萘 1 节
        '蒡' to '第',
        '脊' to '节',
        '甥' to '周'
    )

    /** 连字符的各种写法，统一成 ASCII 短横。 */
    private val DASHES = charArrayOf('—', '–', '－', '~', '～', '至', '─', 'ー')

    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when {
                    ch in DASHES -> '-'
                    CONFUSABLES.containsKey(ch) -> CONFUSABLES.getValue(ch)
                    // 全角数字/字母 → 半角：OCR 偶尔把「４」认成全角
                    ch in '０'..'９' -> '0' + (ch - '０')
                    ch in 'Ａ'..'Ｚ' -> 'A' + (ch - 'Ａ')
                    ch in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /**
     * 课表语境专用：只做**局部**纠正，不动正文。
     *
     * 例如「15 号棱 15609」里「棱」在「号」后面才改成「楼」，
     * 避免把真的叫「棱」的字改掉。
     */
    fun normalizeTimetable(raw: String): String {
        var out = normalize(raw)
        out = out.replace("号棱", "号楼").replace("号樓", "号楼")
        out = out.replace("萘 ", "第 ").replace("萘", "第")
        // "第1-2节" 与 "第 1 - 2 节" 统一成紧凑写法，正则只需认一种
        out = Regex("第\\s*(\\d+)\\s*-\\s*(\\d+)\\s*节").replace(out, "第$1-$2节")
        return out
    }
}

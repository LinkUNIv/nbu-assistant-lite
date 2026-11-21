package app.nbult.lite.parser

/**
 * 解析前的文本归一化。
 *
 * 两类噪声来自同一源头：**OCR 与排版用的不是标准字符**。
 * 不加这一步，正则会在"看起来一样"的字符上失配 —— 这类失败最难查，
 * 因为日志里文字是对的，眼睛看不出差别。
 *
 * 原则：**只做无损替换**。全局把「棱」当「楼」会误伤「棱镜」，
 * 所以形近字一律放到 [normalizeTimetable] 里按上下文替换。
 */
internal object TextNormalizer {

    /** 连字符的各种写法，统一成 ASCII 短横。 */
    private val DASHES = charArrayOf('—', '–', '－', '~', '～', '至', '─', 'ー')

    /** 只做"任何语境下都等价"的替换：连字符与全角字符。 */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when {
                    ch in DASHES -> '-'
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
     * 课表语境专用：形近字按**上下文**纠正。
     *
     * 「棱」本身是正常汉字（棱镜），只有紧跟「号」时才必然是「号楼」；
     * 同理「萘」在化学课名里合法，只有出现在「第…节」的位置上才是误识。
     */
    fun normalizeTimetable(raw: String): String {
        var out = normalize(raw)
        out = out.replace("号棱", "号楼").replace("号樓", "号楼")
        // 「萘 1 - 2 节」这种位置上的「萘」一定是「第」
        out = Regex("萘\\s*(?=\\d)").replace(out, "第")
        // 节次写法统一成紧凑形态，正则只需认一种
        out = Regex("第\\s*(\\d+)\\s*-\\s*(\\d+)\\s*节").replace(out, "第$1-$2节")
        return out
    }
}

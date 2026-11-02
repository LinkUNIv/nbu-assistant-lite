package app.nbult.lite.browser

internal data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height get() = (bottom - top).coerceAtLeast(1)
}

internal object SignCodeRecognition {
    private val code = Regex("(?<![0-9A-Za-z])([0-9]{4})(?![0-9A-Za-z])")
    fun candidate(lines: List<OcrLine>): String? {
        fun normalized(s: String) = s.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")
        val anchored = mutableSetOf<String>()
        val anchors = lines.filter { it.text.contains("签到") || it.text.contains("簽到") }
        for (anchor in anchors) {
            val own = normalized(anchor.text).replace(Regex("(?<=[0-9])\\s+(?=[0-9])"), "")
            code.findAll(own).forEach { anchored.add(it.groupValues[1]) }
            lines.filter { it !== anchor && it.left >= anchor.left && it.left - anchor.right < anchor.height * 8 &&
                kotlin.math.abs((it.top + it.bottom) - (anchor.top + anchor.bottom)) < maxOf(it.height, anchor.height) * 2 }
                .filter { normalized(it.text).trim().matches(Regex("[0-9]{4}")) }
                .forEach { anchored.add(normalized(it.text).trim()) }
        }
        if (anchored.size == 1) return anchored.single()
        if (anchored.size > 1) return null
        val standalone = lines.filter { normalized(it.text).trim().matches(Regex("[0-9]{4}")) }
            .filter { normalized(it.text).trim().toInt() !in 1900..2100 }
        val tallest = standalone.maxByOrNull { it.height } ?: return null
        val otherHeight = lines.filter { it !== tallest }.map { it.height }.sorted().let { it.getOrNull(it.size / 2) ?: 1 }
        return if (standalone.size == 1 && tallest.height >= otherHeight * 1.3) normalized(tallest.text).trim() else null
    }
}

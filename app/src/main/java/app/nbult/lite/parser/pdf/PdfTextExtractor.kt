package app.nbult.lite.parser.pdf

import android.content.Context
import app.nbult.lite.util.AppLog
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * 从 PDF 取带坐标的文字。**只做取词**，归列交给 [PdfLayoutParser]。
 *
 * 教务导出的课表没有表格结构，「星期几」也不在文字里 —— 它只由列位置决定，
 * 所以必须逐行取出 x/y，而不是 `getText()` 拿一串没有归属的碎片。
 */
internal class PdfTextExtractor(context: Context) {

    init {
        // 必须初始化一次，否则字体与资源加载会在解析时才失败
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    fun extract(bytes: ByteArray): List<TextFragment> {
        val fragments = mutableListOf<TextFragment>()
        PDDocument.load(bytes).use { document ->
            for (page in 0 until document.numberOfPages) {
                val pageNumber = page
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: List<TextPosition>) {
                        if (textPositions.isEmpty()) return
                        // **不按行合并**：一行横跨两列时，合并后只能整体归到一列，
                        // 会把邻居的课名/教师一起拖进来（实测「周宪法学[04]…陈光斌,聂长建」）。
                        // 按词输出，每个词有自己的 x，才能各自归列。
                        val sorted = textPositions.sortedBy { it.xDirAdj }
                        var buffer = StringBuilder()
                        var startX = 0f
                        var widthSum = 0f
                        var prevEnd = Float.NaN
                        var glyphY = 0f
                        fun flush() {
                            val raw = buffer.toString().trim()
                            if (raw.isNotEmpty()) {
                                fragments.add(
                                    TextFragment(
                                        page = pageNumber,
                                        x = startX,
                                        y = -glyphY,
                                        text = raw,
                                        width = widthSum
                                    )
                                )
                            }
                            buffer = StringBuilder()
                            widthSum = 0f
                        }
                        for (position in sorted) {
                            val glyph = position.unicode ?: ""
                            if (glyph.isBlank()) continue
                            // 词间距：按字号的比例判断，避免中文字距被误切
                            val gapLimit = (position.fontSizeInPt * 0.35f).coerceIn(2f, 12f)
                            val gap = if (prevEnd.isNaN()) 0f else position.xDirAdj - prevEnd
                            if (buffer.isNotEmpty() && gap > gapLimit) flush()
                            if (buffer.isEmpty()) {
                                startX = position.xDirAdj
                                glyphY = position.yDirAdj
                            }
                            buffer.append(glyph)
                            widthSum += position.widthDirAdj
                            prevEnd = position.xDirAdj + position.widthDirAdj
                        }
                        flush()
                    }
                }
                stripper.sortByPosition = true
                stripper.startPage = page + 1
                stripper.endPage = page + 1
                stripper.getText(document)
            }
        }
        AppLog.log(
            "Parse",
            "PDF 取词完成：${fragments.size} 段 / ${fragments.map { it.page }.distinct().size} 页"
        )
        return fragments
    }
}

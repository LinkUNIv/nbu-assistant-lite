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
                        val trimmed = text.trim()
                        if (trimmed.isEmpty() || textPositions.isEmpty()) return
                        // 一行可能由多段组成；取最左那段的坐标作为这一行的归属依据
                        val first = textPositions.minByOrNull { it.xDirAdj } ?: return
                        fragments.add(
                            TextFragment(
                                page = pageNumber,
                                x = first.xDirAdj,
                                y = first.yDirAdj,
                                text = trimmed
                            )
                        )
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

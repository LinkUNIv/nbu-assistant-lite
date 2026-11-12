package app.nbult.lite.parser.pdf

import android.content.Context
import app.nbult.lite.parser.ParseException
import app.nbult.lite.parser.ParseResult
import app.nbult.lite.school.SchoolRules

/**
 * PDF 导入的入口：取词 → 归列 → 结果。
 *
 * 参数全部来自学校规则（`importer.pdf`），包括单元格正则与噪声词 ——
 * 换一所学校只改 JSON，不改这里。
 */
internal class PdfImporter(
    context: Context,
    private val rules: SchoolRules?
) {
    private val extractor = PdfTextExtractor(context)

    fun parse(bytes: ByteArray): ParseResult {
        val pdf = rules?.importer?.pdf
            ?: throw ParseException("当前学校未配置 PDF 导入参数")

        if (pdf.cellRegex.isNullOrBlank()) {
            // 宁大这类不提供 PDF 课表的学校没有配单元格正则，此时不该假装在解析
            throw ParseException("当前学校未配置 PDF 导入规则")
        }

        val fragments = extractor.extract(bytes)
        if (fragments.isEmpty()) {
            // 没有文本层 = 扫描件。这条要说清楚，否则用户只会看到"解析失败"
            throw ParseException("这份 PDF 没有文字层，可能是扫描件，暂时无法导入")
        }

        val result = PdfLayoutParser.parse(
            fragments,
            PdfTableParams(
                columnAnchor = pdf.columnAnchor,
                rowGapPt = pdf.rowGapPt,
                headerPerPage = pdf.headerPerPage,
                cellRegex = pdf.cellRegex,
                noiseDrop = pdf.noiseDrop,
                weekSyntax = rules?.weekSyntax ?: app.nbult.lite.parser.WeekSyntax()
            )
        )
        if (result.slots.isEmpty()) {
            throw ParseException("未识别的表格结构：没有取到任何课程")
        }
        return result
    }
}

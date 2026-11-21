package app.nbult.lite.parser.pdf

import android.content.Context
import app.nbult.lite.parser.ParseException
import app.nbult.lite.parser.ParseResult
import app.nbult.lite.school.SchoolRules
import app.nbult.lite.util.AppLog

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
    private val ocrExtractor = PdfOcrExtractor(context)

    fun parse(bytes: ByteArray): ParseResult {
        val pdf = rules?.importer?.pdf
            ?: throw ParseException("当前学校未配置 PDF 导入参数")

        if (pdf.cellRegex.isNullOrBlank()) {
            // 宁大这类不提供 PDF 课表的学校没有配单元格正则，此时不该假装在解析
            throw ParseException("当前学校未配置 PDF 导入规则")
        }

        // 文字层优先；没有文字层、或学校规则要求识图时，渲染成图再 OCR。
        // 识图这条路的坐标也会换算回 PDF 点空间，因此后面的列锚/行距/正则照用。
        var fragments = if (pdf.ocr) emptyList() else extractor.extract(bytes)
        if (fragments.isEmpty()) {
            AppLog.log("Parse", "无可用文字层，改用识图")
            fragments = kotlinx.coroutines.runBlocking { ocrExtractor.extract(bytes) }
        }
        if (fragments.isEmpty()) {
            throw ParseException("这份 PDF 既没有文字层，识图也没读到内容，请换一份导出文件")
        }

        // TODO(矢量线)：Android 侧读取表格线还没接通 —— pdfbox-android 的图形引擎
        // 用的坐标系与桌面版不同，签名也不一样。算法本身已由夹具验证（见 ScmzuPdfFixtureTest），
        // 现在还靠文字聚类兜底。
        val pdfRules = emptyList<PdfRule>()

        val result = PdfLayoutParser.parse(
            fragments,
            PdfTableParams(
                columnAnchor = pdf.columnAnchor,
                columnEdge = pdf.columnEdge,
                cellSource = pdf.cellSource,
                rowGapPt = pdf.rowGapPt,
                headerPerPage = pdf.headerPerPage,
                cellRegex = pdf.cellRegex,
                multiCourse = pdf.multiCourse,
                noiseDrop = pdf.noiseDrop,
                namePrefixMerge = pdf.namePrefixMerge,
                weekSyntax = rules?.weekSyntax ?: app.nbult.lite.parser.WeekSyntax()
            ),
            rules = pdfRules
        )
        if (result.slots.isEmpty()) {
            throw ParseException("未识别的表格结构：没有取到任何课程")
        }
        return result
    }
}

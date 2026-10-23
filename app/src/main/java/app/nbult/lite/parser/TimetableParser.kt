package app.nbult.lite.parser

import app.nbult.lite.util.AppLog

object TimetableParser {

    fun parse(raw: String): ParseResult {
        val text = raw.trim()
        if (text.isEmpty()) throw ParseException("内容为空")

        if (HtmlTableParser.looksLikeHtml(text)) {
            val result = HtmlTableParser.parse(text)
            if (result.slots.isNotEmpty()) return result
        }

        ExportFormatParser.parse(text, ParseSource.TEXT_GRID)?.let { return it }

        val result = TextTimetableParser.parse(text)
        AppLog.log("Parse", "文本解析 source=${result.source} slots=${result.slots.size} courses=${result.courseCount}")
        if (result.slots.isEmpty()) {
            throw ParseException("未能识别课程。可尝试网页读取，或导入教务导出的 Excel / Word 文件")
        }
        return result
    }

    fun parseFile(fileName: String, bytes: ByteArray): ParseResult {
        AppLog.log("Parse", "导入文件 $fileName size=${bytes.size}")
        if (bytes.isEmpty()) throw ParseException("文件内容为空")
        val lower = fileName.lowercase()

        return when {
            lower.endsWith(".xlsx") || lower.endsWith(".xlsm") -> parseExcel(bytes)
            lower.endsWith(".docx") -> parseDocx(bytes)
            lower.endsWith(".doc") -> parseDoc(bytes)
            else -> throw ParseException("仅支持 .xlsx / .xlsm / .docx / .doc（RTF）文件")
        }
    }

    private fun parseExcel(bytes: ByteArray): ParseResult {
        val rows = XlsxParser.parse(bytes)
        if (rows.isEmpty()) throw ParseException("Excel 中未读到内容")

        val byGrid = GridParser.parse(rows, ParseSource.EXCEL)
        if (byGrid.slots.isNotEmpty()) return byGrid

        val flattened = rows.joinToString("\n") { row -> row.joinToString("\t") }
        ExportFormatParser.parse(flattened, ParseSource.EXCEL)?.let { return it }

        throw ParseException("Excel 中未识别到课表，请确认表格含「星期一…」表头或「周次,星期X,节次,地点」格式")
    }

    private fun parseDocx(bytes: ByteArray): ParseResult {
        val content = DocxParser.parse(bytes)

        if (content.rows.isNotEmpty()) {
            val byGrid = GridParser.parse(content.rows, ParseSource.WORD)
            if (byGrid.slots.isNotEmpty()) return byGrid

            val flattened = content.rows.joinToString("\n") { row -> row.joinToString("\t") }
            ExportFormatParser.parse(flattened, ParseSource.WORD)?.let { return it }
        }

        if (content.paragraphs.isNotEmpty()) {
            val joined = content.paragraphs.joinToString("\n")
            ExportFormatParser.parse(joined, ParseSource.WORD)?.let { return it }
            val byText = TextTimetableParser.parse(joined)
            if (byText.slots.isNotEmpty()) return byText
        }

        throw ParseException("Word 中未识别到课表，请确认文档含课表表格")
    }

    private fun parseDoc(bytes: ByteArray): ParseResult {
        if (RtfParser.isOleDocument(bytes)) {
            throw ParseException("这是旧版二进制 .doc，请在 Word 中另存为 .docx 后导入")
        }

        if (RtfParser.isRtf(bytes)) {
            val text = RtfParser.toText(bytes)
            ExportFormatParser.parse(text, ParseSource.WORD)?.let { return it }
            val byText = TextTimetableParser.parse(text)
            if (byText.slots.isNotEmpty()) return byText
            throw ParseException("未能从 Word 表格中识别课程，请确认表格是否完整")
        }

        val text = ParseUtils.decode(bytes)
        ExportFormatParser.parse(text, ParseSource.WORD)?.let { return it }
        return parse(text)
    }
}

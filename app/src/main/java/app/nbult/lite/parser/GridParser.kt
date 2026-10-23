package app.nbult.lite.parser

import app.nbult.lite.domain.WeekMask
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object ParseUtils {

    fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("GBK"))
    }

    fun splitCells(line: String): List<String> {
        if (line.contains('\t')) return line.split('\t').map { it.trim() }
        if (line.contains('|')) return line.split('|').map { it.trim() }
        return line.split(Regex("""\s{2,}""")).map { it.trim() }
    }

    fun dedupe(slots: List<ParsedSlot>): List<ParsedSlot> = slots
        .distinctBy { "${it.name}|${it.dayOfWeek}|${it.startSection}|${it.endSection}|${it.weekMask}" }
        .sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }))

    fun defaultWeeks(): Int = WeekMask.range(1, 16)

    fun normalizeRows(rows: List<List<String>>): List<List<String>> {
        val width = rows.maxOfOrNull { it.size } ?: 0
        return rows.map { it + List(width - it.size) { "" } }
    }
}

data class GridModel(
    val rows: List<List<String>>,
    val source: ParseSource
)

object GridParser {

    fun parse(rows: List<List<String>>, source: ParseSource): ParseResult {
        if (rows.isEmpty()) return ParseResult(source, emptyList(), listOf("表格为空"))

        val headerIndex = rows.indexOfFirst { row ->
            row.count { FieldExtractor.dayOfWeek(it) != null } >= 3
        }
        if (headerIndex < 0) {
            return ParseResult(source, emptyList(), listOf("表格中没有找到「星期」表头行"))
        }

        val dayByColumn = mutableMapOf<Int, Int>()
        rows[headerIndex].forEachIndexed { column, text ->
            FieldExtractor.dayOfWeek(text)?.let { dayByColumn[column] = it }
        }
        if (dayByColumn.isEmpty()) {
            return ParseResult(source, emptyList(), listOf("未识别到星期列"))
        }

        val slots = mutableListOf<ParsedSlot>()
        for (rowIndex in headerIndex + 1 until rows.size) {
            val row = rows[rowIndex]
            val sectionHint = row.firstOrNull()?.let { FieldExtractor.sections(it) }
            row.forEachIndexed { column, cell ->
                if (column == 0 || cell.isBlank()) return@forEachIndexed
                val day = FieldExtractor.dayOfWeek(cell) ?: dayByColumn[column] ?: return@forEachIndexed
                TextBlockParser.parseCell(cell, day, sectionHint)?.let { slots += it }
            }
        }

        val warnings = if (slots.isEmpty()) listOf("表格中未识别到课程") else emptyList()
        return ParseResult(source, ParseUtils.dedupe(slots), warnings)
    }
}

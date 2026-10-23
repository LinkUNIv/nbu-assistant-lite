package app.nbult.lite.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object HtmlTableParser {

    fun looksLikeHtml(raw: String): Boolean {
        val t = raw.trimStart()
        return t.contains("<table", ignoreCase = true) ||
            (t.contains("<tr", ignoreCase = true) && t.contains("<td", ignoreCase = true))
    }

    fun parse(raw: String): ParseResult {
        val doc = Jsoup.parse(raw)
        val table = doc.select("table").maxByOrNull { it.select("tr").size }
            ?: return ParseResult(ParseSource.HTML_TABLE, emptyList(), listOf("未找到表格结构"))
        val rows = toTextRows(table)
        if (rows.isEmpty()) return ParseResult(ParseSource.HTML_TABLE, emptyList(), listOf("表格为空"))
        return GridParser.parse(rows, ParseSource.HTML_TABLE)
    }

    private fun toTextRows(table: Element): List<List<String>> {
        val grid = expandGrid(table)
        val seen = mutableSetOf<Element>()
        return grid.map { row ->
            row.map { cell ->
                when {
                    cell == null -> ""
                    seen.add(cell) -> cell.text().replace('\u00A0', ' ').trim()
                    else -> ""
                }
            }
        }
    }

    private fun expandGrid(table: Element): List<MutableList<Element?>> {
        val rows = table.select("tr")
        val grid = mutableListOf<MutableList<Element?>>()

        rows.forEachIndexed { rowIndex, tr ->
            while (grid.size <= rowIndex) grid.add(mutableListOf())
            var column = 0
            tr.select("> td, > th").forEach { cell ->
                val rowSpan = cell.attr("rowspan").toIntOrNull()?.coerceIn(1, 20) ?: 1
                val colSpan = cell.attr("colspan").toIntOrNull()?.coerceIn(1, 10) ?: 1
                while (column < grid[rowIndex].size && grid[rowIndex][column] != null) column++
                for (dr in 0 until rowSpan) {
                    val targetRow = rowIndex + dr
                    while (grid.size <= targetRow) grid.add(mutableListOf())
                    for (dc in 0 until colSpan) {
                        val targetColumn = column + dc
                        while (grid[targetRow].size <= targetColumn) grid[targetRow].add(null)
                        if (grid[targetRow][targetColumn] == null) {
                            grid[targetRow][targetColumn] = cell
                        }
                    }
                }
                column += colSpan
            }
        }
        return grid
    }
}

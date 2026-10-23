package app.nbult.lite.parser

import android.util.Xml
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

object XlsxParser {

    fun parse(bytes: ByteArray): List<List<String>> {
        var shared = emptyList<String>()
        var sheet: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "xl/sharedStrings.xml" -> shared = parseSharedStrings(zip.readBytes())
                    name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") && sheet == null ->
                        sheet = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }

        val sheetBytes = sheet ?: return emptyList()
        return parseSheet(sheetBytes, shared)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        val parser = newParser(bytes)
        var event = parser.eventType
        var current: StringBuilder? = null
        var inText = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inText && current != null) current.append(parser.text ?: "")
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> {
                        result += current?.toString().orEmpty()
                        current = null
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = mutableMapOf<Int, MutableMap<Int, String>>()
        val parser = newParser(bytes)
        var event = parser.eventType

        var rowIndex = -1
        var cellColumn = -1
        var cellType = ""
        var value: StringBuilder? = null
        var inValue = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        rowIndex = parser.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: (rowIndex + 1)
                        rows.getOrPut(rowIndex) { mutableMapOf() }
                    }
                    "c" -> {
                        cellColumn = columnIndex(parser.getAttributeValue(null, "r"))
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        value = StringBuilder()
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue && value != null) value.append(parser.text ?: "")
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value?.toString().orEmpty()
                        if (raw.isNotEmpty() && rowIndex >= 0 && cellColumn >= 0) {
                            val text = if (cellType == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                            if (text.isNotBlank()) {
                                rows.getOrPut(rowIndex) { mutableMapOf() }[cellColumn] = text.trim()
                            }
                        }
                        value = null
                    }
                }
            }
            event = parser.next()
        }

        if (rows.isEmpty()) return emptyList()
        val maxRow = rows.keys.max()
        val maxColumn = rows.values.flatMap { it.keys }.maxOrNull() ?: 0
        return (0..maxRow).map { r ->
            val line = rows[r]
            (0..maxColumn).map { c -> line?.get(c).orEmpty() }
        }
    }

    private fun columnIndex(ref: String?): Int {
        if (ref.isNullOrBlank()) return -1
        var column = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') column = column * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') column = column * 26 + (ch - 'a' + 1)
            else break
        }
        return column - 1
    }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), null)
        return parser
    }
}

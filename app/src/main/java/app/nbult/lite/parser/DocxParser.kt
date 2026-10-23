package app.nbult.lite.parser

import android.util.Xml
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

data class DocxContent(
    val rows: List<List<String>>,
    val paragraphs: List<String>
)

object DocxParser {

    fun parse(bytes: ByteArray): DocxContent {
        var document: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    document = zip.readBytes()
                    break
                }
                entry = zip.nextEntry
            }
        }

        val xml = document ?: return DocxContent(emptyList(), emptyList())
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), null)

        val tableRows = mutableListOf<MutableList<String>>()
        val paragraphs = mutableListOf<String>()

        var inTable = false
        var currentRow: MutableList<String>? = null
        var currentCell: StringBuilder? = null
        var currentParagraph: StringBuilder? = null
        var inText = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "tbl" -> if (tableRows.isEmpty()) inTable = true
                    "tr" -> if (inTable) currentRow = mutableListOf()
                    "tc" -> if (inTable) currentCell = StringBuilder()
                    "p" -> currentParagraph = StringBuilder()
                    "t" -> inText = true
                    "br" -> {
                        currentCell?.append('\n')
                        currentParagraph?.append('\n')
                    }
                }
                XmlPullParser.TEXT -> if (inText) {
                    val text = parser.text ?: ""
                    currentCell?.append(text)
                    currentParagraph?.append(text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "p" -> {
                        val text = currentParagraph?.toString()?.trim().orEmpty()
                        if (!inTable && text.isNotEmpty()) paragraphs += text
                        currentParagraph = null
                    }
                    "tc" -> {
                        currentRow?.add(currentCell?.toString()?.trim().orEmpty())
                        currentCell = null
                    }
                    "tr" -> {
                        currentRow?.let { tableRows += it }
                        currentRow = null
                    }
                    "tbl" -> inTable = false
                }
            }
            event = parser.next()
        }

        val rows = tableRows
            .filter { row -> row.any { it.isNotBlank() } }
            .let { ParseUtils.normalizeRows(it) }

        return DocxContent(rows, paragraphs)
    }
}

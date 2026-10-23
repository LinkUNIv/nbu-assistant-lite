package app.nbult.lite.parser

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

object RtfParser {

    private val GBK: Charset = Charset.forName("GBK")

    fun isRtf(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        return String(bytes, 0, 5, Charsets.US_ASCII) == "{\\rtf"
    }

    fun isOleDocument(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        return bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()
    }

    fun toText(bytes: ByteArray): String {
        val text = StringBuilder()
        val pending = ByteArrayOutputStream()
        var index = 0
        var depth = 0
        var skipUntilDepth = -1

        fun flushPending() {
            if (pending.size() > 0) {
                text.append(String(pending.toByteArray(), GBK))
                pending.reset()
            }
        }

        while (index < bytes.size) {
            val current = bytes[index].toInt() and 0xFF
            when {
                current == '{'.code -> {
                    depth++
                    if (index + 2 < bytes.size &&
                        bytes[index + 1].toInt() == '\\'.code &&
                        bytes[index + 2].toInt() == '*'.code &&
                        skipUntilDepth < 0
                    ) {
                        skipUntilDepth = depth
                    }
                    index++
                }

                current == '}'.code -> {
                    if (skipUntilDepth >= 0 && depth == skipUntilDepth) skipUntilDepth = -1
                    depth--
                    index++
                }

                current == '\\'.code -> {
                    if (index + 1 >= bytes.size) break
                    val next = bytes[index + 1].toInt() and 0xFF
                    when {
                        next == '\\'.code || next == '{'.code || next == '}'.code -> {
                            if (skipUntilDepth < 0) {
                                flushPending()
                                text.append(next.toChar())
                            }
                            index += 2
                        }

                        next == '\''.code -> {
                            if (index + 3 < bytes.size) {
                                val hex = String(bytes, index + 2, 2, Charsets.US_ASCII)
                                val value = hex.toIntOrNull(16)
                                if (value != null && skipUntilDepth < 0) pending.write(value)
                                index += 4
                            } else {
                                index = bytes.size
                            }
                        }

                        else -> {
                            var cursor = index + 1
                            val wordStart = cursor
                            while (cursor < bytes.size && bytes[cursor].toInt().toChar().isLetter()) cursor++
                            val word = String(bytes, wordStart, cursor - wordStart, Charsets.US_ASCII)

                            var number: Int? = null
                            val numberStart = cursor
                            if (cursor < bytes.size &&
                                (bytes[cursor].toInt() == '-'.code || bytes[cursor].toInt() in '0'.code..'9'.code)
                            ) {
                                while (cursor < bytes.size &&
                                    (bytes[cursor].toInt() == '-'.code || bytes[cursor].toInt() in '0'.code..'9'.code)
                                ) cursor++
                                number = String(bytes, numberStart, cursor - numberStart, Charsets.US_ASCII).toIntOrNull()
                            }
                            if (cursor < bytes.size && bytes[cursor].toInt() == ' '.code) cursor++

                            if (word == "bin" && number != null) {
                                index = (cursor + number).coerceAtMost(bytes.size)
                                continue
                            }

                            if (skipUntilDepth < 0) {
                                when (word) {
                                    "u" -> if (number != null) {
                                        flushPending()
                                        text.append((number and 0xFFFF).toChar())
                                        if (cursor < bytes.size && bytes[cursor].toInt() == '?'.code) cursor++
                                    }

                                    "cell", "tab" -> {
                                        flushPending()
                                        text.append('\t')
                                    }

                                    "row", "par", "line" -> {
                                        flushPending()
                                        text.append('\n')
                                    }
                                }
                            }
                            index = cursor
                        }
                    }
                }

                else -> {
                    if (skipUntilDepth < 0) pending.write(current)
                    index++
                }
            }
        }
        flushPending()

        return text.toString()
            .replace("\r", "")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{2,}"""), "\n")
            .trim()
    }
}

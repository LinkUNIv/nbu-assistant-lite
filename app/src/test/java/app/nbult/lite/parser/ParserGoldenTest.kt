package app.nbult.lite.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 解析器的**回归网**。
 *
 * 在这之前 `parser/` 有 1055 行、零个测试 —— 改一条正则只能靠真机导入试，
 * 而多校适配正要动这一层。这里把现有样本的解析结果钉成契约：
 *
 * 1. 结果与 `parser_golden/<样本>.txt` 逐行一致；
 * 2. 契约不存在时不会假装通过 —— 会把实际结果写到 `build/parser-golden/` 并失败，
 *    由人工过目后再收进资源目录（避免把现有 bug 固化成"正确答案"）；
 * 3. 空内容与非课表文本必须**明确失败或返回空**，不许猜出一门课。
 */
class ParserGoldenTest {

    private val samples = listOf(
        "samples/timetable_sample.txt",
        "samples/timetable_sample_plain.txt"
    )

    private fun readClasspath(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "找不到 $path" }
            .bufferedReader().use { it.readText() }

    /** 一行一个格子，字段固定顺序，便于肉眼比对 diff。 */
    private fun render(result: ParseResult): String = buildString {
        appendLine("source=${result.source}")
        result.slots
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.name }))
            .forEach { s ->
                appendLine(
                    listOf(
                        s.name,
                        s.teacher,
                        s.room,
                        s.dayOfWeek,
                        s.startSection,
                        s.endSection,
                        "0x%04X".format(s.weekMask)
                    ).joinToString("|")
                )
            }
    }

    private fun golden(name: String): String =
        readClasspath("parser_golden/$name").replace("\r\n", "\n").trim()

    @Test
    fun `现有样本的解析结果与契约一致`() {
        samples.forEach { path ->
            val name = path.substringAfterLast('/').removeSuffix(".txt") + ".txt"
            val actual = render(TimetableParser.parse(readClasspath(path))).trim()

            val fromResources = runCatching { golden(name) }.getOrNull()
            if (fromResources == null) {
                // 契约还不存在：写出实际结果并失败，等人工确认后收编
                val out = File("build/parser-golden").apply { mkdirs() }
                File(out, name).writeText(actual + "\n")
                throw AssertionError("缺少契约 parser_golden/$name；已生成到 build/parser-golden/$name，请过目后收进 src/test/resources")
            }
            assertEquals("样本 $path 的解析结果与契约不符（实际值见上方）", fromResources, actual)
        }
    }

    @Test
    fun `空内容会明确失败`() {
        val thrown = runCatching { TimetableParser.parse("   ") }.exceptionOrNull()
        assertTrue("空内容应抛 ParseException，实际：$thrown", thrown is ParseException)
    }

    @Test
    fun `非课表文本不会被猜成课程`() {
        val text = """
            这是一段完全无关的文字，用来确认解析器不会硬凑出一门课。
            它没有星期、没有节次、没有周次，也没有教室。
        """.trimIndent()
        val result = runCatching { TimetableParser.parse(text) }
        val slots = result.getOrNull()?.slots.orEmpty()
        // 要么明确报错，要么给出空结果 —— 两者都可接受，唯一不可接受的是编出课来
        assertTrue("非课表文本不应产出格子，实际 ${slots.size} 条", slots.isEmpty())
    }
}

package app.nbult.lite.data.changelog

import android.content.Context
import app.nbult.lite.util.AppLog

/** 一个版本块内的一类内容。解析结果只保留这四种，够用且渲染简单。 */
sealed interface ChangelogBlock {
    /** `### 一、版本号在两处重复` */
    data class Heading(val text: String) : ChangelogBlock

    /** 连续的 `- ` 列表项 */
    data class Bullets(val items: List<String>) : ChangelogBlock

    /** 普通段落。表格已在此阶段拍平为逐行文字，因此也可能包含换行。 */
    data class Paragraph(val text: String) : ChangelogBlock

    /** 围栏代码块 */
    data class Code(val text: String) : ChangelogBlock
}

/**
 * 一个版本。
 *
 * `version` 与 `channel` 分开存：发版标签是 `v0.31.1-Preview`，
 * 但界面要能单独把「0.31.1」与「Preview」用不同字重排出来，
 * 也方便以后按渠道过滤（正式版 / 预览版）。
 */
data class ChangelogVersion(
    val version: String,
    val channel: String,
    val date: String,
    val headline: String,
    val blocks: List<ChangelogBlock>
) {
    /** `0.31.1 Preview`；正式版无渠道后缀时只有版本号。 */
    val displayVersion: String
        get() = if (channel.isBlank()) version else "$version $channel"
}

/**
 * 应用内更新日志。
 *
 * 数据来源是仓库根目录的 `CHANGELOG.md` —— 构建时由 Gradle 复制进
 * `assets/changelog/CHANGELOG.md`（见 `app/build.gradle.kts` 的 `copyChangelog`）。
 * 这样变更记录只有一份，写入 CHANGELOG 就等于写进了应用内页面。
 *
 * 只解析本项目实际用到的 Markdown 子集：二级标题分版本、三级标题作分组、
 * 列表、围栏代码块、表格。刻意不引入 Markdown 库：完整实现会带来
 * 一整套用不上的语法支持，而这里需要的规则不到一百行。
 */
object ChangelogCatalog {

    private const val ASSET = "changelog/CHANGELOG.md"

    /** `## v0.31.1-Preview — 2026-10-29`；破折号兼容半角与短横。 */
    private val VERSION_HEADER = Regex("""^##\s+v(\S+?)(?:-(\w+))?\s+[—–-]\s+(\S+)\s*$""")

    fun load(context: Context): List<ChangelogVersion> = runCatching {
        val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        parse(raw)
    }.getOrElse {
        // 变更记录读不出来不该让页面崩，界面自己显示"读取失败"
        AppLog.log("Changelog", "读取变更记录失败: ${it.message}")
        emptyList()
    }

    fun parse(raw: String): List<ChangelogVersion> {
        val versions = mutableListOf<ChangelogVersion>()
        var header: MatchResult? = null
        var body = mutableListOf<String>()

        fun flush() {
            val h = header ?: return
            if (body.isEmpty()) return
            val (headline, blocks) = parseBody(body)
            versions += ChangelogVersion(
                version = h.groupValues[1],
                channel = h.groupValues[2],
                date = h.groupValues[3],
                headline = headline,
                blocks = blocks
            )
        }

        for (line in raw.lineSequence()) {
            val m = VERSION_HEADER.matchEntire(line.trim())
            if (m != null) {
                flush()
                header = m
                body = mutableListOf()
            } else if (header != null) {
                body += line
            }
        }
        flush()
        // 新版本在前：CHANGELOG 本身就是倒序写的，这里再排一次是为了防止手工插入位置出错。
        // 不能直接用 sortedByDescending —— 比较键是 List<Int>，本身不实现 Comparable
        return versions.sortedWith(Comparator { a, b ->
            compareKeys(parseVersionKey(b.version), parseVersionKey(a.version))
        })
    }

    /** 版本号按数字段比较，`0.9.0` 不能排在 `0.10.0` 之后。 */
    private fun parseVersionKey(version: String): List<Int> =
        version.split('.').map { segment ->
            // 取前导数字：`9` / `9b` 都按 9 计
            segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }

    /** 逐段比较，段数不同时短的一方按 0 补齐（`0.31` 与 `0.31.0` 视为同版）。 */
    private fun compareKeys(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** 返回 (标题行, 内容块)。 */
    private fun parseBody(lines: List<String>): Pair<String, List<ChangelogBlock>> {
        var headline = ""
        val blocks = mutableListOf<ChangelogBlock>()
        val bullets = mutableListOf<String>()
        val paragraph = mutableListOf<String>()
        val code = mutableListOf<String>()
        var inCode = false

        fun flushBullets() {
            if (bullets.isNotEmpty()) {
                blocks += ChangelogBlock.Bullets(bullets.toList())
                bullets.clear()
            }
        }

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += ChangelogBlock.Paragraph(paragraph.joinToString("\n"))
                paragraph.clear()
            }
        }

        fun flushAll() {
            flushBullets()
            flushParagraph()
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()

            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    blocks += ChangelogBlock.Code(code.joinToString("\n"))
                    code.clear()
                }
                inCode = !inCode
                continue
            }
            if (inCode) {
                code += line
                continue
            }

            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flushAll()

                trimmed == "---" -> flushAll()

                // 「详见 docs/releases/…」是仓库内相对链接，应用里点不开，直接略过
                trimmed.startsWith("详见 ") -> Unit

                trimmed.startsWith("###") -> {
                    flushAll()
                    blocks += ChangelogBlock.Heading(trimmed.trimStart('#').trim())
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    flushParagraph()
                    bullets += trimmed.drop(2).trim()
                }

                // 表格：拆成「单元格 · 单元格」逐行文字。手机屏宽放不下真正的表格，
                // 横滚又难读，拍平反而更清楚。分隔行（|---|）丢弃
                trimmed.startsWith("|") -> {
                    flushAll()
                    if (trimmed.contains(Regex("""^\|[\s:|-]+\|$"""))) continue
                    val cells = trimmed.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    if (cells.isNotEmpty()) paragraph += cells.joinToString(" · ")
                }

                // 整行加粗且还没有标题时，它就是这一版的标题
                headline.isEmpty() && trimmed.startsWith("**") && trimmed.endsWith("**") &&
                    trimmed.count { it == '*' } == 4 -> {
                    flushAll()
                    headline = trimmed.trim('*')
                }

                else -> {
                    flushBullets()
                    paragraph += trimmed
                }
            }
        }
        if (inCode && code.isNotEmpty()) blocks += ChangelogBlock.Code(code.joinToString("\n"))
        flushAll()
        return headline to blocks
    }
}

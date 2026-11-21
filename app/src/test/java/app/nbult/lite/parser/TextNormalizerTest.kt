package app.nbult.lite.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归一化的边界。
 *
 * 这些字符**肉眼几乎一样**，但正则一条都匹配不上 —— 失败时日志里的文字看着是对的，
 * 所以必须有单测把"归一化到底改了什么"钉住。
 */
class TextNormalizerTest {

    @Test
    fun `连字符的各种写法统一成短横`() {
        assertEquals("4-19", TextNormalizer.normalize("4—19"))
        assertEquals("4-19", TextNormalizer.normalize("4–19"))
        assertEquals("4-19", TextNormalizer.normalize("4－19"))
        assertEquals("4-19", TextNormalizer.normalize("4~19"))
        assertEquals("4-19", TextNormalizer.normalize("4至19"))
    }

    @Test
    fun `形近字在课表语境下纠正`() {
        assertEquals("15号楼15609", TextNormalizer.normalizeTimetable("15号棱15609"))
        assertEquals("第1-2节", TextNormalizer.normalizeTimetable("萘1-2节"))
    }

    @Test
    fun `没带号字的棱不改`() {
        // 「棱」本身是正常汉字，只有紧跟「号」时才当「号楼」处理
        assertTrue(TextNormalizer.normalizeTimetable("棱镜实验").contains("棱"))
    }

    @Test
    fun `节次写法统一成紧凑形态`() {
        assertEquals("第1-2节", TextNormalizer.normalizeTimetable("第 1 - 2 节"))
        assertEquals("第9-11节", TextNormalizer.normalizeTimetable("第9—11节"))
    }

    @Test
    fun `全角数字与字母转半角`() {
        assertEquals("101", TextNormalizer.normalize("１０１"))
        assertEquals("S160211", TextNormalizer.normalize("Ｓ160211"))
    }

    @Test
    fun `正文不受影响`() {
        val raw = "法律逻辑学[01] 4-19周(第1-2节) 教师甲 15号楼15609"
        assertEquals(raw, TextNormalizer.normalizeTimetable(raw))
    }
}

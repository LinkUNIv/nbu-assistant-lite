package app.nbult.lite.domain

import app.nbult.lite.data.db.Course
import app.nbult.lite.data.db.CourseSlot
import app.nbult.lite.data.db.PeriodTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公选课按另一套作息表计算。
 *
 * 民政学院课第 3 节 10:10、公选课 10:30 —— 同一节次两个时刻，
 * 光靠"偏移"表达不了，必须是"换整张表"。这里钉住判定与取表两件事。
 */
class PublicCourseTest {

    private val rule = PublicCourseRule(
        preset = "C",
        badge = "公选课",
        matchKeywords = listOf("公必", "公限", "公选")
    )

    private val normal = listOf(
        PeriodTime(1, "08:00", "08:45"),
        PeriodTime(3, "10:10", "10:55")
    )
    private val publicTable = listOf(
        PeriodTime(1, "08:20", "09:05"),
        PeriodTime(3, "10:30", "11:15")
    )

    private fun course(category: String = "", override: Boolean? = null) =
        Course(id = 1, termId = 1, name = "大学英语", category = category, publicCourseOverride = override)

    private fun entry(course: Course, room: String = "1-101", start: Int = 3) = TimetableEntry(
        slot = CourseSlot(
            id = 1, courseId = 1, termId = 1, dayOfWeek = 1,
            startSection = start, endSection = start, weekMask = 1, room = room
        ),
        course = course
    )

    @Test
    fun `课程性质含公必公限判为公选课`() {
        assertTrue(rule.categoryLooksPublic("公共必修（公必）"))
        assertTrue(rule.categoryLooksPublic("公限"))
        // 只是课名带"公共"，性质是专业课 → 不算
        assertFalse(rule.categoryLooksPublic("专业必修"))
    }

    @Test
    fun `规则未配置时一律不生效`() {
        val off = PublicCourseRule()
        assertFalse(off.enabled)
        assertFalse(off.categoryLooksPublic("公必"))
        assertFalse(off.appliesTo(course(category = "公必")))
    }

    @Test
    fun `手动开关优先于课程性质`() {
        // 性质写着公必，但用户手动关掉 → 按学院课走
        assertFalse(rule.appliesTo(course(category = "公必", override = false)))
        // 性质是专业课，用户手动打开 → 按公选课走
        assertTrue(rule.appliesTo(course(category = "专业必修", override = true)))
    }

    @Test
    fun `公选课取另一张表，学院课取当前表`() {
        val periods = normal.associateBy { it.idx }
        val pub = effectiveTimes(entry(course(category = "公必")), periods, rule, publicTable, 0)
        assertEquals("10:30" to "11:15", pub)

        val normalTimes = effectiveTimes(entry(course(category = "专业课")), periods, rule, publicTable, 0)
        assertEquals("10:10" to "10:55", normalTimes)
    }

    @Test
    fun `公选课再叠加楼栋推迟`() {
        val periods = normal.associateBy { it.idx }
        val shifted = effectiveTimes(entry(course(category = "公必")), periods, rule, publicTable, 10)
        assertEquals("10:40" to "11:25", shifted)
    }

    @Test
    fun `表里没有该节次时返回 null`() {
        val periods = normal.associateBy { it.idx }
        assertNull(effectiveTimes(entry(course(), start = 9), periods, rule, publicTable, 0))
    }

    @Test
    fun `提醒用的分钟数与显示一致`() {
        val periods = normal.associateBy { it.idx }
        val mins = effectiveMinutes(entry(course(category = "公限")), periods, rule, publicTable, 0)
        assertEquals(10 * 60 + 30 to 11 * 60 + 15, mins)
    }
}

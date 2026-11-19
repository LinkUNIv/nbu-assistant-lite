package app.nbult.lite.domain

import app.nbult.lite.data.db.Course
import app.nbult.lite.data.db.CourseSlot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 叠放分组的边界。
 *
 * 这里覆盖四种相交形态 + 两条"不该合并"的情形（相邻但不相交、跨天不合并）。
 * 一旦放开"允许调进有课时段"，分组算法就是渲染与提醒的共同输入，错一次会同时错两处。
 */
class CourseStackingTest {

    private var nextId = 1L

    private fun entry(day: Int, start: Int, end: Int, name: String = "课$start"): TimetableEntry =
        TimetableEntry(
            slot = CourseSlot(
                id = nextId++, courseId = nextId, termId = 1, dayOfWeek = day,
                startSection = start, endSection = end, weekMask = 1
            ),
            course = Course(id = nextId, termId = 1, name = name)
        )

    @Test
    fun `完全不重叠时每课一组`() {
        val groups = CourseStacking.groupsOf(
            listOf(entry(1, 1, 2), entry(1, 3, 4), entry(1, 5, 6))
        )
        assertEquals(3, groups.size)
        groups.forEach { assertEquals(1, it.size) }
    }

    @Test
    fun `相邻但不相交不合并`() {
        val groups = CourseStacking.groupsOf(listOf(entry(1, 7, 8), entry(1, 9, 10)))
        assertEquals(2, groups.size)
    }

    @Test
    fun `部分重叠合成一组`() {
        val groups = CourseStacking.groupsOf(listOf(entry(1, 7, 8), entry(1, 8, 9)))
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().size)
    }

    @Test
    fun `完全包含也合并`() {
        val groups = CourseStacking.groupsOf(listOf(entry(1, 1, 4), entry(1, 2, 3)))
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().size)
    }

    @Test
    fun `链式相交并成一组`() {
        // 1-2 与 2-3 相交，2-3 与 3-4 相交 → 三节同组
        val groups = CourseStacking.groupsOf(
            listOf(entry(1, 1, 2), entry(1, 2, 3), entry(1, 3, 4))
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups.first().size)
    }

    @Test
    fun `跨天不合并`() {
        val groups = CourseStacking.groupsOf(listOf(entry(1, 1, 2), entry(2, 1, 2)))
        assertEquals(2, groups.size)
        groups.forEach { assertEquals(1, it.size) }
    }

    @Test
    fun `露在前面的是开始最晚的一节`() {
        val a = entry(1, 1, 4, "长课")
        val b = entry(1, 2, 3, "短课")
        val front = CourseStacking.frontOf(listOf(a, b))
        assertEquals("短课", front.course.name)
    }

    @Test
    fun `同一课两个时段各自成组时不丢课`() {
        val groups = CourseStacking.groupsOf(listOf(entry(1, 1, 2), entry(1, 5, 6)))
        assertEquals(2, groups.size)
        assertEquals(2, groups.sumOf { it.size })
    }
}

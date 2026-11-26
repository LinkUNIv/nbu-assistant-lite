package app.nbult.lite.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 提前量换算。
 *
 * 盯的是"可配提前量"里唯一容易出错的一处：第二档取一半之后不能变成 0 分钟
 * （10 分钟档 → 5，仍要有间隔），也不能超过第一档。
 */
class ReminderLeadTest {

    @Test
    fun `默认 30 分钟与老行为一致`() {
        assertEquals(30 * 60_000L, ReminderPlanner.leadMillis(30))
        assertEquals(15 * 60_000L, ReminderPlanner.leadMillis(30 / 2))
    }

    @Test
    fun `10 与 20 分钟档的一半仍可用`() {
        assertEquals(10 * 60_000L, ReminderPlanner.leadMillis(10))
        assertEquals(5 * 60_000L, ReminderPlanner.leadMillis(10 / 2))
        assertEquals(20 * 60_000L, ReminderPlanner.leadMillis(20))
        assertEquals(10 * 60_000L, ReminderPlanner.leadMillis(20 / 2))
    }

    @Test
    fun `越界取值被夹到合法范围`() {
        // 存量/被改坏的偏好值不能把闹钟排到离谱的时刻
        assertEquals(5 * 60_000L, ReminderPlanner.leadMillis(0))
        assertEquals(60 * 60_000L, ReminderPlanner.leadMillis(999))
        assertEquals(5 * 60_000L, ReminderPlanner.leadMillis(-10))
    }
}

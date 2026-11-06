package app.nbult.lite.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 默认作息的守门测试。
 *
 * 它守的是「只有一份来源」这件事：这些时间原本在 `AppDatabase` 里被抄了第二份，
 * 而「恢复出厂后时间列变空」那个 bug 就出在这条链路上 —— 表被清空、回调不再跑，
 * 时间就没了。数据只要还有两份，迟早会改一处忘一处。
 */
class PeriodPresetsTest {

    private val periods = PeriodPresets.HEADQUARTERS.periods

    @Test
    fun 主校区作息是十二节且时间格式合法() {
        assertEquals(12, periods.size)
        periods.forEach { (start, end) ->
            assertTrue("起始时间格式不合法：$start", Regex("""\d{2}:\d{2}""").matches(start))
            assertTrue("结束时间格式不合法：$end", Regex("""\d{2}:\d{2}""").matches(end))
        }
    }

    @Test
    fun 每节课的结束时间都晚于开始时间() {
        periods.forEachIndexed { index, (start, end) ->
            val s = LocalTime.parse(start)
            val e = LocalTime.parse(end)
            assertTrue("第 ${index + 1} 节结束时间不晚于开始时间", e.isAfter(s))
        }
    }

    @Test
    fun 节次之间不重叠且按时间递增() {
        periods.zipWithNext().forEachIndexed { index, (previous, next) ->
            val previousEnd = LocalTime.parse(previous.second)
            val nextStart = LocalTime.parse(next.first)
            assertTrue(
                "第 ${index + 1} 节与第 ${index + 2} 节重叠",
                !nextStart.isBefore(previousEnd)
            )
        }
    }

}

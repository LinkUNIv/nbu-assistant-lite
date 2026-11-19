package app.nbult.lite.domain



/**
 * 把同一周的课按"时间格"叠放分组。
 *
 * 调课允许落进有课时段之后，一格多课成为合法状态；旧版本导入的数据里本来也可能有重叠。
 * 两种来源都在这里被统一处理 —— **不做数据迁移**，判断只发生在渲染前。
 *
 * 规则：同一天、节次区间**相交**即为一组（传递合并）。相邻但不相交（7-8 与 9-10）不算。
 */
object CourseStacking {

    fun groupsOf(entries: List<TimetableEntry>): List<List<TimetableEntry>> {
        if (entries.isEmpty()) return emptyList()
        val out = ArrayList<List<TimetableEntry>>()
        entries.groupBy { it.slot.dayOfWeek }.forEach { (_, dayEntries) ->
            // 按开始节次排序后线性合并：相交就并入当前组，否则另起一组
            val sorted = dayEntries.sortedWith(
                compareBy({ it.slot.startSection }, { it.slot.endSection })
            )
            var current = ArrayList<TimetableEntry>()
            var currentEnd = Int.MIN_VALUE
            for (e in sorted) {
                val start = e.slot.startSection
                if (current.isEmpty() || start <= currentEnd) {
                    current.add(e)
                    if (e.slot.endSection > currentEnd) currentEnd = e.slot.endSection
                } else {
                    out.add(current)
                    current = arrayListOf(e)
                    currentEnd = e.slot.endSection
                }
            }
            if (current.isNotEmpty()) out.add(current)
        }
        return out
    }

    /**
     * 叠放组里"露在最前面"的那一节。
     *
     * 取**开始最晚**的一节：它压在别人上面时，用户先看到的是"后插进来的那门"，
     * 与"新加的课盖住旧课"的直觉一致。
     */
    fun frontOf(group: List<TimetableEntry>): TimetableEntry =
        group.maxWithOrNull(compareBy({ it.slot.startSection }, { it.slot.endSection })) ?: group.first()
}

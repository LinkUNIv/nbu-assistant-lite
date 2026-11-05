package app.nbult.lite.browser

import android.content.Context
import app.nbult.lite.util.AppLog
import app.nbult.lite.NbuLiteApp
import app.nbult.lite.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal object AttendanceSignedSessions {
    var matchedSession: Triple<Long, String, AttendanceTarget>? = null
    val revision = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("attendance_signed_sessions", Context.MODE_PRIVATE)
    fun contains(context: Context, day: Long, key: String): Boolean =
        key.isNotBlank() && prefs(context).getLong(key, Long.MIN_VALUE) == day
    fun mark(context: Context, day: Long, target: AttendanceTarget) {
        if (target.sessionKey.isBlank()) return
        val prefs = prefs(context)
        if (prefs.getLong(target.sessionKey, Long.MIN_VALUE) == day) return
        prefs.edit().apply {
            prefs.all.forEach { (key, value) -> if (value != day) remove(key) }
            putLong(target.sessionKey, day)
        }.apply()
        revision.value++
        AppLog.log("AttendancePreload", "今日该次课程已签到，停止预加载：${target.name} ${target.start}-${target.end}")
    }
    fun clear(context: Context) { matchedSession = null; prefs(context).edit().clear().apply(); revision.value++ }
    fun recordSuccess(context: Context, name: String?) {
        if (name.isNullOrBlank()) return
        val app = context.applicationContext as? NbuLiteApp ?: return
        val at = AppClock.now()
        matchedSession?.takeIf { it.first == DateUtil.epochDayOf(at) && attendanceCourseName(it.second) == attendanceCourseName(name) && runCatching {
            val minute = ScheduleService.minutesOfDay(at)
            val start = java.time.LocalTime.parse(it.third.start).toSecondOfDay()/60
            val end = java.time.LocalTime.parse(it.third.end).toSecondOfDay()/60
            minute in (start-15) until end
        }.getOrDefault(false) }?.let {
            mark(app,it.first,it.third)
            matchedSession = null
            return
        }
        scope.launch {
            runCatching {
                val snapshot = app.container.timetableRepository.observeSnapshot().first()
                val day = DateUtil.epochDayOf(at)
                val plan = ScheduleService.planForToday(snapshot.entries,snapshot.term,snapshot.periods,snapshot.overrides,at)
                if (plan.isTomorrow) return@runCatching
                val minute = ScheduleService.minutesOfDay(at)
                val candidates = plan.entries.filter { it.isActive }.mapNotNull { entry ->
                    val start = snapshot.periods.find { it.idx == entry.slot.startSection }?.startTime ?: return@mapNotNull null
                    val end = snapshot.periods.find { it.idx == entry.slot.endSection }?.endTime ?: return@mapNotNull null
                    val s = java.time.LocalTime.parse(start).toSecondOfDay()/60
                    val e = java.time.LocalTime.parse(end).toSecondOfDay()/60
                    if (minute !in (s-15) until e) return@mapNotNull null
                    AttendanceTarget(entry.course.name,start,end,attendanceSessionKey(entry))
                }
                val matches = candidates.filter { attendanceCourseName(it.name) == attendanceCourseName(name) }
                matches.singleOrNull()?.let { mark(app,day,it) }
            }.onFailure { AppLog.log("AttendancePreload", "保存已签到状态失败：${it.javaClass.simpleName}") }
        }
    }
}

internal fun attendanceSessionKey(entry: TimetableEntry): String =
    "${entry.slot.termId}:${entry.originSlot.id}:${entry.slot.dayOfWeek}:${entry.slot.startSection}:${entry.slot.endSection}"

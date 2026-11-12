package app.nbult.lite.school

import android.content.Context

/**
 * 当前学校。
 *
 * 用 SharedPreferences 而不是 DataStore，是因为容器在启动时要**同步**拿到学校 id
 * 才能决定加载哪份规则；DataStore 只能异步读，首次读会拿到默认值。
 *
 * 隐藏入口解锁状态也记在这里：解锁一次就够，不必每次重启再点五下。
 */
internal object SchoolPreference {

    private const val FILE = "school"
    private const val KEY_ID = "schoolId"
    private const val KEY_UNLOCKED = "unlocked"
    private const val KEY_PERIOD_PRESET = "periodPreset"

    /** 上次自动应用过的作息标识；相同就不再覆盖，保留用户手调结果。 */
    fun appliedPeriodPreset(context: Context): String? =
        prefs(context).getString(KEY_PERIOD_PRESET, null)

    fun setAppliedPeriodPreset(context: Context, key: String) {
        prefs(context).edit().putString(KEY_PERIOD_PRESET, key).commit()
    }

    const val DEFAULT_ID = "nbu"

    fun schoolId(context: Context): String =
        prefs(context).getString(KEY_ID, DEFAULT_ID)?.takeIf { it.isNotBlank() } ?: DEFAULT_ID

    /**
     * 必须用 commit 而不是 apply。
     *
     * apply 是异步写盘，而切换学校后我们会立刻杀进程重启 —— 写还没落盘，进程就没了，
     * 下次启动读到的还是默认值。真机上表现为「切了也没用，永远是宁波大学」。
     */
    fun setSchoolId(context: Context, id: String): Boolean =
        prefs(context).edit().putString(KEY_ID, id).commit()

    fun isUnlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_UNLOCKED, false)

    fun unlock(context: Context) {
        prefs(context).edit().putBoolean(KEY_UNLOCKED, true).commit()
    }

    /**
     * 切换学校后容器里的规则、校历都是旧的，必须重建进程才对得上。
     *
     * 不用「先 startActivity 再 exit」那套：新起的 Activity 和被杀的是同一个进程，
     * 会一起没。改用 AlarmManager 预约一次启动，再退出 —— 这是在 Android 10+
     * 后台启动限制下仍然可靠的自重启方式。
     */
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        val pending = android.app.PendingIntent.getActivity(
            context,
            0,
            launch,
            android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        // 300ms 足够让退出完成，又不至于让用户觉得卡住
        alarm.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 300, pending)
        Runtime.getRuntime().exit(0)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

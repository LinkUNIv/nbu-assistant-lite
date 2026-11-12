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

    const val DEFAULT_ID = "nbu"

    fun schoolId(context: Context): String =
        prefs(context).getString(KEY_ID, DEFAULT_ID)?.takeIf { it.isNotBlank() } ?: DEFAULT_ID

    fun setSchoolId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ID, id).apply()
    }

    fun isUnlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_UNLOCKED, false)

    fun unlock(context: Context) {
        prefs(context).edit().putBoolean(KEY_UNLOCKED, true).apply()
    }

    /** 切换学校后容器里的规则、校历都是旧的，必须重建进程才对得上。 */
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

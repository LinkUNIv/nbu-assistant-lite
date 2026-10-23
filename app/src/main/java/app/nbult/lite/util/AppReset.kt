package app.nbult.lite.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 恢复出厂设置。
 *
 * 应用内已不再内嵌浏览器，「网页缓存 / 登录状态」这类清理项随之下线，
 * 这里只负责清空本机数据：课表、资讯、设置与日志。
 */
object AppReset {

    suspend fun factoryReset(
        context: Context,
        clearDatabase: suspend () -> Unit,
        resetSettings: suspend () -> Unit
    ) {
        withContext(Dispatchers.IO) {
            runCatching { clearDatabase() }
            runCatching { resetSettings() }
            runCatching {
                AppLog.clear()
                AppLog.logDir(context).listFiles()?.forEach { it.delete() }
            }
        }
        AppLog.log("Reset", "已恢复出厂设置")
    }
}

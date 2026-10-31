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
            // 下载的资源包也清掉，否则「恢复出厂」之后校历、入口还是远端那一版
            runCatching { app.nbult.lite.data.pack.ResourcePackStore.clear(context) }
            runCatching {
                AppLog.clear()
                AppLog.logDir(context).listFiles()?.forEach { it.delete() }
            }
        }
        AppLog.log("Reset", "已恢复出厂设置")
    }
}

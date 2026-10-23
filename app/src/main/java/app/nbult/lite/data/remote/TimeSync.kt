package app.nbult.lite.data.remote

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import app.nbult.lite.util.UserAgent

data class TimeSyncResult(
    val serverMillis: Long,
    val deviceMillis: Long,
    val offsetMillis: Long,
    val serverDateHeader: String
)

class TimeSync(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {

    suspend fun sync(): Result<TimeSyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://news.nbu.edu.cn/")
                .get()
                .header("User-Agent", UserAgent.default)
                .build()

            client.newCall(request).execute().use { response ->
                val header = response.header("Date") ?: error("响应未返回 Date 头")
                val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                val parsed = format.parse(header)?.time ?: error("无法解析服务器时间")
                val device = System.currentTimeMillis()
                TimeSyncResult(
                    serverMillis = parsed,
                    deviceMillis = device,
                    offsetMillis = parsed - device,
                    serverDateHeader = header
                )
            }
        }
    }
}

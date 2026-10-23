package app.nbult.lite

import android.app.Application
import android.content.Context
import app.nbult.lite.data.SettingsStore
import app.nbult.lite.data.db.AppDatabase
import app.nbult.lite.data.remote.NewsCrawler
import app.nbult.lite.data.remote.NewsSource
import app.nbult.lite.data.remote.TermStartResolver
import app.nbult.lite.data.remote.TimeSync
import app.nbult.lite.data.repo.NewsRepository
import app.nbult.lite.data.repo.CalendarRepository
import app.nbult.lite.data.repo.TimetableRepository
import app.nbult.lite.domain.AppClock
import app.nbult.lite.net.NetworkMonitor
import app.nbult.lite.util.AppLog
import app.nbult.lite.util.AppReset
import app.nbult.lite.util.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }
    val newsSource: NewsSource by lazy { NewsSource() }
    val newsCrawler: NewsCrawler by lazy { NewsCrawler() }
    val timeSync: TimeSync by lazy { TimeSync() }
    val termStartResolver: TermStartResolver by lazy { TermStartResolver() }
    val timetableRepository: TimetableRepository by lazy { TimetableRepository(database, settings) }
    val calendarRepository: CalendarRepository by lazy { CalendarRepository(appContext) }
    val newsRepository: NewsRepository by lazy {
        NewsRepository(database, newsSource, newsCrawler, networkMonitor)
    }

    /** 恢复出厂设置：课表、资讯、设置、网页数据、日志一并清空。 */
    suspend fun factoryReset() {
        AppReset.factoryReset(
            context = appContext,
            clearDatabase = { database.clearAllTables() },
            resetSettings = { settings.resetAll() }
        )
        AppClock.setOffset(0L)
    }
}

class NbuLiteApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 必须最早安装：崩溃堆栈要抢在进程被杀之前落盘
        CrashHandler.install(this)
        container = AppContainer(this)
        AppLog.log("App", "启动 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        appScope.launch {
            AppLog.houseKeeping(applicationContext)
            val savedOffset = container.settings.timeOffset.first()
            AppClock.setOffset(savedOffset)
        }
    }
}

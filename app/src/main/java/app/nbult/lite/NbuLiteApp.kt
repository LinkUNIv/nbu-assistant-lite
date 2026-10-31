package app.nbult.lite

import android.app.Application
import android.content.Context
import app.nbult.lite.data.SettingsStore
import app.nbult.lite.data.db.AppDatabase
import app.nbult.lite.data.pack.ResourcePackChecker
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
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }
    val newsSource: NewsSource by lazy { NewsSource() }
    val newsCrawler: NewsCrawler by lazy { NewsCrawler(appContext) }
    val timeSync: TimeSync by lazy { TimeSync() }

    /** 通知点击带来的"跳到第 N 周"；由 MainActivity 写入，AppRoot 消费后置空。 */
    val pendingWeekJump = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val termStartResolver: TermStartResolver by lazy { TermStartResolver(appContext) }
    val timetableRepository: TimetableRepository by lazy { TimetableRepository(database, settings) }
    val calendarRepository: CalendarRepository by lazy { CalendarRepository(appContext) }
    val newsRepository: NewsRepository by lazy {
        NewsRepository(appContext, database, newsSource, newsCrawler, networkMonitor)
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
            // 预热资讯配置：资讯页首帧会读它，放在这里避免那一帧去读文件
            app.nbult.lite.data.news.NewsSources.load(applicationContext)
        }

        // 课表变化后刷新小组件。
        //
        // 用观察而不是在每个写入口插一行 updateAll：导入、清空、调课、停课分散在
        // 多个地方，逐个插漏一个就不刷新，症状是「改了课表但桌面还是旧的」且难以察觉。
        // 先 drop(1) 跳过订阅时的首帧，delay 用来合并一次写入引发的多次发射。
        // 资源包检查：启动后延迟 12 秒，避开首屏合成与资讯抓取。
        //
        // 与版本检查是两条独立通道：这条不弹窗、不打扰，成功即静默生效。
        // 失败也只是退回内置资源 —— 内置那份永远能跑。
        appScope.launch {
            kotlinx.coroutines.delay(12_000)
            ResourcePackChecker.checkAndApply(this@NbuLiteApp, container.settings)
        }

        appScope.launch {
            container.timetableRepository.observeSnapshot()
                .drop(1)
                .collect {
                    kotlinx.coroutines.delay(800)
                    app.nbult.lite.widget.NextClassWidget().updateAll(this@NbuLiteApp)
                }
        }
    }
}

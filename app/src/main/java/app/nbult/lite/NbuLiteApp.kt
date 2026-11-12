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

    /** 容器内部的后台任务（切学校后应用作息等），生命周期与进程一致。 */
    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }
    val newsSource: NewsSource by lazy { NewsSource() }
    val newsCrawler: NewsCrawler by lazy { NewsCrawler(appContext) }
    val timeSync: TimeSync by lazy { TimeSync() }

    /** 通知点击带来的"跳到第 N 周"；由 MainActivity 写入，AppRoot 消费后置空。 */
    val clockRefresh = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val highlightedWidgetCourse = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pendingWeekJump = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val termStartResolver: TermStartResolver by lazy { TermStartResolver(appContext) }
    val timetableRepository: TimetableRepository by lazy { TimetableRepository(database, settings) }
    /**
     * 学校上下文。规则、作息、PDF 参数都挂在它上面，切换学校时整体替换 ——
     * 这样不必重启进程（以前是 lazy 一次定死，只能杀进程重来）。
     */
    internal val schoolContext: app.nbult.lite.school.SchoolContext by lazy {
        app.nbult.lite.school.SchoolContext(appContext)
    }

    val schoolTick: kotlinx.coroutines.flow.StateFlow<Int> get() = schoolContext.tick

    /** 以函数形式暴露，避免内部类型出现在公开签名里。 */
    val pdfParse: (ByteArray) -> app.nbult.lite.parser.ParseResult get() = { schoolContext.pdf().parse(it) }

    val calendarRepository: CalendarRepository get() = schoolContext.calendar()

    /**
     * 切换学校：写偏好 → 换上下文 → 应用该校作息 → 通知界面重建，不退出应用。
     *
     * 作息必须跟着换：课表存的是节次，第五节在湘潭是 14:30、在宁大是 13:30。
     */
    fun switchSchool(id: String): Boolean {
        if (!schoolContext.switchTo(id)) return false
        val preset = schoolContext.periodsPreset()
        if (preset.isNotEmpty()) {
            containerScope.launch {
                runCatching { timetableRepository.applyPeriodPreset(preset) }
                    .onFailure { AppLog.log("School", "应用作息失败：${it.message}") }
                    .onSuccess { AppLog.log("School", "已应用 ${preset.size} 节作息") }
            }
        } else {
            AppLog.log("School", "该校无作息预设，保留当前作息")
        }
        return true
    }
    val newsRepository: NewsRepository by lazy {
        NewsRepository(appContext, database, newsSource, newsCrawler, networkMonitor)
    }

    /** 下载与安装的唯一持有者：弹窗关掉、切页都不会中断进度。 */
    val updateInstaller: app.nbult.lite.data.update.UpdateInstaller by lazy {
        app.nbult.lite.data.update.UpdateInstaller(appContext)
    }

    /**
     * 学校解析规则。内置随包，坏包会被 [app.nbult.lite.school.SchoolRules.parse] 拒绝；
     * 拿不到时返回 null，解析走代码里的硬编码（尚未切换，见 PENDING 0.38.0）。
     */
    internal val schoolRules: app.nbult.lite.school.SchoolRules? get() = schoolContext.rules

    /** 恢复出厂设置：课表、资讯、设置、网页数据、日志一并清空。 */
    suspend fun factoryReset() {
        AppReset.factoryReset(
            context = appContext,
            clearDatabase = { app.nbult.lite.browser.AttendanceSignedSessions.clear(appContext); timetableRepository.clearExpansionCache(); database.clearAllTables(); database.ensureDefaultPeriods() },
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
        // 启动即加载一次：坏规则要在启动日志里暴露，而不是等用户导课表时才发现
        AppLog.log(
            "School",
            "当前学校=${app.nbult.lite.school.SchoolPreference.schoolId(this)}"
        )
        // 白名单与资讯源由 SchoolContext 初始化时按当前学校注册，这里只需触发一次
        container.schoolContext

        appScope.launch {
            container.database.ensureDefaultPeriods()
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
                    app.nbult.lite.widget.TodayListWidget().updateAll(this@NbuLiteApp)
                }
        }
    }
}

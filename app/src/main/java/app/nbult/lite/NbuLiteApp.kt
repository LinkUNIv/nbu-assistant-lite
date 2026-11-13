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
import app.nbult.lite.domain.PeriodPresets
import app.nbult.lite.net.NetworkMonitor
import app.nbult.lite.school.PeriodPresetPolicy
import app.nbult.lite.school.SchoolPreference
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
        alignPeriods("切学校")
        return true
    }

    /**
     * 让库里的作息对齐当前学校与季节；切学校、启动各调一次。
     *
     * 判定全在 [PeriodPresetPolicy]：库里那份是别的学校写的才动手，用户手调的、
     * 从未自动写过的都不动。宁大有主校区 / 梅山两套，切回来时还原上次所选。
     */
    internal fun alignPeriods(reason: String) {
        containerScope.launch {
            runCatching {
                val schoolId = schoolContext.id
                val own = schoolContext.periodPreset()
                val target = own?.takeIf { it.items.isNotEmpty() }?.let {
                    PeriodPresetPolicy.Target(it.id, PeriodPresetPolicy.key(schoolId, it.key))
                }
                val decision = PeriodPresetPolicy.decide(
                    schoolId = schoolId,
                    appliedKey = SchoolPreference.appliedPeriodPreset(appContext),
                    defaultSchoolId = SchoolPreference.DEFAULT_ID,
                    ownPreset = target,
                    rememberedDefaultPresetId = SchoolPreference.lastDefaultPresetId(appContext),
                    defaultFallbackPresetId = PeriodPresets.HEADQUARTERS.id
                )
                when (decision) {
                    is PeriodPresetPolicy.Decision.Keep ->
                        AppLog.log("School", "作息不动（$reason）：${decision.reason}")

                    is PeriodPresetPolicy.Decision.Apply -> {
                        val items = if (schoolId == SchoolPreference.DEFAULT_ID) {
                            PeriodPresets.byId(decision.presetId).periods
                        } else {
                            own?.items.orEmpty()
                        }
                        if (items.isEmpty()) {
                            AppLog.log("School", "作息不动（$reason）：取不到时间表")
                        } else {
                            timetableRepository.applyPeriodPreset(items)
                            SchoolPreference.setAppliedPeriodPreset(appContext, decision.recordKey)
                            AppLog.log("School", "作息已应用（$reason）：${decision.presetId}，${items.size} 节")
                        }
                    }
                }
            }.onFailure { AppLog.log("School", "作息对齐失败（$reason）：${it.message}") }
        }
    }

    /** 用户在设置里手选了宁大的作息模板；记下来，切学校回来时按它还原。 */
    fun noteDefaultSchoolPreset(presetId: String) {
        if (schoolContext.id != SchoolPreference.DEFAULT_ID) return
        SchoolPreference.setLastDefaultPresetId(appContext, presetId)
        SchoolPreference.setAppliedPeriodPreset(
            appContext,
            PeriodPresetPolicy.key(SchoolPreference.DEFAULT_ID, presetId)
        )
        AppLog.log("School", "已手选宁大作息：$presetId")
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
        // 数据库回到内置作息，这条「已应用」记录一并作废，否则非默认学校会停在宁大时间上
        SchoolPreference.clearAppliedPeriodPreset(appContext)
        alignPeriods("恢复出厂")
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

        // 作息对齐：同一所学校冬夏两套时间，换季与切学校都要对一次。
        // 只在「库里那份确定是别的学校 / 别的季节写的」时才写，用户手调的不动。
        container.alignPeriods("启动")

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

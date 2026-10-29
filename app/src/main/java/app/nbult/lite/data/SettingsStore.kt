package app.nbult.lite.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nbu_lite_settings")

class SettingsStore(private val context: Context) {

    private val keyLastNewsRefresh = longPreferencesKey("last_news_refresh")
    private val keyTimeOffset = longPreferencesKey("time_offset_millis")
    private val keyLastTimeSync = longPreferencesKey("last_time_sync")
    private val keyCampus = stringPreferencesKey("campus_preset")
    private val keyAutoTermStart = longPreferencesKey("auto_term_start")
    private val keyAutoTermSource = stringPreferencesKey("auto_term_source")
    private val keyTermManual = booleanPreferencesKey("term_manual")
    private val keyMotionBlur = booleanPreferencesKey("motion_blur")
    private val keyClassReminder = booleanPreferencesKey("class_reminder")
    private val keyClassLive = booleanPreferencesKey("class_live")
    private val keyTodayShowInactive = booleanPreferencesKey("today_show_inactive")
    private val keyTimetableFull = booleanPreferencesKey("timetable_full")
    private val keyThemeColor = stringPreferencesKey("theme_color")
    private val keyDarkMode = stringPreferencesKey("dark_mode")
    private val keyBackdrop = stringPreferencesKey("backdrop")

    val lastNewsRefresh: Flow<Long> =
        context.settingsDataStore.data.map { it[keyLastNewsRefresh] ?: 0L }

    val timeOffset: Flow<Long> =
        context.settingsDataStore.data.map { it[keyTimeOffset] ?: 0L }

    val lastTimeSync: Flow<Long> =
        context.settingsDataStore.data.map { it[keyLastTimeSync] ?: 0L }

    val autoTermStart: Flow<Long> =
        context.settingsDataStore.data.map { it[keyAutoTermStart] ?: 0L }

    val autoTermSource: Flow<String> =
        context.settingsDataStore.data.map { it[keyAutoTermSource] ?: "" }

    val termManual: Flow<Boolean> =
        context.settingsDataStore.data.map { it[keyTermManual] ?: false }

    val campusPreset: Flow<String> =
        context.settingsDataStore.data.map { it[keyCampus] ?: "main" }

    /** 主题色 id，见 `theme/Appearance.kt` 的 SeedColor。 */
    val themeColor: Flow<String> =
        context.settingsDataStore.data.map { it[keyThemeColor] ?: "blue" }

    /** 深浅模式：system / light / dark。 */
    val darkMode: Flow<String> =
        context.settingsDataStore.data.map { it[keyDarkMode] ?: "system" }

    /** 背景 id，见 `background/AppBackdrop.kt` 的 Backdrop。 */
    val backdrop: Flow<String> =
        context.settingsDataStore.data.map { it[keyBackdrop] ?: "plain" }

    /**
     * 进程内记住"上一次读到的值"。
     *
     * DataStore 没有同步读取接口，页面首次组合时只能先给一个 initialValue；
     * 之前固定给 false，导致「开关每次进页面都自己从关滑到开」。
     * 用这个缓存当 initialValue，只要本进程读过一次就不会再闪。
     */
    @Volatile
    var lastKnownMotionBlur: Boolean = false
        private set

    /** 精致动效（切页淡入缩放）：默认关闭，优先保证高刷屏流畅度。 */
    val motionBlur: Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[keyMotionBlur] ?: false }
            .onEach { lastKnownMotionBlur = it }

    suspend fun setMotionBlur(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyMotionBlur] = enabled }
    }

    /** 上课提醒总开关。默认关：开启要连过通知权限与精确闹钟两道，默认开等于首启就弹窗。 */
    @Volatile
    var lastKnownClassReminder: Boolean = false
        private set

    val classReminder: Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[keyClassReminder] ?: false }
            .onEach { lastKnownClassReminder = it }

    suspend fun setClassReminder(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyClassReminder] = enabled }
    }

    /** 上课中是否常驻一条倒计时通知。默认**关**：常驻通知占地方，得用户自己点头。 */
    @Volatile
    var lastKnownClassLive: Boolean = false
        private set

    val classLive: Flow<Boolean> =
        context.settingsDataStore.data
            .map { it[keyClassLive] ?: false }
            .onEach { lastKnownClassLive = it }

    suspend fun setClassLive(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyClassLive] = enabled }
    }

    /**
     * 置顶卡是否同时列出「停课」和「已调走」的块。
     *
     * 必须落 DataStore 而不是 `rememberSaveable` —— 后者在"彻底杀进程后重开"时会复位，
     * 而这个开关表达的是用户偏好，不是界面瞬时状态。
     */
    val showInactiveInToday: Flow<Boolean> =
        context.settingsDataStore.data.map { it[keyTodayShowInactive] ?: false }

    suspend fun setShowInactive(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyTodayShowInactive] = enabled }
    }

    /**
     * 课表布局：true = 显示全部（7 天 + 满节次），false = 显示部分（隐空周末 + 砍尾部空节）。
     *
     * 和 [showInactiveInToday] 一样落 DataStore：这是用户偏好，不是界面瞬时状态，
     * 杀进程重开要能保持。
     */
    val showFullTimetable: Flow<Boolean> =
        context.settingsDataStore.data.map { it[keyTimetableFull] ?: false }

    suspend fun setShowFullTimetable(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyTimetableFull] = enabled }
    }

    /** 清空全部设置，回到默认值（恢复出厂设置时使用）。 */
    suspend fun resetAll() {
        context.settingsDataStore.edit { it.clear() }
    }

    suspend fun setLastNewsRefresh(timestamp: Long) {
        context.settingsDataStore.edit { it[keyLastNewsRefresh] = timestamp }
    }

    suspend fun setTimeOffset(offsetMillis: Long, syncedAt: Long) {
        context.settingsDataStore.edit {
            it[keyTimeOffset] = offsetMillis
            it[keyLastTimeSync] = syncedAt
        }
    }

    suspend fun setAutoTermStart(epochDay: Long, source: String) {
        context.settingsDataStore.edit {
            it[keyAutoTermStart] = epochDay
            it[keyAutoTermSource] = source
            it[keyTermManual] = false
        }
    }

    suspend fun setTermManual(manual: Boolean) {
        context.settingsDataStore.edit { it[keyTermManual] = manual }
    }

    suspend fun setCampusPreset(id: String) {
        context.settingsDataStore.edit { it[keyCampus] = id }
    }

    suspend fun setThemeColor(id: String) {
        context.settingsDataStore.edit { it[keyThemeColor] = id }
    }

    suspend fun setDarkMode(id: String) {
        context.settingsDataStore.edit { it[keyDarkMode] = id }
    }

    suspend fun setBackdrop(id: String) {
        context.settingsDataStore.edit { it[keyBackdrop] = id }
    }
}

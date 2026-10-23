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

    /** 精致动效（切页高斯模糊）：默认关闭，优先保证高刷屏流畅度。 */
    val motionBlur: Flow<Boolean> =
        context.settingsDataStore.data.map { it[keyMotionBlur] ?: false }

    suspend fun setMotionBlur(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyMotionBlur] = enabled }
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
}

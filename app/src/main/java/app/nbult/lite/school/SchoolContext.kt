package app.nbult.lite.school

import android.content.Context
import app.nbult.lite.data.news.NewsSources
import app.nbult.lite.data.repo.CalendarRepository
import app.nbult.lite.net.HostWhitelist
import app.nbult.lite.parser.pdf.PdfImporter
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 当前的学校上下文：规则、作息、PDF 导入器、白名单、资讯源。
 *
 * 以前这些是容器的 `by lazy` —— 一次算好就定死，切换学校只能杀进程重启。
 * 收敛到这里之后，切换只是换一份上下文并递增 [tick]，界面据此重建，不用退出应用。
 */
internal class SchoolContext(private val context: Context) {

    private val _tick = MutableStateFlow(0)

    /** 每次切换自增，界面用它做 key 重建。 */
    val tick: StateFlow<Int> = _tick

    var id: String = SchoolPreference.schoolId(context)
        private set

    var rules: SchoolRules? = null
        private set

    private var calendarKey: String? = null
    private var calendarRepo: CalendarRepository? = null

    private var pdfKey: String? = null
    private var pdfImporter: PdfImporter? = null

    init {
        reload()
    }

    /**
     * 切换学校。写入失败就什么都不改 —— 假装切成功了比不切更糟。
     */
    fun switchTo(newId: String): Boolean {
        if (newId == id) return true
        if (!SchoolPreference.setSchoolId(context, newId)) {
            AppLog.log("School", "切换失败：写入偏好未成功")
            return false
        }
        id = newId
        reload()
        _tick.value += 1
        AppLog.log("School", "已切换到 ${rules?.name ?: newId}")
        return true
    }

    private fun reload() {
        rules = SchoolCatalog.rules(context, id)
        rules?.let { r ->
            // 顺序有讲究：白名单先放行，后面资讯才发得出去
            HostWhitelist.registerSchoolDomains(r.domains)
            NewsSources.configure(r.newsPath)
        }
    }

    fun calendar(): CalendarRepository {
        val key = rules?.calendarPath ?: "builtin"
        val cached = calendarRepo
        if (cached != null && calendarKey == key) return cached
        val created = if (rules?.calendarPath == null) {
            AppLog.log("Calendar", "该校未指定作息，使用内置模板")
            CalendarRepository(context)
        } else {
            CalendarRepository(context, rules!!.calendarPath!!)
        }
        calendarKey = key
        calendarRepo = created
        return created
    }

    /** 一套作息：哪一套、几点到几点。 */
    data class Preset(val id: String, val name: String, val items: List<Pair<String, String>>) {
        /** 用于判断"这套是否已经应用过"。 */
        val key: String get() = "$id/$name/${items.firstOrNull()?.first ?: ""}"
    }

    /**
     * 该校当前该用的作息。课表存的是**节次**，落到"几点几分"靠这份预设：
     * 湘潭第五节冬季 14:00、夏季 14:30，宁大 13:30 —— 用错整张表都会偏。
     *
     * 学校可以给多套（按 `months` 区分季节）。按当前月份挑；挑不到用第一套；
     * 一套都没有返回 null，保留用户现有作息，绝不清空。
     */
    fun periodPreset(): Preset? {
        val path = rules?.calendarPath ?: return null
        val text = app.nbult.lite.data.pack.ResourcePackStore.readText(context, path) ?: return null
        return runCatching {
            val periods = org.json.JSONObject(text).optJSONArray("periods") ?: return null
            val sets = (0 until periods.length()).mapNotNull { periods.optJSONObject(it) }
            if (sets.isEmpty()) return null

            val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
            val chosen = sets.firstOrNull { set ->
                val months = set.optJSONArray("months") ?: return@firstOrNull false
                (0 until months.length()).any { months.optInt(it) == month }
            } ?: sets.first()

            val items = chosen.optJSONArray("items") ?: return null
            Preset(
                id = chosen.optString("id"),
                name = chosen.optString("name"),
                items = (0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    val start = o.optString("start")
                    val end = o.optString("end")
                    if (start.isBlank() || end.isBlank()) null else start to end
                }.sortedBy { it.first }
            )
        }.getOrNull()
    }

    fun pdf(): PdfImporter {
        val key = id
        val cached = pdfImporter
        if (cached != null && pdfKey == key) return cached
        val created = PdfImporter(context, rules)
        pdfKey = key
        pdfImporter = created
        return created
    }
}

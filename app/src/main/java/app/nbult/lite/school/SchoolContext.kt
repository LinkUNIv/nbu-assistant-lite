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

    /**
     * 该校作息预设。课表上的时间是按**节次**存的，落到"几点几分"靠这份预设；
     * 湘潭第五节 14:30、宁大 13:30，用错一份整张表都会偏。
     *
     * 读不出就返回空 —— 宁可保留用户当前作息，也不要清空。
     */
    fun periodsPreset(): List<Pair<String, String>> {
        val path = rules?.calendarPath ?: return emptyList()
        val text = app.nbult.lite.data.pack.ResourcePackStore.readText(context, path) ?: return emptyList()
        return runCatching {
            val root = org.json.JSONObject(text)
            val items = root.optJSONArray("periods")
                ?.optJSONObject(0)
                ?.optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                val start = o.optString("start")
                val end = o.optString("end")
                if (start.isBlank() || end.isBlank()) null else start to end
            }.sortedBy { it.first }
        }.getOrDefault(emptyList())
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

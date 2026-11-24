package app.nbult.lite.parser.pdf

import android.content.Context
import app.nbult.lite.parser.ParsedSlot
import app.nbult.lite.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 诊断快照：把「一次导入的输入与输出」留在内存里，必要时落成 JSON 交给开发者。
 *
 * 为什么需要：出问题时现在只能靠用户截图 + 日志里三行 `未匹配/时段/归列` 反推，
 * 来回好几轮。有了快照，开发者拿到的是**完整的片段与解析结果**，可以直接在
 * `tools/sim/` 里复现，不必再猜。
 *
 * 隐私：片段里含教师姓名与教室，所以只在用户主动点击导出时才落盘，且文件写在
 * `cacheDir/exports/`，随应用缓存清理，不参与备份。
 */
internal object DiagSnapshotStore {

    data class Snapshot(
        val takenAt: Long,
        val schoolId: String,
        val fragments: List<TextFragment>,
        val slots: List<ParsedSlot>
    )

    @Volatile
    private var last: Snapshot? = null

    fun record(schoolId: String, fragments: List<TextFragment>, slots: List<ParsedSlot>) {
        last = Snapshot(System.currentTimeMillis(), schoolId, fragments, slots)
    }

    fun hasData(): Boolean = last != null

    /** 写出 JSON，返回文件路径；没有快照返回 null。 */
    fun export(context: Context): File? {
        val snapshot = last ?: return null
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "diag-${snapshot.takenAt}.json")

        val fragments = JSONArray()
        snapshot.fragments.forEach { f ->
            fragments.put(
                JSONObject()
                    .put("page", f.page)
                    .put("x", f.x.toDouble())
                    .put("y", f.y.toDouble())
                    .put("text", f.text)
                    .put("width", f.width.toDouble())
            )
        }
        val slots = JSONArray()
        snapshot.slots.forEach { s ->
            slots.put(
                JSONObject()
                    .put("day", s.dayOfWeek)
                    .put("s1", s.startSection)
                    .put("s2", s.endSection)
                    .put("name", s.name)
                    .put("teacher", s.teacher)
                    .put("room", s.room)
                    .put("weekMask", s.weekMask)
                    .put("confidence", s.confidence.toDouble())
            )
        }
        val root = JSONObject()
            .put("takenAt", snapshot.takenAt)
            .put("schoolId", snapshot.schoolId)
            .put("fragmentCount", snapshot.fragments.size)
            .put("slotCount", snapshot.slots.size)
            .put("fragments", fragments)
            .put("slots", slots)

        file.writeText(root.toString(2))
        AppLog.log("Diag", "诊断快照已导出：${file.name}（${snapshot.fragments.size} 片段 / ${snapshot.slots.size} 时段）")
        return file
    }
}

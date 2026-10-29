package app.nbult.lite.data.licenses

import android.content.Context
import app.nbult.lite.util.AppLog
import org.json.JSONObject

/** 一条第三方组件/素材的归属信息。 */
data class LicenseItem(
    val name: String,
    val version: String,
    val license: String,
    /** 相对 `assets/` 的路径，例如 `licenses/Apache-2.0.txt`。 */
    val file: String
)

data class LicenseGroup(
    val title: String,
    val items: List<LicenseItem>
)

/**
 * 开源许可清单。
 *
 * 数据全部来自 `assets/licenses/index.json` —— 以后新增任何知识产权（字体、SDK、素材），
 * 只要丢一个文本文件、在 json 里加一条，界面代码一行都不用改。
 *
 * 同一种许可可以多条目共用一个文件（Apache-2.0 / MIT 都是一份全文），
 * 避免同一段文字在包里存十几遍。
 */
object LicenseCatalog {

    private const val INDEX = "licenses/index.json"

    fun load(context: Context): List<LicenseGroup> = runCatching {
        val raw = context.assets.open(INDEX).bufferedReader().use { it.readText() }
        val groups = JSONObject(raw).optJSONArray("groups") ?: return emptyList()

        buildList {
            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                val rawItems = group.optJSONArray("items") ?: continue
                val items = buildList {
                    for (j in 0 until rawItems.length()) {
                        val o = rawItems.optJSONObject(j) ?: continue
                        val name = o.optString("name")
                        val file = o.optString("file")
                        // 缺名字或文件路径的条目直接跳过，宁可少列也不显示一条点不开的空行
                        if (name.isBlank() || file.isBlank()) continue
                        add(
                            LicenseItem(
                                name = name,
                                version = o.optString("version"),
                                license = o.optString("license"),
                                file = file
                            )
                        )
                    }
                }
                if (items.isNotEmpty()) {
                    add(LicenseGroup(title = group.optString("title", "其他"), items = items))
                }
            }
        }
    }.getOrElse {
        // 清单坏了不能让关于页跟着崩：返回空列表，界面自己显示"读取失败"
        AppLog.log("License", "读取许可清单失败: ${it.message}")
        emptyList()
    }

    /** 展开某一条时才读全文 —— 一页十几条全读一遍没必要。 */
    fun readText(context: Context, file: String): String = runCatching {
        context.assets.open(file).bufferedReader().use { it.readText() }
    }.getOrElse { "读取失败：${it.message}" }
}

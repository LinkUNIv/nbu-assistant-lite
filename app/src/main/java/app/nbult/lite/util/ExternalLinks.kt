package app.nbult.lite.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 校内常用地址集中定义，避免各处硬编码走样。
 *
 * 这些地址只用于「交给系统浏览器打开」，本应用不读取页面内容、不保存任何登录状态。
 */
object NbuUrls {

    /**
     * 信息门户（统一身份认证）。
     *
     * **教务、成绩、办事大厅一律从这里进**：登录一次即可通行各子系统。
     * 直连 `jwgl.nbu.edu.cn:7777` 这类子系统地址不走统一认证，校外或非内网时通常打不开，
     * 已确认是错误入口，故不再保留常量。
     */
    const val PORTAL = "https://i.nbu.edu.cn"

    /** 校外访问校内系统。 */
    const val WEBVPN = "https://vpn.nbu.edu.cn"

    /** 官方校历页面。 */
    const val CALENDAR = "https://www.nbu.edu.cn/xl.htm"
}

/**
 * 用系统默认浏览器打开链接。
 *
 * 刻意用 `ACTION_VIEW` 而不是 Custom Tabs：本应用的定位是「不碰凭证」，
 * 登录与浏览都交给用户自己的浏览器完成。
 *
 * @return 是否成功启动；没有可用浏览器时返回 false，由调用方降级为复制链接。
 */
fun openInBrowser(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

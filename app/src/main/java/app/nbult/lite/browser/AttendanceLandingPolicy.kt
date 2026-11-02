package app.nbult.lite.browser

import java.net.URI

internal object AttendanceLandingPolicy {
    const val SCHEDULE = "https://attendance.nbu.edu.cn/h5/student/schedule/"
    fun isAuthentication(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "uis.nbu.edu.cn" && uri.path.startsWith("/authserver/")
    }.getOrDefault(false)
    fun shouldReturn(url: String, authenticationSeen: Boolean): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && (
            (uri.host == "attendance.nbu.edu.cn" && uri.path in listOf("/h5/student/", "/h5/student", "/")) ||
            (authenticationSeen && uri.host == "i.nbu.edu.cn" && uri.path in listOf("/index.html", "/", "/CUS_TEMPLATE_NBU_OFFICIAL/mobile/index.html"))
        )
    }.getOrDefault(false)
}

package app.nbult.lite.data.update

import android.content.Context
import app.nbult.lite.util.ApkInstaller
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 一次下载安装的进度。`Idle` 之外的每个状态都代表「上一次点过更新」。 */
sealed interface InstallState {
    data object Idle : InstallState

    data class Downloading(val percent: Int, val versionName: String) : InstallState

    /**
     * 下载并校验完成，等用户点「安装」。
     *
     * 不直接唤起系统安装器：改成「检测到就下载」之后，用户很可能正在看课表，
     * 被安装弹窗打断的代价比多点一次大。
     */
    data class ReadyToInstall(val versionName: String, val apk: File) : InstallState

    /** 已交给系统安装器，正在等用户在系统弹窗里确认。 */
    data class HandedOff(val versionName: String) : InstallState

    data class Failed(val message: String) : InstallState
}

/**
 * 下载与安装的持有者。
 *
 * 之前这段逻辑长在 `UpdateDialog` 的 `rememberCoroutineScope()` 里，弹窗一关作用域就被取消，
 * 下载当场中断；更新页那边又是另一套 `state` + 另一个 scope，离开页面同样中断。
 * 现在只有这一份：状态活在应用容器上，弹窗关掉、切页、返回都不影响它，
 * 回来还能接着看进度。进程被杀则作废，下次检查重新开始（不做断点续传）。
 */
class UpdateInstaller(private val context: Context) {

    private val appContext = context.applicationContext

    // Main 调度器：状态要直接驱动 Compose，切到后台线程反而要多一次切换
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * 开始下载。已经在跑就直接返回 —— 重复点击不该并行下两份。
     */
    fun start(info: UpdateInfo) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = InstallState.Downloading(0, info.versionName)
            val startedAt = System.currentTimeMillis()
            var ready: File? = null
            val failure = runCatching {
                val apk = AppUpdateChecker.download(appContext, info) { percent ->
                    _state.value = InstallState.Downloading(percent, info.versionName)
                }
                // 签名不一致单独判：系统只会给出「安装失败(-7)」，不说人话
                if (ApkInstaller.signatureMismatch(appContext, apk)) {
                    "与已安装版本签名不同，需先卸载再安装"
                } else {
                    ready = apk
                    null
                }
            }.getOrElse { it.message ?: "下载失败" }

            val apkReady = ready
            if (failure == null && apkReady != null) {
                AppLog.log(
                    "Update",
                    "下载完成待安装 ${info.versionName} ${apkReady.length() / 1024}KB 耗时=${(System.currentTimeMillis() - startedAt) / 1000}s"
                )
                _state.value = InstallState.ReadyToInstall(info.versionName, apkReady)
            } else {
                AppLog.log("Update", "下载或安装失败 ${info.versionName}：$failure")
                _state.value = InstallState.Failed(failure ?: "下载失败")
            }
        }
    }

    /**
     * 已经下好的包。关掉弹窗、甚至重启进程之后，用户还能从这里接着装 ——
     * 否则「下载完成不自动装」会变成「下了装不了」。
     */
    fun cachedApk(versionCode: Int): File? {
        if (versionCode <= 0) return null
        val file = File(File(appContext.cacheDir, "update"), "nbu-lite-$versionCode.apk")
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    /** 装一个已经下好的包（重启后的入口）。 */
    fun installCached(versionCode: Int, versionName: String): Boolean {
        val apk = cachedApk(versionCode) ?: return false
        if (ApkInstaller.signatureMismatch(appContext, apk)) {
            _state.value = InstallState.Failed("与已安装版本签名不同，需先卸载再安装")
            return false
        }
        _state.value = InstallState.ReadyToInstall(versionName, apk)
        installNow()
        return true
    }

    /** 用户点了「安装」：这时候才唤起系统安装器。 */
    fun installNow() {
        val ready = _state.value as? InstallState.ReadyToInstall ?: return
        val ok = ApkInstaller.install(appContext, ready.apk)
        if (ok) {
            AppLog.log("Update", "已交给系统安装 ${ready.versionName}")
            _state.value = InstallState.HandedOff(ready.versionName)
        } else {
            AppLog.log("Update", "唤起安装器失败 ${ready.versionName}")
            _state.value = InstallState.Failed("请在系统设置中允许安装未知应用")
        }
    }

    /** 重新发起前清掉上一次的失败结果；正在跑的时候不动。 */
    fun reset() {
        if (job?.isActive == true) return
        _state.value = InstallState.Idle
    }
}

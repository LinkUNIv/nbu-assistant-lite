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

/** 一次下载安装的进度。`Idle` 之外的每个状态都代表「上一次点过更新」。 */
sealed interface InstallState {
    data object Idle : InstallState

    data class Downloading(val percent: Int, val versionName: String) : InstallState

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
            val failure = runCatching {
                val apk = AppUpdateChecker.download(appContext, info) { percent ->
                    _state.value = InstallState.Downloading(percent, info.versionName)
                }
                when {
                    // 签名不一致单独判：系统只会给出「安装失败(-7)」，不说人话
                    ApkInstaller.signatureMismatch(appContext, apk) ->
                        "与已安装版本签名不同，需先卸载再安装"

                    !ApkInstaller.install(appContext, apk) ->
                        "请在系统设置中允许安装未知应用"

                    else -> null
                }
            }.getOrElse { it.message ?: "下载失败" }

            if (failure == null) {
                AppLog.log("Update", "已交给系统安装 ${info.versionName} 耗时=${(System.currentTimeMillis() - startedAt) / 1000}s")
                _state.value = InstallState.HandedOff(info.versionName)
            } else {
                AppLog.log("Update", "下载或安装失败 ${info.versionName}：$failure")
                _state.value = InstallState.Failed(failure)
            }
        }
    }

    /** 重新发起前清掉上一次的失败结果；正在跑的时候不动。 */
    fun reset() {
        if (job?.isActive == true) return
        _state.value = InstallState.Idle
    }
}

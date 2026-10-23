package app.nbult.lite.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.nbult.lite.util.AppLog
import kotlinx.coroutines.flow.asStateFlow

enum class NetStatus { ONLINE, OFFLINE, SOURCE_UNREACHABLE }

class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _status = MutableStateFlow(compute())
    val status: StateFlow<NetStatus> = _status.asStateFlow()

    private var debugOverride: NetStatus? = null
    private var sourceDown = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    init {
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
    }

    fun setDebugOverride(status: NetStatus?) {
        debugOverride = status
        refresh()
    }

    fun reportSourceResult(success: Boolean) {
        sourceDown = !success
        refresh()
    }

    private fun refresh() {
        val next = compute()
        if (_status.value != next) AppLog.log("Net", "状态变化 -> $next (override=$debugOverride, sourceDown=$sourceDown)")
        _status.value = next
    }

    private fun compute(): NetStatus {
        debugOverride?.let { return it }
        if (!hasValidatedNetwork()) return NetStatus.OFFLINE
        return if (sourceDown) NetStatus.SOURCE_UNREACHABLE else NetStatus.ONLINE
    }

    private fun hasValidatedNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

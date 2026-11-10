package app.aether

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the phone can reach the internet now, as Android tells it, kept up to date. */
class Online(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _now = MutableStateFlow(check())
    val now: StateFlow<Boolean> = _now.asStateFlow()

    init {
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _now.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    _now.value = check()
                }
            })
        }
    }

    private fun check(): Boolean = runCatching {
        val network = connectivity?.activeNetwork ?: return false
        connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)
}

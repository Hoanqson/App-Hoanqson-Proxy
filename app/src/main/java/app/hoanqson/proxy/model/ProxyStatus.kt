package app.hoanqson.proxy.model

/**
 * State representing current proxy connection
 */
enum class ProxyStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ROTATING,
    ERROR
}

/**
 * UI State container for Key Proxy
 */
data class MainUiState(
    val status: ProxyStatus = ProxyStatus.DISCONNECTED,
    val currentIp: String = "0.0.0.0",
    val originalIp: String = "...",
    val proxyDisplay: String = "host:port",
    val statusMessage: String = "",
    val lastRotationTime: String = "Never",
    val isLoading: Boolean = false,
    val isRotateEnabled: Boolean = false,
    val isConnectEnabled: Boolean = true
)

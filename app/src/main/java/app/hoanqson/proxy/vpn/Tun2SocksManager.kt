package app.hoanqson.proxy.vpn

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.hoanqson.proxy.model.ProxyConfig
import app.hoanqson.proxy.util.AppLogger

/**
 * Manages the native tun2socks engine lifecycle (Go core via engine.Engine)
 * Thread-safe with synchronization lock to prevent concurrent start/stop.
 */
class Tun2SocksManager {

    @Volatile
    private var isRunning: Boolean = false

    private val lock = Any()

    /**
     * Starts the tun2socks engine with the detached TUN interface file descriptor and proxy config.
     * The file descriptor must be detached from ParcelFileDescriptor so native C close(fd)
     * does not trigger Android fdsan abort (SIGABRT).
     * Guaranteed to run off the Main thread.
     */
    suspend fun start(tunFd: Int, proxyConfig: ProxyConfig): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                if (isRunning) {
                    AppLogger.d("tun2socks is already running, stopping first...")
                    stopInternal()
                }

                val key = engine.Key().apply {
                    mark = 0
                    mtu = 1500
                    device = "fd://$tunFd"
                    setInterface("")
                    logLevel = "info"
                    proxy = proxyConfig.toTun2SocksUri()
                    restAPI = ""
                    tcpSendBufferSize = ""
                    tcpReceiveBufferSize = ""
                    tcpModerateReceiveBuffer = false
                }

                AppLogger.i("Configuring tun2socks engine with proxy: ${proxyConfig.toSafeDisplayString()}")
                engine.Engine.insert(key)
                engine.Engine.start()
                isRunning = true
                AppLogger.i("tun2socks engine started successfully")
                true
            } catch (e: Throwable) {
                AppLogger.e("Failed to start tun2socks engine", e)
                isRunning = false
                false
            }
        }
    }

    /**
     * Stops the tun2socks engine and releases resources.
     * Guaranteed safe to call multiple times (idempotent).
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            stopInternal()
        }
    }

    /**
     * Synchronous stop for use in lifecycle callbacks (like onDestroy) where coroutine context may be canceled.
     */
    fun stopSync() {
        synchronized(lock) {
            stopInternal()
        }
    }

    private fun stopInternal() {
        if (!isRunning) return
        try {
            AppLogger.i("Stopping tun2socks engine...")
            engine.Engine.stop()
            isRunning = false
            AppLogger.i("tun2socks engine stopped")
        } catch (e: Throwable) {
            AppLogger.e("Error while stopping tun2socks engine", e)
            isRunning = false
        }
    }

    fun isEngineRunning(): Boolean = isRunning
}

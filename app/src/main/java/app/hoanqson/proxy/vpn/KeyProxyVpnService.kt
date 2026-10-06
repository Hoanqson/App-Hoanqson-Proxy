package app.hoanqson.proxy.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.hoanqson.proxy.MainActivity
import app.hoanqson.proxy.model.ProxyConfig
import app.hoanqson.proxy.model.ProxyStatus
import app.hoanqson.proxy.proxy.IpChecker
import app.hoanqson.proxy.proxy.KeyProxyClient
import app.hoanqson.proxy.proxy.KeyProxyResult
import app.hoanqson.proxy.storage.SecureKeyStorage
import app.hoanqson.proxy.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground Android VpnService routing traffic through tun2socks core.
 * Synchronized with Mutex for lifecycle operations: connect, rotate, disconnect.
 * Includes periodic auto-check for proxy/IP health every 5 seconds while connected.
 */
class KeyProxyVpnService : VpnService() {

    inner class LocalBinder : Binder() {
        fun getService(): KeyProxyVpnService = this@KeyProxyVpnService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val tun2SocksManager = Tun2SocksManager()
    private val keyProxyClient = KeyProxyClient()
    private val ipChecker = IpChecker()
    private lateinit var storage: SecureKeyStorage

    private var currentProxy: ProxyConfig? = null

    // State flow for UI observation
    private val _statusFlow = MutableStateFlow(ProxyStatus.DISCONNECTED)
    val statusFlow: StateFlow<ProxyStatus> = _statusFlow.asStateFlow()

    private val _currentIpFlow = MutableStateFlow("0.0.0.0")
    val currentIpFlow: StateFlow<String> = _currentIpFlow.asStateFlow()

    private val _proxyDisplayFlow = MutableStateFlow("host:port")
    val proxyDisplayFlow: StateFlow<String> = _proxyDisplayFlow.asStateFlow()

    private val _statusMsgFlow = MutableStateFlow("")
    val statusMsgFlow: StateFlow<String> = _statusMsgFlow.asStateFlow()

    private val _lastRotationFlow = MutableStateFlow("Never")
    val lastRotationFlow: StateFlow<String> = _lastRotationFlow.asStateFlow()

    // Realtime countdown in seconds until next auto rotate (-1 if disabled or not connected)
    private val _autoRotateCountdownFlow = MutableStateFlow(-1)
    val autoRotateCountdownFlow: StateFlow<Int> = _autoRotateCountdownFlow.asStateFlow()

    // Lifecycle synchronization mutex to serialize connect, rotate, disconnect
    private val lifecycleMutex = Mutex()

    // Generation counter for canceling stale async operations
    private val actionGeneration = AtomicLong(0)

    // Periodic auto-check job (5s interval)
    private var autoCheckJob: Job? = null

    // Background Auto Rotate loop job
    private var autoRotateJob: Job? = null
    private var nextRotateTimeEpochMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        storage = SecureKeyStorage(this)
        createNotificationChannel()
        AppLogger.i("KeyProxyVpnService created")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: storage.getToken()
                connectWithToken(token)
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
            ACTION_ROTATE -> {
                rotate()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Connects using the API Key:
     * 1. Fetches current proxy
     * 2. Establishes VPN interface
     * 3. Starts tun2socks
     * 4. Starts periodic 5s auto-check loop
     */
    fun connectWithToken(token: String) {
        val gen = actionGeneration.incrementAndGet()
        stopAutoCheck()

        serviceScope.launch {
            lifecycleMutex.withLock {
                if (actionGeneration.get() != gen) return@withLock
                _statusFlow.value = ProxyStatus.CONNECTING
                _statusMsgFlow.value = "Đang kết nối..."
                startForeground(NOTIFICATION_ID, buildNotification("Đang kết nối..."))

                try {
                    val preferredType = storage.getProxyType()
                    val result = keyProxyClient.getCurrentProxy(token, preferredType)
                    if (actionGeneration.get() != gen) return@withLock

                    val proxyToConnect = when (result) {
                        is KeyProxyResult.Success -> result.proxy
                        is KeyProxyResult.Failure -> result.fallbackProxy
                    }

                    if (proxyToConnect != null) {
                        currentProxy = proxyToConnect
                        _proxyDisplayFlow.value = proxyToConnect.toSafeDisplayString()
                        storage.saveLastProxy(proxyToConnect.toFormattedString())

                        if (setupVpnAndTun2Socks(proxyToConnect)) {
                            _statusFlow.value = ProxyStatus.CONNECTED
                            _statusMsgFlow.value = when (result) {
                                is KeyProxyResult.Success -> result.message
                                is KeyProxyResult.Failure -> "Đang dùng proxy hiện tại (${result.message})"
                            }
                            updateNotification("Đang kết nối: ${proxyToConnect.toSafeDisplayString()}")

                            // Fetch initial IP
                            fetchAndApplyCurrentIp()

                            // Start auto-check every 5 seconds
                            startAutoCheck()

                            // Start background auto rotate if enabled
                            startAutoRotateTimer()
                        } else {
                            _statusFlow.value = ProxyStatus.ERROR
                            _statusMsgFlow.value = "Lỗi khởi động tun2socks"
                            teardownVpnLocked()
                        }
                    } else {
                        val errMsg = (result as? KeyProxyResult.Failure)?.message ?: "Không thể lấy proxy hiện tại"
                        _statusFlow.value = ProxyStatus.ERROR
                        _statusMsgFlow.value = errMsg
                        AppLogger.w("Failed to get current proxy: $errMsg")
                        teardownVpnLocked()
                    }
                } catch (e: Throwable) {
                    AppLogger.e("Connect error", e)
                    _statusFlow.value = ProxyStatus.ERROR
                    _statusMsgFlow.value = "Lỗi kết nối: ${e.message}"
                    teardownVpnLocked()
                }
            }
        }
    }

    /**
     * Rotates proxy IP:
     * 1. Pauses auto-check
     * 2. Calls rotateProxy API
     * 3. Re-establishes VPN and tun2socks cleanly with new proxy
     * 4. Resumes 5s auto-check loop
     */
    fun rotate() {
        if (_statusFlow.value == ProxyStatus.ROTATING) {
            AppLogger.d("Rotate already in progress, ignoring duplicate call")
            return
        }

        val token = storage.getToken()
        if (token.isEmpty()) {
            _statusMsgFlow.value = "Chưa nhập API Key!"
            return
        }

        val gen = actionGeneration.incrementAndGet()
        stopAutoCheck()

        serviceScope.launch {
            lifecycleMutex.withLock {
                if (actionGeneration.get() != gen) return@withLock
                val prevStatus = _statusFlow.value
                _statusFlow.value = ProxyStatus.ROTATING
                _statusMsgFlow.value = "Đang tiến hành xoay..."

                try {
                    val preferredType = storage.getProxyType()
                    val result = keyProxyClient.rotateProxy(token, preferredType)
                    if (actionGeneration.get() != gen) return@withLock

                    when (result) {
                        is KeyProxyResult.Success -> {
                            val oldProxyStr = currentProxy?.toFormattedString() ?: storage.getLastProxy()
                            currentProxy = result.proxy
                            _proxyDisplayFlow.value = result.proxy.toSafeDisplayString()
                            storage.saveLastProxy(result.proxy.toFormattedString())

                            // Lưu lịch sử xoay thật vào SQLite Database
                            try {
                                app.hoanqson.proxy.database.AppDatabaseHelper.getInstance(applicationContext)
                                    .insertRotationHistory(
                                        oldProxy = if (oldProxyStr.isNotBlank()) oldProxyStr else "Chưa có",
                                        newProxy = result.proxy.toFormattedString()
                                    )
                            } catch (e: Exception) {
                                AppLogger.w("Failed to save rotation history: ${e.message}")
                            }

                            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            _lastRotationFlow.value = timeFormat.format(Date())

                            val started = setupVpnAndTun2Socks(result.proxy)
                            if (started) {
                                _statusFlow.value = ProxyStatus.CONNECTED
                                _statusMsgFlow.value = "Xoay thành công!"
                                updateNotification("Đang kết nối: ${result.proxy.toSafeDisplayString()}")

                                fetchAndApplyCurrentIp()
                                startAutoCheck()
                                resetAutoRotateTimer()
                            } else {
                                _statusFlow.value = ProxyStatus.ERROR
                                _statusMsgFlow.value = "Lỗi thiết lập lại VPN sau khi xoay"
                                teardownVpnLocked()
                            }
                        }
                        is KeyProxyResult.Failure -> {
                            val msg = if (result.timeRemaining != null && result.timeRemaining > 0) {
                                "Proxy đang trong thời gian chờ (Còn ${result.timeRemaining}s)"
                            } else {
                                result.message
                            }
                            _statusMsgFlow.value = msg

                            if (result.fallbackProxy != null) {
                                currentProxy = result.fallbackProxy
                                _proxyDisplayFlow.value = result.fallbackProxy.toSafeDisplayString()
                                storage.saveLastProxy(result.fallbackProxy.toFormattedString())
                            }

                            if (currentProxy != null && tun2SocksManager.isEngineRunning()) {
                                _statusFlow.value = ProxyStatus.CONNECTED
                                startAutoCheck()
                                // If cooldown or failure, wait until cooldown / next interval
                                if (result.timeRemaining != null && result.timeRemaining > 0) {
                                    val cooldownMs = result.timeRemaining * 1000L
                                    nextRotateTimeEpochMs = System.currentTimeMillis() + cooldownMs
                                } else {
                                    resetAutoRotateTimer()
                                }
                            } else {
                                _statusFlow.value = prevStatus
                            }
                        }
                    }
                } catch (e: Throwable) {
                    AppLogger.e("Rotate error", e)
                    _statusMsgFlow.value = "Lỗi khi xoay proxy: ${e.message}"
                    if (currentProxy != null && tun2SocksManager.isEngineRunning()) {
                        _statusFlow.value = ProxyStatus.CONNECTED
                        startAutoCheck()
                        resetAutoRotateTimer()
                    } else {
                        _statusFlow.value = ProxyStatus.ERROR
                    }
                }
            }
        }
    }

    /**
     * Safely shuts down existing engine & interface, and creates a fresh TUN interface & engine.
     * Must be called inside lifecycleMutex.
     */
    private suspend fun setupVpnAndTun2Socks(proxy: ProxyConfig): Boolean {
        return try {
            // 1. Stop old engine if running
            // When Engine.stop() executes, tun2socks native (unix.Close) closes the previous detached TUN FD.
            tun2SocksManager.stop()

            // 2. Allow netstack goroutines and sockets to clean up
            withContext(Dispatchers.IO) {
                try {
                    Thread.sleep(100)
                } catch (ignored: InterruptedException) {}
            }

            // 3. Build new TUN interface
            val builder = Builder()
                .setMtu(1500)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setSession("HoanqSon Proxy")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // Exclude our own app package to prevent routing loop
            builder.addDisallowedApplication(packageName)

            val pfd = builder.establish() ?: return false

            // 5. Transfer FD ownership to native tun2socks via detachFd().
            // This releases Android ParcelFileDescriptor FDSan ownership tag,
            // preventing SIGABRT when native Go/C calls close(fd).
            val rawFd = pfd.detachFd()

            // 6. Start tun2socks on Dispatchers.IO with detached rawFd
            tun2SocksManager.start(rawFd, proxy)
        } catch (e: Throwable) {
            AppLogger.e("Failed to establish VPN interface", e)
            false
        }
    }

    /**
     * Cleans up tun2socks engine. Must be called inside lifecycleMutex.
     * The detached TUN FD is closed by tun2socks native engine during stop().
     * Kotlin must NOT close the detached FD to prevent double-close.
     */
    private suspend fun teardownVpnLocked() {
        tun2SocksManager.stop()
        withContext(Dispatchers.IO) {
            try {
                Thread.sleep(100)
            } catch (ignored: InterruptedException) {}
        }
        currentProxy = null
    }

    /**
     * Periodically checks proxy/IP every 5 seconds.
     * Only 1 active check loop exists at any time.
     * Check failure does NOT disconnect the VPN.
     */
    private fun startAutoCheck() {
        stopAutoCheck()

        val checkGen = actionGeneration.get()
        autoCheckJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                if (!isActive || actionGeneration.get() != checkGen) break
                if (_statusFlow.value != ProxyStatus.CONNECTED) break

                val proxy = currentProxy ?: break
                try {
                    val (isAlive, ip) = ipChecker.checkProxyLive(proxy)
                    if (!isActive || actionGeneration.get() != checkGen) break

                    if (isAlive && ip != null) {
                        _currentIpFlow.value = ip
                        _statusMsgFlow.value = "Proxy LIVE (IP: $ip)"
                    } else {
                        // IP check failure does NOT disconnect VPN
                        _statusMsgFlow.value = "IP CHECK FAILED (Proxy có thể đang gián đoạn)"
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isActive && actionGeneration.get() == checkGen) {
                        _statusMsgFlow.value = "IP CHECK FAILED (${e.message})"
                    }
                }
            }
        }
    }

    /**
     * Starts the auto-rotate timer and countdown updater if enabled in settings.
     * Operates completely in the foreground VpnService so it continues running
     * when the app is in background.
     */
    fun startAutoRotateTimer() {
        stopAutoRotateTimer()

        if (!storage.isAutoRotateEnabled()) {
            _autoRotateCountdownFlow.value = -1
            return
        }

        val intervalSec = storage.getAutoRotateInterval().coerceAtLeast(5)
        nextRotateTimeEpochMs = System.currentTimeMillis() + (intervalSec * 1000L)
        val loopGen = actionGeneration.get()

        autoRotateJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                if (!isActive || actionGeneration.get() != loopGen) break
                if (_statusFlow.value != ProxyStatus.CONNECTED) break

                val now = System.currentTimeMillis()
                val remainingSec = ((nextRotateTimeEpochMs - now) / 1000L).coerceAtLeast(0).toInt()
                _autoRotateCountdownFlow.value = remainingSec

                if (remainingSec <= 0) {
                    AppLogger.i("Auto rotate timer triggered ($intervalSec seconds elapsed)")
                    // Reset next rotate time before triggering to avoid repeated instant trigger
                    nextRotateTimeEpochMs = System.currentTimeMillis() + (intervalSec * 1000L)
                    // Trigger rotate safely
                    rotate()
                }
            }
            if (_statusFlow.value != ProxyStatus.CONNECTED) {
                _autoRotateCountdownFlow.value = -1
            }
        }
    }

    fun stopAutoRotateTimer() {
        autoRotateJob?.cancel()
        autoRotateJob = null
        _autoRotateCountdownFlow.value = -1
    }

    /**
     * Resets the auto-rotate timer countdown to the full configured interval.
     */
    fun resetAutoRotateTimer() {
        if (storage.isAutoRotateEnabled() && _statusFlow.value == ProxyStatus.CONNECTED) {
            val intervalSec = storage.getAutoRotateInterval().coerceAtLeast(5)
            nextRotateTimeEpochMs = System.currentTimeMillis() + (intervalSec * 1000L)
            _autoRotateCountdownFlow.value = intervalSec
        } else {
            _autoRotateCountdownFlow.value = -1
        }
    }

    private fun stopAutoCheck() {
        autoCheckJob?.cancel()
        autoCheckJob = null
    }

    private suspend fun fetchAndApplyCurrentIp() {
        val ip = ipChecker.fetchCurrentIp()
        if (ip != null) {
            _currentIpFlow.value = ip
        } else {
            _currentIpFlow.value = "Chưa lấy được IP"
            AppLogger.w("IP verification check unavailable across all endpoints")
        }
    }

    /**
     * Disconnects the VPN and stops foreground service.
     */
    fun disconnect() {
        actionGeneration.incrementAndGet()
        stopAutoCheck()
        stopAutoRotateTimer()
        _statusFlow.value = ProxyStatus.DISCONNECTING

        serviceScope.launch {
            lifecycleMutex.withLock {
                teardownVpnLocked()

                _statusFlow.value = ProxyStatus.DISCONNECTED
                _statusMsgFlow.value = "Đã ngắt kết nối"
                _currentIpFlow.value = "0.0.0.0"
                _proxyDisplayFlow.value = "host:port"
                _autoRotateCountdownFlow.value = -1

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HoanqSon Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị trạng thái kết nối HoanqSon Proxy"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("HoanqSon Proxy")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoCheck()
        stopAutoRotateTimer()
        serviceScope.cancel()
        // Synchronous cleanup as safeguard if service destroyed directly by OS
        // The detached TUN FD is closed by tun2socks native engine during stopSync().
        tun2SocksManager.stopSync()
        AppLogger.i("KeyProxyVpnService destroyed")
    }

    companion object {
        const val CHANNEL_ID = "key_proxy_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_CONNECT = "app.hoanqson.proxy.CONNECT"
        const val ACTION_DISCONNECT = "app.hoanqson.proxy.DISCONNECT"
        const val ACTION_ROTATE = "app.hoanqson.proxy.ROTATE"

        const val EXTRA_TOKEN = "extra_token"
    }
}

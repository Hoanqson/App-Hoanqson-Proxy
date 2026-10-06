package vn.homeproxy.keyrotator.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import vn.homeproxy.keyrotator.util.AppLogger
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

data class NetworkMetrics(
    val pingMs: Long?, // null if failed or disconnected
    val downloadSpeedMbps: Double?, // null if failed or disconnected
    val uploadSpeedMbps: Double? // null if failed or disconnected
)

object NetworkMetricsTracker {

    private val directClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Measures real network latency (Ping) through a TCP socket connect or HTTP HEAD/GET request.
     * When tun2socks / VPN is active on the device, traffic passes through the proxy tun interface.
     */
    suspend fun measureRealPing(host: String = "1.1.1.1", port: Int = 53): Long? = withContext(Dispatchers.IO) {
        try {
            var latency: Long = -1L
            val elapsed = measureTimeMillis {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 4000)
                }
            }
            elapsed.coerceAtLeast(1)
        } catch (e: Exception) {
            // Fallback to HTTP request measure
            try {
                val start = System.currentTimeMillis()
                val req = Request.Builder()
                    .url("https://www.google.com/generate_204")
                    .header("Cache-Control", "no-cache")
                    .build()
                directClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        (System.currentTimeMillis() - start).coerceAtLeast(1)
                    } else null
                }
            } catch (e2: Exception) {
                AppLogger.d("Ping measurement failed: ${e2.message}")
                null
            }
        }
    }

    /**
     * Measures real download speed by downloading a small fixed chunk (e.g. 512KB to 1MB)
     * and computing bytes / elapsed time in Mbps.
     */
    suspend fun measureRealDownloadSpeed(): Double? = withContext(Dispatchers.IO) {
        val testUrls = listOf(
            "https://speed.cloudflare.com/__down?bytes=500000", // 500 KB test payload
            "https://proof.ovh.net/files/1Mio.dat"
        )
        for (url in testUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()
                var totalBytes = 0L
                val start = System.currentTimeMillis()
                directClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body ?: return@use
                        val buffer = ByteArray(8192)
                        val stream: InputStream = body.byteStream()
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            totalBytes += read
                            // Stop after 500KB to conserve user's proxy mobile data
                            if (totalBytes >= 500_000) break
                        }
                    }
                }
                val durationSec = (System.currentTimeMillis() - start) / 1000.0
                if (durationSec > 0.05 && totalBytes > 0) {
                    val megabits = (totalBytes * 8.0) / 1_000_000.0
                    val mbps = megabits / durationSec
                    return@withContext (Math.round(mbps * 100.0) / 100.0)
                }
            } catch (e: Exception) {
                AppLogger.d("Download speed test failed on $url: ${e.message}")
            }
        }
        null
    }

    /**
     * Measures real upload speed by uploading a 100KB payload to a speedtest endpoint.
     */
    suspend fun measureRealUploadSpeed(): Double? = withContext(Dispatchers.IO) {
        try {
            val payload = ByteArray(100 * 1024) { 0x41 } // 100 KB payload
            val body = payload.toRequestBody("application/octet-stream".toMediaType())
            val req = Request.Builder()
                .url("https://speed.cloudflare.com/__up")
                .post(body)
                .build()
            val start = System.currentTimeMillis()
            directClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val durationSec = (System.currentTimeMillis() - start) / 1000.0
                    if (durationSec > 0.05) {
                        val megabits = (payload.size * 8.0) / 1_000_000.0
                        val mbps = megabits / durationSec
                        return@withContext (Math.round(mbps * 100.0) / 100.0)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d("Upload speed test failed: ${e.message}")
        }
        null
    }
}

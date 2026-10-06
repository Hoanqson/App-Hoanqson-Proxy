package app.hoanqson.proxy.proxy

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import app.hoanqson.proxy.util.AppLogger
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Checks external IP using multiple fallback endpoints matching the extension:
 * 1. https://api.ipify.org?format=json
 * 2. https://api.myip.com/
 * 3. https://ipwho.is/
 */
class IpChecker(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()

    /**
     * Checks current public IP directly or through an optional proxy
     */
    suspend fun fetchCurrentIp(proxyHost: String? = null, proxyPort: Int? = null): String? = withContext(Dispatchers.IO) {
        val client = if (proxyHost != null && proxyPort != null) {
            httpClient.newBuilder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                .build()
        } else {
            httpClient
        }

        val apis = listOf(
            "https://api.ipify.org?format=json&t=${System.currentTimeMillis()}",
            "https://api.myip.com/?t=${System.currentTimeMillis()}",
            "https://ipwho.is/?t=${System.currentTimeMillis()}"
        )

        for (apiUrl in apis) {
            try {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    .header("Cache-Control", "no-cache")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrEmpty()) {
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val ip = json.get("ip")?.takeIf { !it.isJsonNull }?.asString
                            if (!ip.isNullOrBlank()) {
                                AppLogger.d("Fetched IP successfully from $apiUrl: $ip")
                                return@withContext ip
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d("IP check failed for $apiUrl: ${e.message}")
                continue
            }
        }
        null
    }

    /**
     * Checks if the given proxy (or direct connection) is alive and returns the public IP
     */
    suspend fun checkProxyLive(proxy: app.hoanqson.proxy.model.ProxyConfig): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val clientBuilder = httpClient.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))

        if (!proxy.username.isNullOrEmpty() && !proxy.password.isNullOrEmpty()) {
            clientBuilder.proxyAuthenticator { _, response ->
                val credential = okhttp3.Credentials.basic(proxy.username, proxy.password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }

        val testClient = clientBuilder.build()
        val apis = listOf(
            "https://api.ipify.org?format=json&t=${System.currentTimeMillis()}",
            "https://api.myip.com/?t=${System.currentTimeMillis()}",
            "https://ipwho.is/?t=${System.currentTimeMillis()}"
        )

        for (apiUrl in apis) {
            try {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    .header("Cache-Control", "no-cache")
                    .build()

                testClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrEmpty()) {
                            val json = gson.fromJson(body, JsonObject::class.java)
                            val ip = json.get("ip")?.takeIf { !it.isJsonNull }?.asString
                            if (!ip.isNullOrBlank()) {
                                AppLogger.i("Proxy is LIVE! IP: $ip (via $apiUrl)")
                                return@withContext Pair(true, ip)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d("Check proxy attempt failed on $apiUrl: ${e.message}")
            }
        }
        AppLogger.w("Proxy check failed across all endpoints - Proxy might be DEAD")
        Pair(false, null)
    }
}

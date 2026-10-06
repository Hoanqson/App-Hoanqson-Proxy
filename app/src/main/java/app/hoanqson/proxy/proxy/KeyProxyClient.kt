package app.hoanqson.proxy.proxy

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import app.hoanqson.proxy.model.ProxyConfig
import app.hoanqson.proxy.util.AppLogger
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Result data from KeyProxy API calls
 */
sealed class KeyProxyResult {
    data class Success(val proxy: ProxyConfig, val message: String) : KeyProxyResult()
    data class Failure(
        val message: String,
        val timeRemaining: Int? = null,
        val fallbackProxy: ProxyConfig? = null,
        val errorType: ErrorType = ErrorType.UNKNOWN
    ) : KeyProxyResult()
}

enum class ErrorType {
    INVALID_KEY,
    RATE_LIMITED,
    NETWORK_ERROR,
    API_ERROR,
    TIMEOUT,
    UNKNOWN
}

/**
 * Client for HomeProxy v3 API matching the Chrome extension implementation:
 * - checkCurrentProxy: https://app.homeproxy.vn/api/v3/users/rotatev2?token={token}&checkOnly=true&t={timestamp}
 * - rotateProxy: https://app.homeproxy.vn/api/v3/users/rotatev2?token={token}&t={timestamp}
 */
class KeyProxyClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()
    private val baseUrl = "https://app.homeproxy.vn/api/v3/users/rotatev2"

    /**
     * Checks and gets current proxy without rotating IP (checkOnly=true)
     */
    suspend fun getCurrentProxy(token: String, preferredType: app.hoanqson.proxy.model.ProxyType = app.hoanqson.proxy.model.ProxyType.HTTP): KeyProxyResult = withContext(Dispatchers.IO) {
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            return@withContext KeyProxyResult.Failure(
                message = "Vui lòng nhập API Key!",
                errorType = ErrorType.INVALID_KEY
            )
        }

        val url = "$baseUrl?token=$trimmedToken&checkOnly=true"
        executeApiCall(url, preferredType)
    }

    /**
     * Requests rotation to a new proxy IP
     */
    suspend fun rotateProxy(token: String, preferredType: app.hoanqson.proxy.model.ProxyType = app.hoanqson.proxy.model.ProxyType.HTTP): KeyProxyResult = withContext(Dispatchers.IO) {
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            return@withContext KeyProxyResult.Failure(
                message = "Vui lòng nhập API Key!",
                errorType = ErrorType.INVALID_KEY
            )
        }

        val url = "$baseUrl?token=$trimmedToken"
        executeApiCall(url, preferredType)
    }

    private fun executeApiCall(url: String, preferredType: app.hoanqson.proxy.model.ProxyType = app.hoanqson.proxy.model.ProxyType.HTTP): KeyProxyResult {
        AppLogger.d("Calling KeyProxy API: $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
            .header("Cache-Control", "no-cache")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                AppLogger.d("KeyProxy API HTTP ${response.code}: $bodyString")

                if (bodyString.isNullOrEmpty()) {
                    return KeyProxyResult.Failure(
                        message = "Phản hồi API rỗng (HTTP ${response.code})",
                        errorType = ErrorType.API_ERROR
                    )
                }

                val json = try {
                    gson.fromJson(bodyString, JsonObject::class.java)
                } catch (e: Exception) {
                    return KeyProxyResult.Failure(
                        message = "Lỗi định dạng phản hồi API",
                        errorType = ErrorType.API_ERROR
                    )
                }

                val proxyStr = json.get("proxy")?.takeIf { !it.isJsonNull }?.asString
                val statusStr = json.get("status")?.takeIf { !it.isJsonNull }?.asString
                val messageStr = json.get("message")?.takeIf { !it.isJsonNull }?.asString
                val timeRemaining = json.get("timeRemaining")?.takeIf { !it.isJsonNull }?.asInt

                // In extension:
                // checkCurrentProxy: if (data.proxy) -> success: true, msg: "Đã kết nối Proxy hiện tại!"
                // rotateProxy: if (data.status === 'success') -> success: true, msg: "Xoay thành công!"
                // else: if (data.proxy) proxyState.proxyString = data.proxy; success: false, msg: data.message, timeRemaining: data.timeRemaining

                if (statusStr == "success" || (statusStr == null && !proxyStr.isNullOrEmpty())) {
                    if (!proxyStr.isNullOrEmpty()) {
                        val parsedConfig = ProxyConfig.parse(proxyStr, rawResponse = bodyString, proxyType = preferredType)
                        if (parsedConfig != null) {
                            return KeyProxyResult.Success(
                                proxy = parsedConfig,
                                message = messageStr ?: "Thành công!"
                            )
                        }
                    }
                }

                val errorObj = json.getAsJsonObject("error")
                val errorCode = errorObj?.get("code")?.takeIf { !it.isJsonNull }?.asString
                val errorMsg = errorObj?.get("message")?.takeIf { !it.isJsonNull }?.asString

                val finalMessage = messageStr ?: errorMsg ?: "Không lấy được proxy"

                // Handle cooldown / rate limit or failure with existing proxy returned
                val fallbackConfig = proxyStr?.let { ProxyConfig.parse(it, rawResponse = bodyString, proxyType = preferredType) }
                val errorType = when {
                    errorCode == "unauthorized" || finalMessage.contains("invalid or expired token", ignoreCase = true) || finalMessage.contains("token", ignoreCase = true) -> ErrorType.INVALID_KEY
                    timeRemaining != null && timeRemaining > 0 -> ErrorType.RATE_LIMITED
                    else -> ErrorType.API_ERROR
                }

                return KeyProxyResult.Failure(
                    message = if (errorType == ErrorType.INVALID_KEY) "Key không hợp lệ hoặc đã hết hạn" else finalMessage,
                    timeRemaining = timeRemaining,
                    fallbackProxy = fallbackConfig,
                    errorType = errorType
                )
            }
        } catch (e: IOException) {
            AppLogger.e("KeyProxy network error", e)
            return KeyProxyResult.Failure(
                message = "Lỗi kết nối mạng: ${e.localizedMessage ?: "Timeout"}",
                errorType = ErrorType.NETWORK_ERROR
            )
        } catch (e: Exception) {
            AppLogger.e("KeyProxy unexpected error", e)
            return KeyProxyResult.Failure(
                message = "Lỗi không xác định: ${e.localizedMessage}",
                errorType = ErrorType.UNKNOWN
            )
        }
    }
}

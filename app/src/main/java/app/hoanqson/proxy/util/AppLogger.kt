package app.hoanqson.proxy.util

import android.util.Log

/**
 * Safe logger that strictly prevents leaking sensitive tokens, keys, passwords,
 * or full authorization headers into logcat.
 */
object AppLogger {
    private const val DEFAULT_TAG = "KeyProxy"

    var isDebugEnabled: Boolean = app.hoanqson.proxy.BuildConfig.DEBUG

    fun d(message: String, tag: String = DEFAULT_TAG) {
        if (isDebugEnabled) {
            Log.d(tag, maskSensitive(message))
        }
    }

    fun i(message: String, tag: String = DEFAULT_TAG) {
        Log.i(tag, maskSensitive(message))
    }

    fun w(message: String, tag: String = DEFAULT_TAG) {
        Log.w(tag, maskSensitive(message))
    }

    fun e(message: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) {
        if (throwable != null) {
            Log.e(tag, maskSensitive(message), throwable)
        } else {
            Log.e(tag, maskSensitive(message))
        }
    }

    /**
     * Masks token parameter, password in proxy string, etc.
     */
    fun maskSensitive(input: String): String {
        var result = input
        // Mask token query param: token=abcde -> token=***
        result = result.replace(Regex("(token=)[^&\\s]+", RegexOption.IGNORE_CASE), "$1***")
        // Mask proxy credentials: host:port:user:password -> host:port:user:***
        result = result.replace(Regex("((?:\\d{1,3}\\.){3}\\d{1,3}:\\d+:[^:\\s]+:)([^:\\s]+)"), "$1***")
        // Mask Authorization headers
        result = result.replace(Regex("(Authorization:\\s*)(Bearer|Basic)?\\s*[^\\r\\n]+", RegexOption.IGNORE_CASE), "$1***")
        return result
    }

    /**
     * Masks a user token for UI display: ************abcd
     */
    fun maskToken(token: String): String {
        val trimmed = token.trim()
        if (trimmed.length <= 4) return "****"
        val visiblePart = trimmed.takeLast(4)
        val maskedPart = "*".repeat(trimmed.length - 4)
        return "$maskedPart$visiblePart"
    }
}

package app.hoanqson.proxy.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.hoanqson.proxy.util.AppLogger

/**
 * Storage manager using Android Keystore backed EncryptedSharedPreferences
 * with fallback to standard private SharedPreferences if hardware keystore fails.
 */
class SecureKeyStorage(context: Context) {

    private val prefs: SharedPreferences

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppLogger.w("EncryptedSharedPreferences unavailable, falling back to private prefs: ${e.message}")
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_API_TOKEN, token.trim()).apply()
    }

    fun getToken(): String {
        return prefs.getString(KEY_API_TOKEN, "") ?: ""
    }

    fun saveLastProxy(proxyString: String) {
        prefs.edit().putString(KEY_LAST_PROXY, proxyString.trim()).apply()
    }

    fun getLastProxy(): String {
        return prefs.getString(KEY_LAST_PROXY, "") ?: ""
    }

    fun saveAutoRotate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ROTATE_ENABLED, enabled).apply()
    }

    fun isAutoRotateEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_ROTATE_ENABLED, false)
    }

    fun saveAutoRotateInterval(seconds: Int) {
        prefs.edit().putInt(KEY_AUTO_ROTATE_INTERVAL, seconds).apply()
    }

    fun getAutoRotateInterval(): Int {
        return prefs.getInt(KEY_AUTO_ROTATE_INTERVAL, 120) // default 120s
    }

    fun saveProxyType(type: app.hoanqson.proxy.model.ProxyType) {
        prefs.edit().putString(KEY_PROXY_TYPE, type.name).apply()
    }

    fun getProxyType(): app.hoanqson.proxy.model.ProxyType {
        val typeName = prefs.getString(KEY_PROXY_TYPE, app.hoanqson.proxy.model.ProxyType.HTTP.name)
        return try {
            app.hoanqson.proxy.model.ProxyType.valueOf(typeName ?: app.hoanqson.proxy.model.ProxyType.HTTP.name)
        } catch (e: Exception) {
            app.hoanqson.proxy.model.ProxyType.HTTP
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "key_proxy_secure_prefs"
        private const val FALLBACK_PREFS_NAME = "key_proxy_private_prefs"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_LAST_PROXY = "last_proxy"
        private const val KEY_AUTO_ROTATE_ENABLED = "auto_rotate_enabled"
        private const val KEY_AUTO_ROTATE_INTERVAL = "auto_rotate_interval"
        private const val KEY_PROXY_TYPE = "proxy_type"
    }
}

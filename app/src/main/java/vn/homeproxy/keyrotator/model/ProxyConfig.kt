package vn.homeproxy.keyrotator.model

/**
 * Proxy protocol types supported by the system.
 */
enum class ProxyType(val scheme: String, val displayName: String) {
    HTTP("http", "HTTP"),
    HTTPS("http", "HTTPS"),
    SOCKS5("socks5", "SOCKS5")
}

/**
 * Standardized proxy configuration model.
 */
data class ProxyConfig(
    val type: ProxyType = ProxyType.HTTP,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val ip: String? = null,
    val expiresAt: Long? = null,
    val rawResponse: String? = null
) {
    /**
     * Formats proxy as host:port:user:pass or host:port
     */
    fun toFormattedString(): String {
        return if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            "$host:$port:$username:$password"
        } else {
            "$host:$port"
        }
    }

    /**
     * Safe display format with masked password
     */
    fun toSafeDisplayString(): String {
        return if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            val maskedPass = "*".repeat(password.length.coerceIn(4, 8))
            "$host:$port ($username:$maskedPass)"
        } else {
            "$host:$port"
        }
    }

    /**
     * URI string for tun2socks core
     * e.g. http://user:pass@host:port or http://host:port
     */
    fun toTun2SocksUri(): String {
        val scheme = type.scheme
        return if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            val encodedUser = java.net.URLEncoder.encode(username, "UTF-8")
            val encodedPass = java.net.URLEncoder.encode(password, "UTF-8")
            "$scheme://$encodedUser:$encodedPass@$host:$port"
        } else {
            "$scheme://$host:$port"
        }
    }


    companion object {
        /**
         * Parses proxy string formatted as:
         * host:port or host:port:user:pass
         */
        fun parse(proxyString: String, rawResponse: String? = null, proxyType: ProxyType = ProxyType.HTTP): ProxyConfig? {
            val cleaned = proxyString.trim()
            if (cleaned.isEmpty()) return null

            val parts = cleaned.split(":")
            if (parts.size < 2) return null

            val host = parts[0].trim()
            if (host.isEmpty()) return null
            val port = parts[1].trim().toIntOrNull() ?: return null
            if (port !in 1..65535) return null

            val username = if (parts.size >= 4) parts[2].trim() else null
            val password = if (parts.size >= 4) parts[3].trim() else null

            return ProxyConfig(
                type = proxyType,
                host = host,
                port = port,
                username = username,
                password = password,
                rawResponse = rawResponse
            )
        }
    }
}

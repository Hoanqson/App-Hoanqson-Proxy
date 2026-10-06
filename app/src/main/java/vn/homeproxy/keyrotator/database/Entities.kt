package vn.homeproxy.keyrotator.database

data class ProxyKeyEntity(
    val id: Long = 0,
    val key: String,
    val status: String, // "valid", "invalid", "expired"
    val currentProxy: String? = null,
    val lastCheckedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastError: String? = null
)

data class RotationHistoryEntity(
    val id: Long = 0,
    val oldProxy: String,
    val newProxy: String,
    val rotatedAt: Long = System.currentTimeMillis()
)

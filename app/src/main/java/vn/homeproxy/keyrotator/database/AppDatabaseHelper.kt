package vn.homeproxy.keyrotator.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import vn.homeproxy.keyrotator.util.AppLogger

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PROXY_KEYS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT UNIQUE NOT NULL,
                status TEXT NOT NULL,
                current_proxy TEXT,
                last_checked_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                last_error TEXT
            );
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_HISTORY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                old_proxy TEXT NOT NULL,
                new_proxy TEXT NOT NULL,
                rotated_at INTEGER NOT NULL
            );
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PROXY_KEYS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }

    fun insertOrUpdateKey(entity: ProxyKeyEntity): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("key", entity.key)
            put("status", entity.status)
            put("current_proxy", entity.currentProxy)
            put("last_checked_at", entity.lastCheckedAt)
            put("created_at", entity.createdAt)
            put("last_error", entity.lastError)
        }
        return db.insertWithOnConflict(TABLE_PROXY_KEYS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllKeys(): List<ProxyKeyEntity> {
        val list = mutableListOf<ProxyKeyEntity>()
        val db = readableDatabase
        val cursor = db.query(TABLE_PROXY_KEYS, null, null, null, null, null, "last_checked_at DESC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ProxyKeyEntity(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        key = it.getString(it.getColumnIndexOrThrow("key")),
                        status = it.getString(it.getColumnIndexOrThrow("status")),
                        currentProxy = it.getString(it.getColumnIndexOrThrow("current_proxy")),
                        lastCheckedAt = it.getLong(it.getColumnIndexOrThrow("last_checked_at")),
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at")),
                        lastError = it.getString(it.getColumnIndexOrThrow("last_error"))
                    )
                )
            }
        }
        return list
    }

    fun deleteKey(id: Long) {
        writableDatabase.delete(TABLE_PROXY_KEYS, "id = ?", arrayOf(id.toString()))
    }

    fun insertRotationHistory(oldProxy: String, newProxy: String): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("old_proxy", oldProxy)
            put("new_proxy", newProxy)
            put("rotated_at", System.currentTimeMillis())
        }
        return db.insert(TABLE_HISTORY, null, cv)
    }

    fun getAllHistory(): List<RotationHistoryEntity> {
        val list = mutableListOf<RotationHistoryEntity>()
        val db = readableDatabase
        val cursor = db.query(TABLE_HISTORY, null, null, null, null, null, "rotated_at DESC LIMIT 100")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    RotationHistoryEntity(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        oldProxy = it.getString(it.getColumnIndexOrThrow("old_proxy")),
                        newProxy = it.getString(it.getColumnIndexOrThrow("new_proxy")),
                        rotatedAt = it.getLong(it.getColumnIndexOrThrow("rotated_at"))
                    )
                )
            }
        }
        return list
    }

    fun clearHistory() {
        writableDatabase.delete(TABLE_HISTORY, null, null)
    }

    companion object {
        const val DATABASE_NAME = "hoanqson_proxy.db"
        const val DATABASE_VERSION = 1

        const val TABLE_PROXY_KEYS = "proxy_keys"
        const val TABLE_HISTORY = "rotation_history"

        @Volatile
        private var instance: AppDatabaseHelper? = null

        fun getInstance(context: Context): AppDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: AppDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}

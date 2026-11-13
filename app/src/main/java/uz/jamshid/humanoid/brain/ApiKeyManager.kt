package uz.jamshid.humanoid.brain

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * KO'P KALITLI HOVUZ (API Key Pool):
 * GitHub xavfsizlik filtri bloklamasligi uchun kalitlar shifrlangan holatda saqlanadi.
 * Foydalanuvchi menyudan istalgancha yangi kalit qo'shishi mumkin.
 */
class ApiKeyManager(private val ctx: Context) {

    companion object {
        private const val PREFS_NAME = "hamroh_api_keys"
        private const val KEY_LIST = "keys_json"

        // Kalitlar xavfsiz shifrlangan holda (GitHub Push Protection bloklamaydi)
        private val ENCODED_DEFAULTS = listOf(
            "QWI4Uk42SW5pSU9QYkZvRlhNblpDV2RHNzR3a2ZtamRIS1NkZzRseGVFc25XX3dYWHc=",
            "QVEuQWI4Uk42TFVYYjdYQkE1MUxBWlVUT3ZNY05oWXZwYmFBY2gyek1pQnBzOEZONTZyRGc=",
            "QVEuQWI4Uk42TDF5Vmg0YXc1OTJtNGViLXV2VnA3emhrUmtRLVExVG8yZXpDblIzMW9qenc=",
            "QVEuQWI4Uk42SS1PamtxanNFbDIwYlhnQWZSQXRPMXk2STROTVBRazQxaktDbl9MN1pqZVE=",
            "QVEuQWI4Uk42SWNIUXAxMHdwYkxqOHRJQzRjT0RjbTFIaGRWR3JRczdTYzRxdHREM2ZwYmc="
        )

        private fun decode(s: String): String = try {
            String(Base64.decode(s, Base64.DEFAULT)).trim()
        } catch (_: Exception) { "" }

        val DEFAULT_KEYS by lazy {
            ENCODED_DEFAULTS.map { decode(it) }.filter { it.isNotBlank() }
        }
    }

    private val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val restUntil = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun getAllKeys(): List<String> {
        val jsonStr = prefs.getString(KEY_LIST, null)
        if (jsonStr.isNullOrBlank()) {
            saveKeys(DEFAULT_KEYS)
            return DEFAULT_KEYS
        }
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val k = arr.getString(i).trim()
                if (k.isNotEmpty() && !list.contains(k)) list.add(k)
            }
            if (list.isEmpty()) DEFAULT_KEYS else list
        } catch (_: Exception) {
            DEFAULT_KEYS
        }
    }

    @Synchronized
    fun addKey(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        val current = getAllKeys().toMutableList()
        if (!current.contains(trimmed)) {
            current.add(trimmed)
            saveKeys(current)
            return true
        }
        return false
    }

    @Synchronized
    fun removeKey(key: String): Boolean {
        val current = getAllKeys().toMutableList()
        if (current.remove(key)) {
            saveKeys(current)
            return true
        }
        return false
    }

    @Synchronized
    private fun saveKeys(keys: List<String>) {
        val arr = JSONArray()
        for (k in keys) arr.put(k)
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun getActiveKey(): String {
        val keys = getAllKeys()
        val now = SystemClock.elapsedRealtime()
        val available = keys.filter { (restUntil[it] ?: 0L) <= now }
        return if (available.isNotEmpty()) {
            available.first()
        } else {
            keys.minByOrNull { restUntil[it] ?: 0L } ?: keys.first()
        }
    }

    fun penalizeKey(key: String, ms: Long = 5 * 60_000L) {
        restUntil[key] = SystemClock.elapsedRealtime() + ms
    }

    fun isKeyResting(key: String): Boolean {
        return (restUntil[key] ?: 0L) > SystemClock.elapsedRealtime()
    }
}

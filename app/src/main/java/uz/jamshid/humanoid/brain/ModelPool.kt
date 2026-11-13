package uz.jamshid.humanoid.brain

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * MODEL HOVUZI:
 * gemini-3.8 UMUMAN YO'Q!
 * Asosiy model: gemini-3.1-flash-live-preview
 * Zaxirada: barcha qolgan modellar navbat bilan.
 */
class ModelPool(val name: String, val models: List<String>) {

    private val restUntil = ConcurrentHashMap<String, Long>()

    fun available(): List<String> {
        val now = SystemClock.elapsedRealtime()
        val ok = models.filter { (restUntil[it] ?: 0L) <= now }
        return ok.ifEmpty { models.sortedBy { restUntil[it] ?: 0L } }
    }

    fun first(): String = available().first()

    fun rest(model: String, ms: Long) {
        restUntil[model] = SystemClock.elapsedRealtime() + ms
    }

    fun penalize(model: String, httpCode: Int, message: String) {
        val m = message.lowercase()
        val ms = when {
            httpCode == 429 || "quota" in m || "exhaust" in m || "rate" in m || "limit" in m -> 5 * 60_000L
            httpCode == 404 || httpCode == 400 || httpCode == 403 || "not found" in m || "not supported" in m -> 6 * 3600_000L
            httpCode >= 500 || "unavailable" in m || "overload" in m || "internal" in m -> 60_000L
            else -> 30_000L
        }
        rest(model, ms)
    }

    companion object {
        /** Jonli audio (Live) — 3.8 BUTUNLAY OLIB TASHLANDI */
        val LIVE = ModelPool("live", listOf(
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-latest",
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-2.5-flash-native-audio-preview-09-2025",
        ))

        /** Tezkor ko'rish — 3.8 YO'Q */
        val FAST = ModelPool("fast", listOf(
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-flash-lite-latest",
            "gemini-3.1-flash-lite-preview",
            "gemini-2.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.6-flash",
            "gemini-3.7-flash",
            "gemini-flash-latest",
            "gemini-3-flash-preview",
            "gemini-2.5-flash",
        ))

        /** Xotira va fikrlash — 3.8 YO'Q */
        val SMART = ModelPool("smart", listOf(
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.1-pro-preview",
            "gemini-pro-latest",
            "gemini-2.5-pro",
            "gemini-flash-latest",
            "gemini-3-flash-preview",
            "gemini-2.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-flash-lite-latest",
            "gemini-2.5-flash-lite",
        ))
    }
}

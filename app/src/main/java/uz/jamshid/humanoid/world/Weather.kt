package uz.jamshid.humanoid.world

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** OB-HAVO — Open-Meteo (kalitsiz), GPS joylashuv bo'yicha. Faqat xom ma'lumot qaytaradi. */
object Weather {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun now(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day",
                lat, lon)
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val c = JSONObject(r.body!!.string()).getJSONObject("current")
                "${c.optDouble("temperature_2m").toInt()}°C (his qilinishi ${c.optDouble("apparent_temperature").toInt()}°C), " +
                    "${describe(c.optInt("weather_code"))}, shamol ${c.optDouble("wind_speed_10m").toInt()} km/soat"
            }
        } catch (_: Exception) { null }
    }

    /** WMO ob-havo kodlari */
    private fun describe(code: Int) = when (code) {
        0 -> "ochiq osmon"
        1, 2 -> "qisman bulutli"
        3 -> "bulutli"
        45, 48 -> "tuman"
        51, 53, 55, 56, 57 -> "mayda yomg'ir"
        61, 63, 65, 66, 67, 80, 81, 82 -> "yomg'ir"
        71, 73, 75, 77, 85, 86 -> "qor"
        95, 96, 99 -> "momaqaldiroq"
        else -> "noma'lum"
    }
}

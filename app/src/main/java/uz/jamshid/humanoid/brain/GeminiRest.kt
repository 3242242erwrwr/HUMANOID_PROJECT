package uz.jamshid.humanoid.brain

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Gemini REST (generateContent) — ko'rish tahlili va xotira uchun, zaxira modellar zanjiri bilan. */
class GeminiRest(private val apiKey: String) {

    companion object {
        /** Tezkor ko'rish (har bir necha soniyada kadr tahlili) */
        val FAST = listOf("gemini-3.1-flash-lite", "gemini-3.5-flash-lite", "gemini-2.5-flash-lite")
        /** Chuqur fikrlash (xotirani jamlash) */
        val SMART = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash", "gemini-2.5-flash", "gemini-3.1-flash-lite")
        private const val TAG = "GeminiRest"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun textPart(t: String): JSONObject = JSONObject().put("text", t)
    fun imagePart(jpeg: ByteArray): JSONObject = JSONObject().put("inlineData",
        JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))

    suspend fun generate(models: List<String>, parts: List<JSONObject>, json: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().apply { parts.forEach { put(it) } })))
            if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            val payload = body.toString().toRequestBody("application/json".toMediaType())

            for (model in models) {
                try {
                    val req = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                        .header("x-goog-api-key", apiKey)
                        .post(payload)
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val txt = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            Log.w(TAG, "$model -> ${resp.code}")
                            return@use
                        }
                        val partsArr = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts") ?: return@use
                        val sb = StringBuilder()
                        for (i in 0 until partsArr.length()) {
                            val p = partsArr.optJSONObject(i)
                            if (!p.optBoolean("thought")) sb.append(p.optString("text"))
                        }
                        if (sb.isNotBlank()) return@withContext sb.toString()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$model xato: ${e.message}")
                }
            }
            null
        }
}

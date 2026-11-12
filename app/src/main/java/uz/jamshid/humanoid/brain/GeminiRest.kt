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

/** Gemini REST (generateContent) — model hovuzidan navbat bilan; limit tugasa keyingisiga o'tadi. */
class GeminiRest(private val apiKey: String) {

    companion object {
        val FAST get() = ModelPool.FAST
        val SMART get() = ModelPool.SMART
        private const val TAG = "GeminiRest"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    fun textPart(t: String): JSONObject = JSONObject().put("text", t)
    fun imagePart(jpeg: ByteArray): JSONObject = JSONObject().put("inlineData",
        JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))

    /** @param maxTries nechta modelni sinab ko'rish (tezkor vazifalar uchun kamroq) */
    suspend fun generate(pool: ModelPool, parts: List<JSONObject>, json: Boolean = false, maxTries: Int = 6): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().apply { parts.forEach { put(it) } })))
            if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            val payload = body.toString().toRequestBody("application/json".toMediaType())

            for (model in pool.available().take(maxTries)) {
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
                            pool.penalize(model, resp.code, txt.take(300))
                            return@use
                        }
                        val partsArr = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")
                        val sb = StringBuilder()
                        if (partsArr != null) for (i in 0 until partsArr.length()) {
                            val p = partsArr.optJSONObject(i)
                            if (!p.optBoolean("thought")) sb.append(p.optString("text"))
                        }
                        if (sb.isNotBlank()) return@withContext sb.toString()
                        pool.rest(model, 30_000L)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$model xato: ${e.message}")
                    pool.penalize(model, 0, e.message.orEmpty())
                }
            }
            null
        }
}

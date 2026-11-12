package uz.jamshid.humanoid.brain

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * MIYA — Gemini Live API bilan to'liq avtomatik almashinuvchi ulanish.
 * Bitta modelda limit (429/quota) yoki xato bo'lsa, zudlik bilan hovuzdagi
 * keyingi modelga o'tadi. Foydalanuvchi uzilishni sezmaydi.
 */
class LiveBrain(
    private val apiKey: String,
    private val instruction: () -> String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(model: String, resumed: Boolean)
        fun onAudio(pcm24k: ByteArray)
        fun onInterrupted()
        fun onUserText(text: String)
        fun onModelText(text: String)
        fun onTurnComplete()
        fun onState(state: String)
    }

    companion object {
        private const val TAG = "LiveBrain"
        const val VOICE = "Charon"
        private const val BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
    }

    private val pool = ModelPool.LIVE
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ws: WebSocket? = null
    @Volatile var ready = false
        private set
    @Volatile private var stopped = true
    @Volatile private var generation = 0
    @Volatile private var currentModel: String = pool.first()

    private var resumeHandle: String? = null
    private var usedHandle = false
    private var failStreak = 0
    @Volatile private var pendingText: String? = null

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        ready = false
        generation++
        ws?.close(1000, "stop")
        ws = null
    }

    private fun connect() {
        if (stopped) return
        val gen = ++generation
        // Mavjud, limiti tugamagan birinchi modelni tanlaymiz
        val model = pool.first()
        currentModel = model
        listener.onState("Ulanmoqda: $model")

        val req = Request.Builder().url(BASE_URL + apiKey).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    webSocket.send(buildSetupJson(model))
                } catch (e: Exception) {
                    onFailure(webSocket, e, response)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) handleMessage(text, model)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen == generation) handleMessage(bytes.utf8(), model)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) onDropped(model, "Yopildi: $code $reason", 0)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val msg = t.message.orEmpty()
                Log.e(TAG, "Failure on $model: code=$code, msg=$msg")
                if (gen == generation) onDropped(model, msg, code)
            }
        })
    }

    private fun buildSetupJson(model: String): String {
        val genConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE))))

        // Agar extended thinking model bo'lsa, kerakli config
        if (model.contains("thinking")) {
            genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }

        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", genConfig)
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", instruction()))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()))

        val sr = JSONObject()
        usedHandle = resumeHandle != null
        resumeHandle?.let { sr.put("handle", it) }
        setup.put("sessionResumption", sr)

        return JSONObject().put("setup", setup).toString()
    }

    private fun handleMessage(text: String, model: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }

        if (m.has("setupComplete")) {
            ready = true
            failStreak = 0
            listener.onState("Jonli: $model")
            listener.onReady(model, usedHandle)
            pendingText?.let { t ->
                pendingText = null
                sendText(t)
            }
            return
        }

        m.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val h = u.optString("newHandle")
            if (u.optBoolean("resumable", true) && h.isNotEmpty()) {
                resumeHandle = h
            }
        }

        if (m.has("goAway")) {
            main.post { reconnectNext(model, "goAway") }
            return
        }

        val sc = m.optJSONObject("serverContent") ?: return

        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                if (inline.optString("mimeType").startsWith("audio")) {
                    val raw = Base64.decode(inline.optString("data"), Base64.DEFAULT)
                    listener.onAudio(raw)
                }
            }
        }

        if (sc.optBoolean("interrupted")) {
            listener.onInterrupted()
        }

        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            listener.onUserText(it)
        }

        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            listener.onModelText(it)
        }

        if (sc.optBoolean("turnComplete")) {
            listener.onTurnComplete()
        }
    }

    private fun reconnectNext(failedModel: String, reason: String) {
        if (stopped) return
        ready = false
        val old = ws
        generation++
        old?.close(1000, reason)
        ws = null
        connect()
    }

    private fun onDropped(model: String, why: String, httpCode: Int) {
        val wasReady = ready
        ready = false
        ws = null
        listener.onState("Limit/uzilish ($model): $why")

        // Agar ulanish bosqichida yoki limit sabab yiqilsa, bu modelni vaqtincha dam oldiramiz
        pool.penalize(model, httpCode, why)
        resumeHandle = null // boshqa modelga o'tganda eski handle ishlamaydi

        if (stopped) return

        failStreak++
        val delay = if (wasReady) 300L else min(10_000L, 500L * failStreak)
        main.postDelayed({
            if (!stopped && ws == null) connect()
        }, delay)
    }

    fun sendAudio(pcm16k: ByteArray) {
        if (!ready) return
        val b64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    fun sendFrame(jpeg: ByteArray) {
        if (!ready) return
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"video\":{\"data\":\"$b64\",\"mimeType\":\"image/jpeg\"}}}")
    }

    fun sendText(text: String) {
        if (!ready) {
            pendingText = text
            return
        }
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }
}

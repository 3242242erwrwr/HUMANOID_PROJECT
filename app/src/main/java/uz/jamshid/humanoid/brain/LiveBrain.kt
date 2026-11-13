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

/**
 * MIYA — Ko'p kalitli aylanuvchi avtomatik tizim (Multi-Key Failover).
 * Bitta kalit limiti tugasa (429/quota), darhol keyingi kalitga sakraydi.
 */
class LiveBrain(
    private val keyManager: ApiKeyManager,
    private val instruction: () -> String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(model: String, keyIndex: Int)
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

    @Volatile private var currentKey: String = ""

    private val currentTurnText = StringBuilder()
    private val currentTurnAudio = mutableListOf<ByteArray>()
    @Volatile private var isFilteringInitialGreeting = true

    fun start() {
        stopped = false
        isFilteringInitialGreeting = true
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
        val model = pool.first()
        val key = keyManager.getActiveKey()
        currentKey = key

        val allKeys = keyManager.getAllKeys()
        val keyIdx = allKeys.indexOf(key) + 1
        listener.onState("Ulanmoqda ($model | Kalit #$keyIdx)")

        val req = Request.Builder().url(BASE_URL + key).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    webSocket.send(buildSetupJson(model))
                } catch (e: Exception) {
                    onFailure(webSocket, e, response)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) handleMessage(text, model, keyIdx)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen == generation) handleMessage(bytes.utf8(), model, keyIdx)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) onDropped(model, "Yopildi", 0)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val msg = t.message.orEmpty()
                Log.e(TAG, "Failure on $model with key #$keyIdx: code=$code, msg=$msg")
                if (gen == generation) onDropped(model, msg, code)
            }
        })
    }

    private fun buildSetupJson(model: String): String {
        val genConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE))))

        if (model.contains("thinking")) {
            genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }

        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", genConfig)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()))

        val text = instruction()
        if (text.isNotBlank()) {
            setup.put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", text))))
        }

        return JSONObject().put("setup", setup).toString()
    }

    private fun handleMessage(text: String, model: String, keyIdx: Int) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }

        if (m.has("setupComplete")) {
            ready = true
            listener.onState("Jonli: $model (Kalit #$keyIdx)")
            listener.onReady(model, keyIdx)
            return
        }

        if (m.has("goAway")) {
            main.post {
                if (!stopped) {
                    ready = false
                    ws?.close(1000, "goAway")
                    ws = null
                    connect()
                }
            }
            return
        }

        val sc = m.optJSONObject("serverContent") ?: return

        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                if (inline.optString("mimeType").startsWith("audio")) {
                    val raw = Base64.decode(inline.optString("data"), Base64.DEFAULT)
                    if (isFilteringInitialGreeting) {
                        currentTurnAudio.add(raw)
                    } else {
                        listener.onAudio(raw)
                    }
                }
            }
        }

        if (sc.optBoolean("interrupted")) {
            listener.onInterrupted()
        }

        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            isFilteringInitialGreeting = false
            listener.onUserText(it)
        }

        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            currentTurnText.append(it)
            if (!isFilteringInitialGreeting) {
                listener.onModelText(it)
            }
        }

        if (sc.optBoolean("turnComplete")) {
            val fullText = currentTurnText.toString().lowercase()
            if (isFilteringInitialGreeting) {
                val isBotGreeting = fullText.contains("yordam") || fullText.contains("qanday yordam") || fullText.contains("nima xizmat")
                if (!isBotGreeting) {
                    for (chunk in currentTurnAudio) {
                        listener.onAudio(chunk)
                    }
                    listener.onModelText(currentTurnText.toString())
                }
                isFilteringInitialGreeting = false
            } else {
                listener.onModelText(currentTurnText.toString())
            }

            currentTurnText.setLength(0)
            currentTurnAudio.clear()
            listener.onTurnComplete()
        }
    }

    private fun onDropped(model: String, why: String, httpCode: Int) {
        ready = false
        ws = null

        // Agar limit (429/quota) bo'lsa, joriy kalitni dam oldiramiz va boshqasiga o'tamiz
        if (httpCode == 429 || why.contains("quota", true) || why.contains("limit", true)) {
            keyManager.penalizeKey(currentKey)
            listener.onState("Kalit limiti tugadi, keyingisiga o'tilmoqda...")
        } else {
            pool.penalize(model, httpCode, why)
            listener.onState("Qayta ulanmoqda...")
        }

        if (stopped) return

        main.postDelayed({
            if (!stopped && ws == null) connect()
        }, 500L)
    }

    fun sendAudio(pcm16k: ByteArray) {
        if (!ready) return
        val b64 = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    fun sendFrame(jpeg: ByteArray) {
        if (!ready) return
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val payload = "{\"realtimeInput\":{\"video\":{\"data\":\"$b64\",\"mimeType\":\"image/jpeg\"}}}"
        ws?.send(payload)
    }

    fun impulse(text: String) {
        if (!ready) return
        val payload = JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("turnComplete", true))
        ws?.send(payload.toString())
    }
}

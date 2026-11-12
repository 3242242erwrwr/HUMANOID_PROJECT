package uz.jamshid.humanoid.brain

import android.os.Handler
import android.os.Looper
import android.util.Base64
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
 * MIYA — Gemini Live API bilan uzluksiz, real-vaqt audio/video aloqa.
 * Asosiy model ishlamasa, zaxira modellarga avtomatik o'tadi.
 * Sessiya uzilsa — "session resumption" orqali suhbat konteksti saqlangan holda qayta ulanadi.
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
        val MODELS = listOf(
            "gemini-3.8-live",
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-latest",
        )
        const val VOICE = "Charon"
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
    }

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
    private var modelIdx = 0
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
        ws?.close(1000, "bye")
        ws = null
    }

    private fun connect() {
        if (stopped) return
        val gen = ++generation
        val model = MODELS[modelIdx]
        listener.onState("Ulanmoqda: $model")
        val req = Request.Builder().url(URL + apiKey).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupJson(model))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) handle(text, model)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (gen == generation) handle(bytes.utf8(), model)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) dropped("yopildi $code $reason")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) dropped("xato: ${t.message}")
            }
        })
    }

    private fun setupJson(model: String): String {
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE)))))
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

    private fun handle(text: String, model: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }

        if (m.has("setupComplete")) {
            ready = true
            failStreak = 0
            listener.onState("Jonli: $model")
            listener.onReady(model, usedHandle)
            pendingText?.let { pendingText = null; sendText(it) }
        }

        m.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val h = u.optString("newHandle")
            if (u.optBoolean("resumable", true) && h.isNotEmpty()) resumeHandle = h
        }

        if (m.has("goAway")) {
            // Server tez orada ulanishni yopadi — oldindan yangi sessiyaga o'tamiz
            main.post { reconnectNow() }
        }

        val sc = m.optJSONObject("serverContent") ?: return
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                if (inline.optString("mimeType").startsWith("audio")) {
                    listener.onAudio(Base64.decode(inline.optString("data"), Base64.DEFAULT))
                }
            }
        }
        if (sc.optBoolean("interrupted")) listener.onInterrupted()
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
            ?.let { listener.onUserText(it) }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
            ?.let { listener.onModelText(it) }
        if (sc.optBoolean("turnComplete")) listener.onTurnComplete()
    }

    private fun reconnectNow() {
        if (stopped) return
        ready = false
        val old = ws
        generation++ // eski ulanish callbacklarini e'tiborsiz qoldiramiz
        old?.close(1000, "goAway")
        connect()
    }

    private fun dropped(why: String) {
        val wasReady = ready
        ready = false
        ws = null
        listener.onState("Aloqa uzildi: $why")
        if (stopped) return
        if (!wasReady) {
            // Sozlash bosqichida xato: eski handle yaroqsiz bo'lishi mumkin, keyin modelni almashtiramiz
            resumeHandle = null
            failStreak++
            if (failStreak % 2 == 0) modelIdx = (modelIdx + 1) % MODELS.size
        }
        val delay = if (wasReady) 500L else min(30_000L, 1000L shl min(failStreak, 5))
        main.postDelayed({ if (!stopped && ws == null) connect() }, delay)
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

    /** Sezgi/ko'z/holat signallarini yuborish. Ulanish tayyor bo'lmasa, oxirgisi saqlanadi. */
    fun sendText(text: String) {
        if (!ready) { pendingText = text; return }
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }
}

package uz.jamshid.humanoid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import uz.jamshid.humanoid.brain.GeminiRest
import uz.jamshid.humanoid.brain.LiveBrain
import uz.jamshid.humanoid.brain.ModelPool
import uz.jamshid.humanoid.brain.Persona
import uz.jamshid.humanoid.memory.Memory
import uz.jamshid.humanoid.organs.Ears
import uz.jamshid.humanoid.organs.Eyes
import uz.jamshid.humanoid.organs.Nerves
import uz.jamshid.humanoid.organs.Voice
import uz.jamshid.humanoid.world.Weather
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GUMANOID TANASI — Barcha a'zolar uyg'unligi.
 * - Hech qanday tayyor gap yo'q!
 * - Qarshisidagi insonni umuman tanimaydi (na ism, na kasb).
 * - Hech qanday harakat yoki sezgi bo'lmasa — gapirmaydi, tinch turadi.
 * - Biror model limiti tugasa, avtomatik boshqa modelga o'tadi.
 */
class HumanoidService : LifecycleService(), LiveBrain.Listener {

    companion object {
        private const val CHANNEL = "hamroh"
        private const val NOTIF_ID = 1
        private const val BARGE_IN_RMS = 2200.0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var rest: GeminiRest
    private lateinit var brain: LiveBrain
    private lateinit var voice: Voice
    private lateinit var ears: Ears
    private lateinit var nerves: Nerves
    private var eyes: Eyes? = null
    private lateinit var memory: Memory
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var weather: String? = null
    private val userBuf = StringBuilder()
    private val modelBuf = StringBuilder()
    private var frameCount = 0
    private val visionBusy = AtomicBoolean(false)
    private var lastHazard = ""
    private var lastHazardAt = 0L

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hamroh::Tana").apply { acquire() }

        val key = BuildConfig.GEMINI_API_KEY
        rest = GeminiRest(key)
        memory = Memory(this, rest)
        voice = Voice(this).apply { routeAudio(); start() }
        nerves = Nerves(this) { event -> signal(event) }
        brain = LiveBrain(key, { Persona.instruction(memory.longTerm(), memory.recentLog(), contextLine()) }, this)

        ears = Ears { chunk ->
            // Inson gapirsa yoki baland tovush kelsa — miyaga uzatiladi
            val speaking = voice.isSpeaking()
            if (!speaking || Ears.rms(chunk) > BARGE_IN_RMS) {
                brain.sendAudio(chunk)
            } else {
                brain.sendAudio(ByteArray(chunk.size))
            }
        }

        brain.start()
        ears.start()
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) nerves.start()
        if (granted(Manifest.permission.CAMERA)) {
            eyes = Eyes(this, this) { onFrame(it) }.also { it.start() }
        }

        HumanoidState.add("Tana tayyor")
        scope.launch { lifeLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        HumanoidState.add("Tizim to'xtatildi")
        HumanoidState.status("Uxlayapti")
        eyes?.stop()
        nerves.stop()
        ears.stop()
        brain.stop()
        voice.stop()
        scope.cancel()
        Thread { runBlocking { memory.consolidate() } }.start()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------------- Fon davriy vazifalari (faqat ob-havo va xotira, jimlikda behuda gapirish YO'Q) ----------------

    private suspend fun lifeLoop() {
        var lastWeather = 0L
        var lastConsolidate = SystemClock.elapsedRealtime()
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()

            nerves.location?.let { loc ->
                if (weather == null || now - lastWeather > 30 * 60_000L) {
                    Weather.now(loc.latitude, loc.longitude)?.let {
                        weather = it
                        lastWeather = now
                    }
                }
            }

            if (now - lastConsolidate > 15 * 60_000L) {
                memory.consolidate()
                lastConsolidate = now
            }
            delay(30_000)
        }
    }

    // ---------------- Ko'z (faqat real xavf yoki muhim narsa ko'rinsagina bildiradi) ----------------

    private fun onFrame(jpeg: ByteArray) {
        brain.sendFrame(jpeg)
        frameCount++
        // Har 4 soniyada 1 marta ko'z tahlili
        if (frameCount % 2 == 0 && visionBusy.compareAndSet(false, true)) {
            scope.launch {
                try { checkVisualChange(jpeg) } finally { visionBusy.set(false) }
            }
        }
    }

    private suspend fun checkVisualChange(jpeg: ByteArray) {
        val prompt = """
            Kadrga qara.
            Atrofda inson e'tiborini tortishi kerak bo'lgan biror favqulodda, xavfli yoki o'ta muhim holat bormi?
            Hech narsani taxmin qilma. Agar oddiy xona, oddiy yo'l yoki sokinlik bo'lsa — darajani 0 deb ber.
            Faqat JSON: {"daraja": 0-3, "nima": "qisqa tavsif"}
            0 = hech narsa yo'q/sokin, 1 = oddiy, 2 = diqqatga loyiq, 3 = xavf.
        """.trimIndent()

        val out = rest.generate(GeminiRest.FAST, listOf(rest.textPart(prompt), rest.imagePart(jpeg)), json = true) ?: return
        val j = try { JSONObject(out.trim().removePrefix("```json").removeSuffix("```")) } catch (_: Exception) { return }
        val level = j.optInt("daraja", 0)
        val what = j.optString("nima")
        val now = SystemClock.elapsedRealtime()

        // Faqat haqiqiy muhim hodisa (daraja 2+) bo'lsagina signallash
        if (level >= 2 && what.isNotBlank() && !(what == lastHazard && now - lastHazardAt < 30_000) && now - lastHazardAt > 15_000) {
            lastHazard = what
            lastHazardAt = now
            signal("[KO'Z] $what")
        }
    }

    // ---------------- Real sezgilar bo'yicha signal ----------------

    private fun signal(text: String) {
        HumanoidState.add("⚡ $text")
        memory.recordEvent(text)
        brain.sendText(text)
    }

    private fun contextLine(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val part = when (h) {
            in 5..10 -> "ertalab"
            in 11..16 -> "kunduzi"
            in 17..21 -> "kechqurun"
            else -> "tun"
        }
        val days = arrayOf("yakshanba", "dushanba", "seshanba", "chorshanba", "payshanba", "juma", "shanba")
        val months = arrayOf("yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avgust", "sentabr", "oktabr", "noyabr", "dekabr")
        val sb = StringBuilder()
        sb.append(String.format("Vaqt %02d:%02d (%s), %s, %d-%s",
            h, c.get(Calendar.MINUTE), part, days[c.get(Calendar.DAY_OF_WEEK) - 1],
            c.get(Calendar.DAY_OF_MONTH), months[c.get(Calendar.MONTH)]))
        val s = nerves.speedKmh
        if (s > 5f) {
            sb.append(". Harakat tezligi: ${s.toInt()} km/soat")
        }
        weather?.let { sb.append(". Ob-havo: $it") }
        return sb.toString()
    }

    // ---------------- Miya bilan aloqa ----------------

    override fun onReady(model: String, resumed: Boolean) {
        HumanoidState.add("Miya ulandi ($model)")
        // Hech qanday tayyor so'zsiz va majburiy salomsiz jim kutadi. Inson gapirsa yoki sezgi kelsa o'zi kirishadi.
    }

    override fun onAudio(pcm24k: ByteArray) {
        voice.play(pcm24k)
    }

    override fun onInterrupted() {
        voice.interrupt()
    }

    override fun onUserText(text: String) {
        synchronized(userBuf) { userBuf.append(text) }
    }

    override fun onModelText(text: String) {
        flushUser()
        synchronized(modelBuf) { modelBuf.append(text) }
    }

    override fun onTurnComplete() {
        flushUser()
        val said = synchronized(modelBuf) { modelBuf.toString().trim().also { modelBuf.setLength(0) } }
        if (said.isNotEmpty()) {
            HumanoidState.add("AI: $said")
            memory.record("Hamroh", said)
        }
    }

    private fun flushUser() {
        val heard = synchronized(userBuf) { userBuf.toString().trim().also { userBuf.setLength(0) } }
        if (heard.isNotEmpty()) {
            HumanoidState.add("Inson: $heard")
            memory.record("Inson", heard)
        }
    }

    override fun onState(state: String) {
        HumanoidState.status(state)
    }

    // ---------------- Ruxsatlar va fon xizmati ----------------

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hamroh", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hamroh")
            .setContentText("Faol")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ServiceCompat.startForeground(this, NOTIF_ID, notif, types)
    }
}

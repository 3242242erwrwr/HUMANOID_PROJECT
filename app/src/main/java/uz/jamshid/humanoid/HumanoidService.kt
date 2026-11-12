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
import uz.jamshid.humanoid.brain.Persona
import uz.jamshid.humanoid.memory.Memory
import uz.jamshid.humanoid.organs.Ears
import uz.jamshid.humanoid.organs.Eyes
import uz.jamshid.humanoid.organs.Nerves
import uz.jamshid.humanoid.organs.Voice
import uz.jamshid.humanoid.world.Weather
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * GUMANOID TANASI — barcha a'zolarni (quloq, ko'z, sezgi, miya, ovoz, xotira) birlashtiradi.
 * Fon xizmati sifatida doimiy ishlaydi, hech qanday tugma talab qilmaydi.
 */
class HumanoidService : LifecycleService(), LiveBrain.Listener {

    companion object {
        private const val CHANNEL = "hamroh"
        private const val NOTIF_ID = 1
        /** Robot gapirayotganda inson gapini ajratish uchun ovoz balandligi chegarasi */
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

    @Volatile private var lastActivity = SystemClock.elapsedRealtime()
    @Volatile private var silenceThreshold = nextSilenceThreshold()
    @Volatile private var weather: String? = null
    private var greeted = false
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
            // Robot gapirayotganda o'z ovozini eshitib qolmasligi uchun: faqat baland inson ovozi o'tadi
            val speaking = voice.isSpeaking()
            if (!speaking || Ears.rms(chunk) > BARGE_IN_RMS) brain.sendAudio(chunk)
            else brain.sendAudio(ByteArray(chunk.size))
        }

        brain.start()
        ears.start()
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) nerves.start()
        if (granted(Manifest.permission.CAMERA)) eyes = Eyes(this, this) { onFrame(it) }.also { it.start() }

        HumanoidState.add("Tana uyg'ondi")
        scope.launch { lifeLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        HumanoidState.add("Tana uxlashga ketdi")
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

    // ---------------- Hayot sikli: jimlik, ob-havo, xotira ----------------

    private suspend fun lifeLoop() {
        var lastWeather = 0L
        var lastConsolidate = SystemClock.elapsedRealtime()
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()

            nerves.location?.let { loc ->
                if (weather == null || now - lastWeather > 30 * 60_000L) {
                    Weather.now(loc.latitude, loc.longitude)?.let { weather = it; lastWeather = now }
                }
            }

            val silentMs = now - lastActivity
            if (brain.ready && silentMs > silenceThreshold) {
                val sec = silentMs / 1000
                signal("[HOLAT] $sec soniyadan beri jimlik. Vaziyatga qarab o'zing gap boshlashing mumkin.")
                lastActivity = now
                silenceThreshold = nextSilenceThreshold()
            }

            if (now - lastConsolidate > 15 * 60_000L) {
                memory.consolidate()
                lastConsolidate = now
            }
            delay(5_000)
        }
    }

    /** Jimlik chegarasi: 1–1.5 daqiqa (har safar tasodifiy, insondek bir xil bo'lmasligi uchun) */
    private fun nextSilenceThreshold() = Random.nextLong(60_000L, 90_000L)

    // ---------------- Ko'z ----------------

    private fun onFrame(jpeg: ByteArray) {
        brain.sendFrame(jpeg)
        frameCount++
        val speed = nerves.speedKmh
        // Har 2-kadrda (≈4 soniya) tezkor model yo'lni xavf uchun alohida tekshiradi
        if (frameCount % 2 == 0 && (speed < 0 || speed > 5f) && visionBusy.compareAndSet(false, true)) {
            scope.launch {
                try { checkHazard(jpeg) } finally { visionBusy.set(false) }
            }
        }
    }

    private suspend fun checkHazard(jpeg: ByteArray) {
        val prompt = """
            Sen haydovchining ko'zisan. Bu — mashina old oynasidan olingan kadr (bo'lmasligi ham mumkin).
            Haydovchi uchun HOZIROQ muhim narsa bormi? Masalan: oldinda keskin tormoz qilgan yoki to'xtagan mashina,
            yo'lga chiqayotgan piyoda yoki bola, qizil svetafor, yo'ldagi to'siq/chuqur, xavfli manevr qilayotgan transport.
            Faqat JSON qaytar: {"daraja": 0-3, "nima": "o'zbekcha qisqa tavsif"}
            0 = muhim narsa yo'q yoki bu yo'l kadri emas, 1 = odatiy holat, 2 = e'tibor kerak, 3 = jiddiy xavf.
        """.trimIndent()
        val out = rest.generate(GeminiRest.FAST, listOf(rest.textPart(prompt), rest.imagePart(jpeg)), json = true) ?: return
        val j = try { JSONObject(out.trim().removePrefix("```json").removeSuffix("```")) } catch (_: Exception) { return }
        val level = j.optInt("daraja", 0)
        val what = j.optString("nima")
        val now = SystemClock.elapsedRealtime()
        if (level >= 2 && what.isNotBlank() && !(what == lastHazard && now - lastHazardAt < 30_000) && now - lastHazardAt > 12_000) {
            lastHazard = what
            lastHazardAt = now
            signal("[KO'Z] $what (xavf darajasi $level/3)")
        }
    }

    // ---------------- Signallar ----------------

    private fun signal(text: String) {
        val full = "$text | ${contextLine()}"
        HumanoidState.add("⚡ $text")
        memory.recordEvent(text)
        brain.sendText(full)
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
        sb.append(if (s < 0) ". Tezlik noma'lum" else if (s < 3) ". Mashina turibdi" else ". Tezlik ${s.toInt()} km/soat")
        weather?.let { sb.append(". Ob-havo: $it") }
        return sb.toString()
    }

    // ---------------- Miya hodisalari ----------------

    override fun onReady(model: String, resumed: Boolean) {
        HumanoidState.add(if (resumed) "Miya qayta ulandi ($model)" else "Miya uyg'ondi ($model)")
        if (!greeted && !resumed) {
            greeted = true
            signal("[HOLAT] Sen hozirgina uyg'onding, Jamshid aka yoningda. Vaziyatga mos ravishda o'zing birinchi bo'lib gapir.")
        }
    }

    override fun onAudio(pcm24k: ByteArray) {
        voice.play(pcm24k)
        lastActivity = SystemClock.elapsedRealtime()
    }

    override fun onInterrupted() {
        voice.interrupt()
    }

    override fun onUserText(text: String) {
        synchronized(userBuf) { userBuf.append(text) }
        lastActivity = SystemClock.elapsedRealtime()
    }

    override fun onModelText(text: String) {
        flushUser()
        synchronized(modelBuf) { modelBuf.append(text) }
    }

    override fun onTurnComplete() {
        flushUser()
        val said = synchronized(modelBuf) { modelBuf.toString().trim().also { modelBuf.setLength(0) } }
        if (said.isNotEmpty()) {
            HumanoidState.add("🤖 $said")
            memory.record("Hamroh", said)
        }
    }

    private fun flushUser() {
        val heard = synchronized(userBuf) { userBuf.toString().trim().also { userBuf.setLength(0) } }
        if (heard.isNotEmpty()) {
            HumanoidState.add("👤 $heard")
            memory.record("Jamshid aka", heard)
        }
    }

    override fun onState(state: String) {
        HumanoidState.status(state)
    }

    // ---------------- Fon xizmati ----------------

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hamroh", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hamroh yoningizda")
            .setContentText("Eshityapti, ko'ryapti, sezyapti")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ServiceCompat.startForeground(this, NOTIF_ID, notif, types)
    }
}

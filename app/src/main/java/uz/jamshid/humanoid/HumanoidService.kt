package uz.jamshid.humanoid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
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
import uz.jamshid.humanoid.brain.LiveBrain
import uz.jamshid.humanoid.brain.Persona
import uz.jamshid.humanoid.organs.Ears
import uz.jamshid.humanoid.organs.Eyes
import uz.jamshid.humanoid.organs.Nerves
import uz.jamshid.humanoid.organs.Voice

/**
 * GUMANOID TANASI — Tez fikrlaydigan, gapni cho'zmaydigan erkin inson.
 */
class HumanoidService : LifecycleService(), LiveBrain.Listener {

    companion object {
        private const val CHANNEL = "hamroh"
        private const val NOTIF_ID = 1
        private const val BARGE_IN_RMS = 2200.0
        const val ACTION_SWITCH_CAMERA = "uz.jamshid.humanoid.SWITCH_CAMERA"
    }

    inner class LocalBinder : Binder() {
        fun getService(): HumanoidService = this@HumanoidService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var brain: LiveBrain
    private lateinit var voice: Voice
    private lateinit var ears: Ears
    private lateinit var nerves: Nerves
    var eyes: Eyes? = null
        private set
    private var wakeLock: PowerManager.WakeLock? = null

    private val userBuf = StringBuilder()
    private val modelBuf = StringBuilder()

    @Volatile private var lastSpokeAt = 0L
    @Volatile private var hasFrame = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hamroh::Tana").apply { acquire() }

        val key = BuildConfig.GEMINI_API_KEY
        voice = Voice(this).apply { routeAudio(); start() }
        nerves = Nerves(this) { event ->
            HumanoidState.add("⚡ $event")
            brain.impulse("Hozirgina tanangda shuni his qilding: $event. Cho'zmasdan, 1 ta tabiiy jumla ayt.")
        }
        brain = LiveBrain(key, { Persona.instruction() }, this)

        ears = Ears { chunk ->
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
            eyes = Eyes(this, this) { jpeg ->
                hasFrame = true
                brain.sendFrame(jpeg)
            }.also { it.start(front = true) }
        }

        HumanoidState.add("Tana uyg'ondi (Oldi kamera)")
        scope.launch { autonomousLoop() }
    }

    private suspend fun autonomousLoop() {
        delay(3000)
        if (brain.ready && hasFrame) {
            brain.impulse("Hozir ko'rganingga tez qarab, cho'zmasdan 1-2 ta erkin jumla bilan gap boshla.")
            lastSpokeAt = SystemClock.elapsedRealtime()
        }

        while (scope.isActive) {
            delay(10_000)
            val now = SystemClock.elapsedRealtime()
            val silentSec = (now - lastSpokeAt) / 1000

            if (brain.ready && hasFrame && !voice.isSpeaking() && silentSec >= 12) {
                brain.impulse("Ko'rganingga qara. Agar diqqatga loyiq yangi narsa ko'rsang, cho'zmasdan 1-2 jumla fikr bildir. O'zgarish bo'lmasa jim tur.")
                lastSpokeAt = now
            }
        }
    }

    fun switchCamera(): Boolean {
        val isFront = eyes?.switchCamera() ?: true
        HumanoidState.add(if (isFront) "Ko'z: OLDI kameraga o'tdi" else "Ko'z: ORQA kameraga o'tdi")
        brain.impulse("Yangi ko'rgan tomonga qara, cho'zmasdan 1-2 jumla munosabat bildir.")
        return isFront
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_SWITCH_CAMERA) {
            switchCamera()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        HumanoidState.add("Tizim to'xtatildi")
        HumanoidState.status("Uxlayapti")
        scope.cancel()
        eyes?.stop()
        nerves.stop()
        ears.stop()
        brain.stop()
        voice.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onReady(model: String) {
        HumanoidState.add("Miya ulandi ($model)")
    }

    override fun onAudio(pcm24k: ByteArray) {
        voice.play(pcm24k)
        lastSpokeAt = SystemClock.elapsedRealtime()
    }

    override fun onInterrupted() {
        voice.interrupt()
    }

    override fun onUserText(text: String) {
        synchronized(userBuf) { userBuf.append(text) }
        lastSpokeAt = SystemClock.elapsedRealtime()
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
            lastSpokeAt = SystemClock.elapsedRealtime()
        }
    }

    private fun flushUser() {
        val heard = synchronized(userBuf) { userBuf.toString().trim().also { userBuf.setLength(0) } }
        if (heard.isNotEmpty()) {
            HumanoidState.add("Inson: $heard")
        }
    }

    override fun onState(state: String) {
        HumanoidState.status(state)
    }

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

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import uz.jamshid.humanoid.brain.LiveBrain
import uz.jamshid.humanoid.brain.Persona
import uz.jamshid.humanoid.organs.Ears
import uz.jamshid.humanoid.organs.Eyes
import uz.jamshid.humanoid.organs.Nerves
import uz.jamshid.humanoid.organs.Voice

/**
 * GUMANOID TANASI — Mutlaqo erkin va tabiiy.
 * Hech qanday shablon, qolip yoki sun'iy to'siqlar yo'q.
 * Ko'radi, eshitadi, sezadi va bevosita jonli muloqot qiladi.
 */
class HumanoidService : LifecycleService(), LiveBrain.Listener {

    companion object {
        private const val CHANNEL = "hamroh"
        private const val NOTIF_ID = 1
        private const val BARGE_IN_RMS = 2200.0
    }

    private lateinit var brain: LiveBrain
    private lateinit var voice: Voice
    private lateinit var ears: Ears
    private lateinit var nerves: Nerves
    private var eyes: Eyes? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val userBuf = StringBuilder()
    private val modelBuf = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hamroh::Tana").apply { acquire() }

        val key = BuildConfig.GEMINI_API_KEY
        voice = Voice(this).apply { routeAudio(); start() }
        nerves = Nerves(this) { event ->
            HumanoidState.add("⚡ $event")
            brain.sendSense(event)
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
                brain.sendFrame(jpeg)
            }.also { it.start() }
        }

        HumanoidState.add("Tana uyg'ondi")
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
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onReady(model: String) {
        HumanoidState.add("Miya ulandi ($model)")
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

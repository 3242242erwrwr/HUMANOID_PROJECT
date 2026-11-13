package uz.jamshid.humanoid.organs

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * OVOZ IJROSI — Endi "telefon trubkasi"dek emas, toza va tabiiy musiqa/media sifatda!
 */
class Voice(private val ctx: Context) {

    companion object {
        const val RATE = 24000
    }

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var speakingUntil = 0L
    private var thread: Thread? = null

    // Toza, jarangdor va tabiiy inson ovozi uchun MEDIA atributlari
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(max(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    fun routeAudio() {
        // Karnaydan tabiiy, ochiq va keng ovoz chiqishi uchun NORMAL rejim
        am.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devs = am.availableCommunicationDevices
            val bt = devs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
            bt?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
    }

    fun start() {
        if (running) return
        running = true
        track.play()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                val b = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                track.write(b, 0, b.size)
            }
        }, "Voice").apply { start() }
    }

    fun play(pcm: ByteArray) {
        queue.offer(pcm)
        val now = SystemClock.elapsedRealtime()
        speakingUntil = max(now, speakingUntil) + pcm.size * 1000L / (RATE * 2)
    }

    fun interrupt() {
        queue.clear()
        track.pause()
        track.flush()
        track.play()
        speakingUntil = 0
    }

    fun isSpeaking(): Boolean = SystemClock.elapsedRealtime() < speakingUntil + 300

    fun stop() {
        running = false
        thread?.join(500)
        track.stop()
        track.release()
        am.mode = AudioManager.MODE_NORMAL
    }
}

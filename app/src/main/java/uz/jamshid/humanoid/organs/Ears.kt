package uz.jamshid.humanoid.organs

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import kotlin.math.max
import kotlin.math.sqrt

/** QULOQ — doimiy ochiq mikrofon, 16kHz PCM, 100ms bo'laklar. Exo/shovqin bostirish yoqilgan. */
class Ears(private val onChunk: (ByteArray) -> Unit) {

    companion object {
        const val RATE = 16000
        private const val CHUNK_BYTES = RATE * 2 / 10 // 100ms

        fun rms(buf: ByteArray): Double {
            var sum = 0.0
            val n = buf.size / 2
            if (n == 0) return 0.0
            for (i in 0 until n) {
                val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xff)).toShort().toDouble()
                sum += s * s
            }
            return sqrt(sum / n)
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, CHUNK_BYTES * 4)
        )
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true }

        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            rec.startRecording()
            val buf = ByteArray(CHUNK_BYTES)
            while (running) {
                var off = 0
                while (off < buf.size && running) {
                    val n = rec.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off > 0) onChunk(buf.copyOf(off))
            }
            rec.stop()
            rec.release()
            aec?.release(); ns?.release(); agc?.release()
        }, "Ears").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }
}

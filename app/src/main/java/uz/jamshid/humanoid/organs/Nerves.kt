package uz.jamshid.humanoid.organs

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ASAB TIZIMI — akselerometr, giroskop va GPS.
 * Bu yerda gap yo'q: faqat hodisaning xom tavsifi (turi, kuchi, tezlik) miyaga uzatiladi.
 */
class Nerves(ctx: Context, private val onEvent: (String) -> Unit) : SensorEventListener, LocationListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile var speedKmh: Float = -1f; private set
    @Volatile var location: Location? = null; private set

    private var linear: Sensor? = null
    private var usingRawAccel = false
    private var lastSpeed = -1f
    private var lastSpeedAt = 0L
    private val cooldown = HashMap<String, Long>()
    private var lastAnyEvent = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linear == null) {
            linear = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            usingRawAccel = true
        }
        linear?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) { }
    }

    fun stop() {
        sm.unregisterListener(this)
        try { lm.removeUpdates(this) } catch (_: Exception) { }
    }

    private fun moving() = speedKmh >= 8f

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                var mag = sqrt(x * x + y * y + z * z)
                if (usingRawAccel) mag = abs(mag - SensorManager.GRAVITY_EARTH)
                // Faqat harakat paytidagi keskin zarbalar (qo'lda silkitish emas)
                if (mag > 7.5f && moving()) {
                    fire("zarba", "Keskin silkinish ${fmt(mag / SensorManager.GRAVITY_EARTH)}g")
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val rate = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                if (rate > 1.0f && speedKmh > 25f) {
                    fire("burilish", "Keskin aylanish ${fmt(rate)} rad/s")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(loc: Location) {
        location = loc
        if (!loc.hasSpeed()) return
        val s = loc.speed * 3.6f
        val now = SystemClock.elapsedRealtime()
        if (lastSpeed >= 0) {
            val dt = (now - lastSpeedAt) / 1000f
            if (dt in 0.5f..3f) {
                val change = (s - lastSpeed) / dt // km/soat har soniyada
                if (change < -13f && lastSpeed > 20f) {
                    fire("sekinlashish", "Keskin sekinlashish ${lastSpeed.toInt()} → ${s.toInt()} km/soat, ${fmt(dt)} s")
                } else if (change > 11f) {
                    fire("tezlanish", "Keskin tezlanish ${lastSpeed.toInt()} → ${s.toInt()} km/soat")
                }
            }
        }
        lastSpeed = s
        lastSpeedAt = now
        speedKmh = s
    }

    private fun fire(type: String, desc: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - (cooldown[type] ?: 0L) < 25_000L) return
        if (now - lastAnyEvent < 8_000L) return
        cooldown[type] = now
        lastAnyEvent = now
        onEvent("[SEZGI] $desc")
    }

    private fun fmt(f: Float) = String.format(Locale.US, "%.1f", f)
}

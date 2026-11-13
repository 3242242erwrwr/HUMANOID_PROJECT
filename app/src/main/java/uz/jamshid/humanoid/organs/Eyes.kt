package uz.jamshid.humanoid.organs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * KO'Z — MainExecutor orqali 100% ishonchli almashadigan kamera.
 */
class Eyes(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val onFrame: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "Eyes"
        const val INTERVAL_MS = 2500L
    }

    private val exec = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lastFrame = 0L

    @Volatile var isFront: Boolean = true
        private set

    fun start(front: Boolean = true) {
        this.isFront = front
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val p = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "CameraProvider xatosi: ${e.message}")
                return@addListener
            }
            provider = p
            bindCamera(p)
        }, ContextCompat.getMainExecutor(ctx))
    }

    /** Ekrandagi tugma bosilganda kamerani oldi/orqa qilib almashtirish (MainThread'da) */
    fun switchCamera(): Boolean {
        isFront = !isFront
        provider?.let { p ->
            ContextCompat.getMainExecutor(ctx).execute {
                bindCamera(p)
            }
        }
        return isFront
    }

    private fun bindCamera(p: ProcessCameraProvider) {
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(exec) { img ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrame >= INTERVAL_MS) {
                    lastFrame = now
                    val rot = img.imageInfo.rotationDegrees
                    val bmp = img.toBitmap()
                    val jpeg = toJpeg(bmp, rot, isFront)
                    onFrame(jpeg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Kadr tahlili xatosi: ${e.message}")
            } finally {
                img.close()
            }
        }

        val selector = if (isFront) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            p.unbindAll()
            p.bindToLifecycle(owner, selector, analysis)
            Log.i(TAG, "Ko'z qadaldi: ${if (isFront) "OLDI KAMERA" else "ORQA KAMERA"}")
        } catch (e: Exception) {
            Log.e(TAG, "Kamerani ulashda xato: ${e.message}")
        }
    }

    private fun toJpeg(src: Bitmap, rotation: Int, front: Boolean): ByteArray {
        val scale = 480f / max(src.width, src.height)
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
            if (front) postScale(-1f, 1f, src.width / 2f, src.height / 2f)
        }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
            out.toByteArray()
        }
    }

    fun stop() {
        ContextCompat.getMainExecutor(ctx).execute {
            try {
                provider?.unbindAll()
            } catch (_: Exception) {}
        }
        exec.shutdown()
    }
}

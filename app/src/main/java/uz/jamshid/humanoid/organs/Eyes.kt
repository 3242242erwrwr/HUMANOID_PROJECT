package uz.jamshid.humanoid.organs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
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

/** KO'Z — Yengil, to'g'ridan-to'g'ri oqim. Internetni tiqmaslik uchun optimal o'lcham va oraliq. */
class Eyes(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val onFrame: (ByteArray) -> Unit,
) {
    companion object {
        const val INTERVAL_MS = 3000L // Har 3 soniyada 1 ta yengil kadr
    }

    private val exec = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lastFrame = 0L

    fun start() {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val p = try { future.get() } catch (e: Exception) { return@addListener }
            provider = p
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
                        onFrame(toJpeg(img.toBitmap(), img.imageInfo.rotationDegrees))
                    }
                } catch (_: Exception) {
                } finally {
                    img.close()
                }
            }
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun toJpeg(src: Bitmap, rotation: Int): ByteArray {
        val scale = 480f / max(src.width, src.height)
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 50, out)
            out.toByteArray()
        }
    }

    fun stop() {
        ContextCompat.getMainExecutor(ctx).execute { provider?.unbindAll() }
        exec.shutdown()
    }
}

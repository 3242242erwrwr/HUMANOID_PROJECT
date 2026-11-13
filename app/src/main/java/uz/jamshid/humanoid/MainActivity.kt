package uz.jamshid.humanoid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Oddiy, ishonchli va crash bo'lmaydigan interfeys.
 * Xizmat bilan aloqa Intent orqali ishlaydi (IPC crash xavfi yo'q).
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnCamera: Button

    private var isFrontCamera = true

    private val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { wake() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        if (BuildConfig.GEMINI_API_KEY.isBlank()) {
            statusView.text = "GEMINI_API_KEY yo'q"
            return
        }

        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            wake()
        } else {
            askPerms.launch(missing.toTypedArray())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    HumanoidState.status.collect {
                        statusView.text = it
                    }
                }
                launch {
                    HumanoidState.log.collect { lines ->
                        logView.text = lines.joinToString("\n\n")
                        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    private fun wake() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusView.text = "Mikrofonga ruxsat kerak"
            return
        }
        val intent = Intent(this, HumanoidService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            statusView.text = "Xizmatni yoqishda xato: ${e.message}"
        }
    }

    private fun toggleCamera() {
        isFrontCamera = !isFrontCamera
        val intent = Intent(this, HumanoidService::class.java).apply {
            action = HumanoidService.ACTION_SWITCH_CAMERA
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (_: Exception) {}
        updateButtonUi()
    }

    private fun updateButtonUi() {
        if (!::btnCamera.isInitialized) return
        btnCamera.text = if (isFrontCamera) "👁️ Ko'z: OLDI KAMERA (Sizga qarayapti)" else "👁️ Ko'z: ORQA KAMERA (Atrofga qarayapti)"
        btnCamera.background = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(if (isFrontCamera) Color.parseColor("#1F6FEB") else Color.parseColor("#238636"))
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            setPadding(40, 60, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "HAMROH"
            setTextColor(Color.parseColor("#7FD1AE"))
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        })

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 10, 0, 20)
        }
        root.addView(statusView)

        btnCamera = Button(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(30, 24, 30, 24)
            setOnClickListener {
                toggleCamera()
            }
        }
        updateButtonUi()

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 24)
        }
        root.addView(btnCamera, btnParams)

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 16f
            setLineSpacing(4f, 1.1f)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }
}

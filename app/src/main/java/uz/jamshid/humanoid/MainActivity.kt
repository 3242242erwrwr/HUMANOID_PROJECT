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
import uz.jamshid.humanoid.brain.ApiKeyManager

/**
 * Hamroh AI Interfeysi:
 * - Rasmiy logotip
 * - KATTA VA YAQQOL "🔑 API KALITLAR MENYUSI" TUGMASI
 * - Kamera almashtirish (Oldi / Orqa)
 * - To'xtatish (O'chirish) va Yoqish
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnCamera: Button
    private lateinit var btnToggleService: Button
    private lateinit var btnApiKeys: Button

    private lateinit var keyManager: ApiKeyManager

    private var isFrontCamera = true
    private var isRunning = false

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
        keyManager = ApiKeyManager(this)
        buildUi()

        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            wake()
        } else {
            askPerms.launch(missing.toTypedArray())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    HumanoidState.status.collect { status ->
                        statusView.text = status
                        isRunning = status != "Uxlayapti" && status != "To'xtatildi"
                        updateToggleServiceButton()
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
            isRunning = true
            updateToggleServiceButton()
        } catch (e: Exception) {
            statusView.text = "Xizmatni yoqishda xato: ${e.message}"
        }
    }

    private fun stopService() {
        val intent = Intent(this, HumanoidService::class.java)
        try {
            stopService(intent)
            isRunning = false
            HumanoidState.status("To'xtatildi")
            updateToggleServiceButton()
        } catch (_: Exception) {}
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
        btnCamera.text = if (isFrontCamera) "👁️ Ko'z: OLDI KAMERA" else "👁️ Ko'z: ORQA KAMERA"
        btnCamera.background = GradientDrawable().apply {
            cornerRadius = 20f
            setColor(if (isFrontCamera) Color.parseColor("#1F6FEB") else Color.parseColor("#238636"))
        }
    }

    private fun updateToggleServiceButton() {
        if (!::btnToggleService.isInitialized) return
        btnToggleService.text = if (isRunning) "🛑 TO'XTATISH (O'CHIRISH)" else "▶️ ISHGA TUSHIRISH (YOQISH)"
        btnToggleService.background = GradientDrawable().apply {
            cornerRadius = 20f
            setColor(if (isRunning) Color.parseColor("#DA3633") else Color.parseColor("#238636"))
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            setPadding(36, 40, 36, 30)
        }

        root.addView(TextView(this).apply {
            text = "HAMROH AI"
            setTextColor(Color.parseColor("#7FD1AE"))
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 8)
        })

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 14)
        }
        root.addView(statusView)

        // 1. KATTA VA YAQQOL KO'RINADIGAN "API KALITLAR MENYUSI" TUGMASI
        btnApiKeys = Button(this).apply {
            text = "🔑 API KALITLAR MENYUSI"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(Color.parseColor("#6E40C9")) // Chiroyli binafsha rang
            }
            setPadding(20, 18, 20, 18)
            setOnClickListener {
                ApiKeysDialog(this@MainActivity, keyManager) {
                    if (isRunning) {
                        stopService()
                        wake()
                    }
                }.show()
            }
        }
        root.addView(btnApiKeys, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) })

        // 2. Kamera tugmasi
        btnCamera = Button(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(20, 18, 20, 18)
            setOnClickListener { toggleCamera() }
        }
        updateButtonUi()
        root.addView(btnCamera, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 12) })

        // 3. To'xtatish / Yoqish tugmasi
        btnToggleService = Button(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(20, 18, 20, 18)
            setOnClickListener {
                if (isRunning) stopService() else wake()
            }
        }
        updateToggleServiceButton()
        root.addView(btnToggleService, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 16) })

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 14f
            setLineSpacing(4f, 1.1f)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }
}

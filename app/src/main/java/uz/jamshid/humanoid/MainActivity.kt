package uz.jamshid.humanoid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Hamroh AI Interfeysi:
 * - Yangi rasmiy logotip
 * - Maxsus API Kalit kiritish va saqlash funksiyasi
 * - Kamera almashtirish (Oldi / Orqa)
 * - To'xtatish (O'chirish) va Yoqish
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnCamera: Button
    private lateinit var btnToggleService: Button
    private lateinit var etApiKey: EditText
    private lateinit var btnSaveKey: Button

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
        })

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 6, 0, 12)
        }
        root.addView(statusView)

        // API KALIT KIRITISH BLOKI
        val keyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 14)
        }

        val prefs = getSharedPreferences("hamroh_prefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("custom_api_key", "")

        etApiKey = EditText(this).apply {
            hint = if (savedKey.isNullOrBlank()) "Gemini API Kalit (bo'sh = standart)" else "Kalit saqlangan (o'zgartirish mumkin)"
            setHintTextColor(Color.parseColor("#6E7681"))
            setTextColor(Color.WHITE)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#161B22"))
                setStroke(2, Color.parseColor("#30363D"))
            }
            setPadding(24, 16, 24, 16)
        }
        keyLayout.addView(etApiKey, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        btnSaveKey = Button(this).apply {
            text = "Saqlash"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#238636"))
            }
            setOnClickListener {
                val input = etApiKey.text.toString().trim()
                prefs.edit().putString("custom_api_key", input).apply()
                Toast.makeText(this@MainActivity, if (input.isBlank()) "Standart kalitga qaytarildi" else "Yangi API kalit saqlandi!", Toast.LENGTH_SHORT).show()
                // Kalit o'zgarganda xizmatni yangilash
                if (isRunning) {
                    stopService()
                    wake()
                }
            }
        }
        val btnSaveParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(12, 0, 0, 0) }
        keyLayout.addView(btnSaveKey, btnSaveParams)

        root.addView(keyLayout)

        // 1. Kamera tugmasi
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

        // 2. To'xtatish / Yoqish tugmasi
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

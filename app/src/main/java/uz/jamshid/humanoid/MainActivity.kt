package uz.jamshid.humanoid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
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
 * Yagona ekran — faqat kuzatish uchun. Hech qanday tugma yo'q:
 * ruxsatlar berilishi bilan gumanoid o'zi uyg'onadi.
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

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
            statusView.text = "GEMINI_API_KEY local.properties faylida yo'q"
            return
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) wake() else askPerms.launch(missing.toTypedArray())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { HumanoidState.status.collect { statusView.text = it } }
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
            statusView.text = "Mikrofonga ruxsat kerak — usiz eshita olmayman"
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, HumanoidService::class.java))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
            setPadding(40, 80, 40, 40)
        }
        root.addView(TextView(this).apply {
            text = "HAMROH"
            setTextColor(Color.parseColor("#7FD1AE"))
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        })
        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 16, 0, 32)
        }
        root.addView(statusView)
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

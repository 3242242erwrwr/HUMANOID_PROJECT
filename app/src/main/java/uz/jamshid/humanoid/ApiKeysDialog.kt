package uz.jamshid.humanoid

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import uz.jamshid.humanoid.brain.ApiKeyManager

/**
 * API Kalitlarni boshqarish oynasi (Menyu):
 * - Kalitlar ro'yxatini ko'rish (qaysi biri dam olayotgani)
 * - Yangi kalit qo'shish
 * - Keraksizini o'chirish
 */
class ApiKeysDialog(
    private val ctx: Context,
    private val keyManager: ApiKeyManager,
    private val onKeysChanged: () -> Unit
) {

    fun show() {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B22"))
            setPadding(36, 36, 36, 36)
        }

        root.addView(TextView(ctx).apply {
            text = "🔑 GEMINI API KALITLARI"
            setTextColor(Color.parseColor("#7FD1AE"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 20)
        })

        // Yangi kalit kiritish bloki
        val addLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }

        val etNewKey = EditText(ctx).apply {
            hint = "Yangi API kalitni joylang..."
            setHintTextColor(Color.parseColor("#6E7681"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = 14f
                setColor(Color.parseColor("#0E1116"))
                setStroke(2, Color.parseColor("#30363D"))
            }
            setPadding(20, 16, 20, 16)
        }
        addLayout.addView(etNewKey, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val btnAdd = Button(ctx).apply {
            text = "+ Qo'shish"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = 14f
                setColor(Color.parseColor("#238636"))
            }
            setPadding(24, 0, 24, 0)
        }
        val btnAddParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ).apply { setMargins(12, 0, 0, 0) }
        addLayout.addView(btnAdd, btnAddParams)

        root.addView(addLayout)

        // Mavjud kalitlar ro'yxati
        val keysContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun refreshList() {
            keysContainer.removeAllViews()
            val keys = keyManager.getAllKeys()
            for ((idx, k) in keys.withIndex()) {
                val item = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16, 14, 16, 14)
                    background = GradientDrawable().apply {
                        cornerRadius = 12f
                        setColor(Color.parseColor("#0E1116"))
                    }
                }

                val isResting = keyManager.isKeyResting(k)
                val masked = if (k.length > 14) "${k.take(6)}...${k.takeLast(6)}" else k
                val statusText = if (isResting) "⏳ Limitda" else "✅ Faol"

                val tvInfo = TextView(ctx).apply {
                    text = "#${idx + 1}: $masked ($statusText)"
                    setTextColor(if (isResting) Color.parseColor("#D29922") else Color.parseColor("#E6EDF3"))
                    textSize = 13f
                }
                item.addView(tvInfo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                val btnDel = Button(ctx).apply {
                    text = "✕"
                    textSize = 12f
                    setTextColor(Color.parseColor("#F85149"))
                    background = null
                    setOnClickListener {
                        if (keys.size <= 1) {
                            Toast.makeText(ctx, "Kamida 1 ta kalit qolishi shart!", Toast.LENGTH_SHORT).show()
                        } else {
                            keyManager.removeKey(k)
                            refreshList()
                            onKeysChanged()
                        }
                    }
                }
                item.addView(btnDel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                val itemParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }

                keysContainer.addView(item, itemParams)
            }
        }

        refreshList()

        btnAdd.setOnClickListener {
            val key = etNewKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(ctx, "Kalit bo'sh bo'lishi mumkin emas", Toast.LENGTH_SHORT).show()
            } else {
                if (keyManager.addKey(key)) {
                    Toast.makeText(ctx, "Yangi kalit qo'shildi!", Toast.LENGTH_SHORT).show()
                    etNewKey.setText("")
                    refreshList()
                    onKeysChanged()
                } else {
                    Toast.makeText(ctx, "Bu kalit allaqachon mavjud", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val scroll = ScrollView(ctx).apply { addView(keysContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 500))

        AlertDialog.Builder(ctx)
            .setView(root)
            .setPositiveButton("Yopish", null)
            .show()
    }
}

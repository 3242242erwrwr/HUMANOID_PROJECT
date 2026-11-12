package uz.jamshid.humanoid.memory

import android.content.Context
import uz.jamshid.humanoid.brain.GeminiRest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * XOTIRA — har bir suhbat jurnalga yoziladi, vaqti-vaqti bilan Gemini uni
 * "uzoq muddatli xotira"ga jamlaydi (faktlar, rejalar, joylar, kayfiyat).
 */
class Memory(ctx: Context, private val rest: GeminiRest) {

    private val memFile = File(ctx.filesDir, "xotira.txt")
    private val logFile = File(ctx.filesDir, "suhbat_jurnali.txt")
    private val pending = StringBuilder()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun longTerm(): String = if (memFile.exists()) memFile.readText() else ""

    fun recentLog(maxLines: Int = 30): String =
        if (logFile.exists()) logFile.readLines().takeLast(maxLines).joinToString("\n") else ""

    @Synchronized
    fun record(who: String, text: String) {
        val line = "[${stamp.format(Date())}] $who: ${text.trim()}\n"
        logFile.appendText(line)
        pending.append(line)
        trimLog()
    }

    @Synchronized
    fun recordEvent(event: String) {
        val line = "[${stamp.format(Date())}] (hodisa) $event\n"
        logFile.appendText(line)
        pending.append(line)
    }

    private fun trimLog() {
        if (logFile.length() > 2_000_000) {
            val keep = logFile.readLines().takeLast(5000)
            logFile.writeText(keep.joinToString("\n", postfix = "\n"))
        }
    }

    /** Yangi suhbatlarni uzoq muddatli xotiraga qo'shib, qayta jamlaydi. */
    suspend fun consolidate() {
        val chunk = synchronized(this) {
            if (pending.length < 150) return
            pending.toString().also { pending.setLength(0) }
        }
        val prompt = """
            Sen bir tirik hamrohning xotirasisan. Quyida MAVJUD XOTIRA va YANGI SUHBAT bor.
            Ularni birlashtirib, yangilangan xotirani yoz. Faqat suhbatda ANIQ aytilgan yoki aniq kuzatilgan narsalarni yoz —
            hech narsani taxmin qilma, o'zingdan qo'shma (masalan, odamning ismi yoki kasbi aytilmagan bo'lsa, yozma).
            Saqlash kerak: odam haqida bilib olingan faktlar, odatlari, yaqinlari, rejalari va va'dalar (sanasi bilan),
            joylar, muhim voqealar, kayfiyati, yoqadigan/yoqmaydigan mavzular, siz o'rtangizdagi munosabat.
            Eskirgan yoki takroriy narsalarni olib tashla. Eng ko'pi 500 so'z, o'zbek tilida, qisqa punktlarda.
            Faqat xotira matnini qaytar.

            MAVJUD XOTIRA:
            ${longTerm().ifBlank { "(hali bo'sh)" }}

            YANGI SUHBAT (bugun ${stamp.format(Date())}):
            $chunk
        """.trimIndent()
        val out = rest.generate(GeminiRest.SMART, listOf(rest.textPart(prompt)))
        if (out.isNullOrBlank()) {
            synchronized(this) { pending.insert(0, chunk) } // keyingi safar qayta urinamiz
        } else {
            memFile.writeText(out.trim())
        }
    }
}

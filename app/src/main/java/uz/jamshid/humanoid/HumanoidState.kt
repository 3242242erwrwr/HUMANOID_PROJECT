package uz.jamshid.humanoid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ekranda ko'rsatish uchun umumiy holat. */
object HumanoidState {
    private val _status = MutableStateFlow("Uxlayapti")
    val status: StateFlow<String> = _status

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun status(s: String) { _status.value = s }

    fun add(line: String) {
        synchronized(this) { _log.value = (_log.value + "${fmt.format(Date())}  $line").takeLast(150) }
    }
}

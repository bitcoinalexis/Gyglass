package com.angylabs.gyglass.log

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_LINES = 3000
    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private var writer: BufferedWriter? = null
    var file: File? = null
        private set

    @Synchronized
    fun init(context: Context) {
        if (writer != null) return
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, "gyglass_${fileFormat.format(Date())}.txt")
        file = f
        writer = BufferedWriter(FileWriter(f, true))
        log("APP", "Log iniciado en ${f.absolutePath}")
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${tsFormat.format(Date())} [$tag] $message"
        try {
            writer?.apply {
                write(line)
                newLine()
                flush()
            }
        } catch (_: Exception) {
        }
        val current = _lines.value
        val next = if (current.size >= MAX_LINES) current.drop(current.size - MAX_LINES + 1) + line else current + line
        _lines.value = next
    }

    @Synchronized
    fun clearView() {
        _lines.value = emptyList()
    }
}

object Hex {
    fun toHex(data: ByteArray?): String =
        data?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: ""

    fun toAscii(data: ByteArray?): String =
        data?.map { val c = it.toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }?.joinToString("") ?: ""

    fun describe(data: ByteArray?): String =
        if (data == null || data.isEmpty()) "(vacio)" else "len=${data.size} hex=[${toHex(data)}] ascii=\"${toAscii(data)}\""

    fun parse(text: String): ByteArray? {
        val clean = text.replace("0x", "", ignoreCase = true).replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

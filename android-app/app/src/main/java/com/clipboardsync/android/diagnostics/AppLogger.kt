package com.clipboardsync.android.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

data class LogEntry(
    val timestampUtc: String,
    val level: String,
    val message: String
)

class AppLogger {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries = _entries.asStateFlow()

    fun info(message: String) = append("INFO", message)

    fun warn(message: String) = append("WARN", message)

    fun error(message: String, throwable: Throwable? = null) {
        val suffix = throwable?.let { ": ${it.message}" }.orEmpty()
        append("ERROR", message + suffix)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private fun append(level: String, message: String) {
        Log.d("ClipboardSync", "[$level] $message")
        val next = (listOf(LogEntry(Instant.now().toString(), level, message)) + _entries.value).take(200)
        _entries.value = next
    }
}


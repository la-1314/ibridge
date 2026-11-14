package com.ibridge.otg.core

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class LogLevel(val label: String, val short: String) {
    VERBOSE("详细", "V"),
    INFO("信息", "I"),
    WARN("警告", "W"),
    ERROR("错误", "E"),
}

data class LogEntry(
    val seq: Long,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun format(): String {
        val time = TIME_FORMAT.format(
            java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault()),
        )
        return "$time ${level.short}/$tag: $message"
    }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * In-memory + on-disk session log. The in-memory buffer is bounded; the session file keeps
 * everything that happened during this process lifetime so it can be exported.
 */
class LogBus(context: Context) {

    private val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
    private val sessionFile = File(
        logsDir,
        "ibridge-" + LocalDateTime.now().format(FILE_TIME_FORMAT) + ".log",
    )

    private var writer: BufferedWriter = BufferedWriter(FileWriter(sessionFile, true))
    private val lock = Any()
    private var sequence = 0L

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    init {
        pruneOldSessions()
    }

    fun v(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun log(level: LogLevel, tag: String, message: String) {
        val clean = ANSI_REGEX.replace(message, "").trimEnd()
        if (clean.isEmpty()) return
        val entry: LogEntry
        synchronized(lock) {
            entry = LogEntry(++sequence, System.currentTimeMillis(), level, tag, clean)
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
            runCatching {
                writer.write(entry.format())
                writer.newLine()
                writer.flush()
            }
            Log.println(level.toLogcatPriority(), "iBridge/$tag", clean)
        }
    }

    /** Logs a multi-line tool output chunk, skipping blank lines. */
    fun chunk(level: LogLevel, tag: String, text: String) {
        text.lineSequence()
            .map { ANSI_REGEX.replace(it, "").trimEnd() }
            .filter { it.isNotBlank() }
            .take(400)
            .forEach { log(level, tag, it) }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            runCatching {
                writer.flush()
                writer.close()
            }
            runCatching { sessionFile.writeText("") }
            writer = BufferedWriter(FileWriter(sessionFile, true))
        }
    }

    fun snapshot(): String = synchronized(lock) {
        if (sessionFile.exists() && sessionFile.length() > 0) {
            sessionFile.readText()
        } else {
            _entries.value.joinToString("\n") { it.format() }
        }
    }

    fun saveTo(uri: Uri, resolver: android.content.ContentResolver): Boolean = runCatching {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { out ->
            out.write(snapshot())
        } ?: return false
        true
    }.getOrDefault(false)

    val currentSessionFile: File get() = sessionFile

    private fun pruneOldSessions() {
        runCatching {
            logsDir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(10)
                ?.forEach { it.delete() }
        }
    }

    private fun LogLevel.toLogcatPriority(): Int = when (this) {
        LogLevel.VERBOSE -> Log.VERBOSE
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    companion object {
        private const val MAX_ENTRIES = 2500
        private val FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        private val ANSI_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    }
}

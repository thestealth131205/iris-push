package de.letheapp.iris

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal on-device log, persisted to internal storage (no permission
 * needed) so that crashes which happen "out of nowhere" - and the
 * background service churn that typically precedes them (reconnects,
 * daemon handoffs) - leave a trail without needing adb/logcat access to
 * the device. Surfaced via MainActivity's three-dot "Log" menu entry.
 */
object IrisLog {
    private const val EVENT_FILE_NAME = "iris_log.txt"
    private const val CRASH_FILE_NAME = "iris_crashes.txt"
    private const val MAX_SIZE_BYTES = 256 * 1024
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        appendLine(eventFile(context), "[${timestamp()}] $tag: $message")
    }

    @Synchronized
    fun logCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        appendLine(crashFile(context), "[${timestamp()}] CRASH in Thread \"${thread.name}\":\n$sw")
        appendLine(eventFile(context), "[${timestamp()}] CRASH: ${throwable.javaClass.name}: ${throwable.message}")
    }

    fun readAll(context: Context): String {
        val crashes = crashFile(context).takeIf { it.exists() }?.readText().orEmpty()
        val events = eventFile(context).takeIf { it.exists() }?.readText().orEmpty()
        return buildString {
            append("=== Crash-Logs ===\n")
            append(if (crashes.isBlank()) "(keine Abstürze aufgezeichnet)\n" else crashes)
            append("\n=== Ereignis-Log ===\n")
            append(if (events.isBlank()) "(leer)\n" else events)
        }
    }

    fun clear(context: Context) {
        try { eventFile(context).writeText("") } catch (_: Exception) {}
        try { crashFile(context).writeText("") } catch (_: Exception) {}
    }

    private fun eventFile(context: Context) = File(context.filesDir, EVENT_FILE_NAME)
    private fun crashFile(context: Context) = File(context.filesDir, CRASH_FILE_NAME)

    private fun timestamp() = timestampFormat.format(Date())

    private fun appendLine(file: File, text: String) {
        try {
            file.appendText(text + "\n")
            if (file.length() > MAX_SIZE_BYTES) trim(file)
        } catch (_: Exception) {
            // best-effort only - logging must never be the thing that crashes the app
        }
    }

    /** Drops the oldest half once a log file grows past [MAX_SIZE_BYTES]. */
    private fun trim(file: File) {
        try {
            val lines = file.readLines()
            file.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }
}

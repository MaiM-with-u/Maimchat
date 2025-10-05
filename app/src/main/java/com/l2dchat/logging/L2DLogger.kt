package com.l2dchat.logging

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight logging facility supporting module-scoped loggers, in-memory buffers for UI viewing,
 * throttling to avoid high-frequency spam, and on-disk persistence with rotation.
 */
object L2DLogger {
    private const val MAX_BUFFER_SIZE = 400
    private const val LOG_DIR = "logs"
    private const val LOG_FILE_PREFIX = "l2dchat"
    private const val LOG_FILE_EXTENSION = ".log"
    private const val LOG_FILE_MAX_BYTES = 512 * 1024L // 512KB per file
    private const val LOG_FILE_ROTATION_COUNT = 3

    private val isInitialized = AtomicBoolean(false)
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rateLimiter = LogRateLimiter()

    private val bufferLock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_BUFFER_SIZE)
    private val entriesState = MutableStateFlow<List<LogEntry>>(emptyList())

    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    /** Initialise the logger with an application context. Safe to call multiple times. */
    fun init(context: Context) {
        if (isInitialized.compareAndSet(false, true)) {
            appContext = context.applicationContext
            scope.launch { ensureLogDirectory() }
        }
    }

    /** Returns a module-specific logger instance. */
    fun module(module: LogModule): ModuleLogger = ModuleLogger(module)

    /** Snapshot of recent log entries for UI consumption. */
    fun entries(): StateFlow<List<LogEntry>> = entriesState.asStateFlow()

    /** Optional: provide the most recent log file for sharing/export. */
    fun latestLogFile(): File? {
        if (!isInitialized.get()) return null
        val dir = File(appContext.filesDir, LOG_DIR)
        val primary = File(dir, LOG_FILE_PREFIX + LOG_FILE_EXTENSION)
        return primary.takeIf { it.exists() && it.isFile }
    }

    /** Clear in-memory buffer and truncate disk logs. */
    suspend fun clearLogs() {
        if (!isInitialized.get()) return
        withContext(Dispatchers.IO) {
            synchronized(bufferLock) {
                buffer.clear()
                entriesState.value = emptyList()
            }
            val dir = File(appContext.filesDir, LOG_DIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    internal fun log(
            module: LogModule,
            level: LogLevel,
            message: String,
            throwable: Throwable?,
            throttleMs: Long?,
            throttleKey: String?
    ) {
        if (!isInitialized.get()) {
            // Even if not initialised yet, still forward to Android log for debugging.
            forwardToLogcat(module, level, message, throwable)
            return
        }
        val effectiveThrottle = throttleMs ?: rateLimiter.defaultWindow(level)
        if (!rateLimiter.shouldLog(module, level, throttleKey ?: message, effectiveThrottle)) {
            return
        }
        forwardToLogcat(module, level, message, throwable)
        val entry = LogEntry(
                timestamp = System.currentTimeMillis(),
                module = module,
                level = level,
                message = message,
                throwable = throwable?.stackTraceToString()
        )
        pushToBuffer(entry)
        scope.launch { persist(entry) }
    }

    private fun forwardToLogcat(
            module: LogModule,
            level: LogLevel,
            message: String,
            throwable: Throwable?
    ) {
        when (level) {
            LogLevel.VERBOSE -> Log.v(module.tag, message, throwable)
            LogLevel.DEBUG -> Log.d(module.tag, message, throwable)
            LogLevel.INFO -> Log.i(module.tag, message, throwable)
            LogLevel.WARN -> Log.w(module.tag, message, throwable)
            LogLevel.ERROR -> Log.e(module.tag, message, throwable)
        }
    }

    private fun pushToBuffer(entry: LogEntry) {
        synchronized(bufferLock) {
            if (buffer.size >= MAX_BUFFER_SIZE) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            entriesState.value = buffer.toList()
        }
    }

    private suspend fun persist(entry: LogEntry) {
        ensureLogDirectory()
        val dir = File(appContext.filesDir, LOG_DIR)
        val primary = File(dir, LOG_FILE_PREFIX + LOG_FILE_EXTENSION)
        val line = formatLine(entry)
        try {
            if (primary.length() + line.length + 1 > LOG_FILE_MAX_BYTES) {
                rotateLogs(dir)
            }
            primary.appendText(line + "\n")
        } catch (io: IOException) {
            Log.w("L2DLogger", "Failed to persist log entry", io)
        }
    }

    private suspend fun ensureLogDirectory() {
        withContext(Dispatchers.IO) {
            val dir = File(appContext.filesDir, LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
        }
    }

    private fun rotateLogs(dir: File) {
        for (i in LOG_FILE_ROTATION_COUNT downTo 1) {
            val src = if (i == 1) {
                File(dir, LOG_FILE_PREFIX + LOG_FILE_EXTENSION)
            } else {
                File(dir, LOG_FILE_PREFIX + "_" + (i - 1) + LOG_FILE_EXTENSION)
            }
            val dst = File(dir, LOG_FILE_PREFIX + "_" + i + LOG_FILE_EXTENSION)
            if (src.exists()) {
                if (dst.exists()) dst.delete()
                src.renameTo(dst)
            }
        }
    }

    private fun formatLine(entry: LogEntry): String {
        val time = dateFormat.get().format(Date(entry.timestamp))
    val sanitizedMessage = entry.message.replace("\n", "\\n")
    val throwable = entry.throwable?.replace("\n", "\\n") ?: ""
    return listOf(time, entry.level.name, entry.module.storageKey, sanitizedMessage, throwable)
                .joinToString("|")
    }
}

/** Levels used by [L2DLogger]. */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/** Application log domains. */
enum class LogModule(val tag: String) {
    MAIN_VIEW("L2DMain"),
    WALLPAPER("L2DWallpaper"),
    CHAT("L2DChat"),
    WIDGET("L2DWidget");

    internal val storageKey: String = name
}

/** Structured entry emitted by the logger. */
data class LogEntry(
        val timestamp: Long,
        val module: LogModule,
        val level: LogLevel,
        val message: String,
        val throwable: String? = null
)

/** Provides throttling to avoid high-frequency spam from loops. */
private class LogRateLimiter {
    private val lastLogAt = ConcurrentHashMap<String, Long>()

    fun shouldLog(
            module: LogModule,
            level: LogLevel,
            rawKey: String?,
            throttleMs: Long
    ): Boolean {
        if (throttleMs <= 0) return true
        val key = buildString {
            append(module.storageKey)
            append(':')
            append(level.name)
            append(':')
            append(rawKey ?: "")
        }
        val now = SystemClock.elapsedRealtime()
        val previous = lastLogAt[key]
        return if (previous == null || now - previous >= throttleMs) {
            lastLogAt[key] = now
            true
        } else {
            false
        }
    }

    fun defaultWindow(level: LogLevel): Long =
            when (level) {
                LogLevel.ERROR -> 0L
                LogLevel.WARN -> 250L
                LogLevel.INFO -> 0L
                LogLevel.DEBUG -> 0L
                LogLevel.VERBOSE -> 500L
            }
}

/** Module-specific logger facade. */
class ModuleLogger internal constructor(private val module: LogModule) {
    fun verbose(
            message: String,
            throwable: Throwable? = null,
            throttleMs: Long? = null,
            throttleKey: String? = null
    ) = log(LogLevel.VERBOSE, message, throwable, throttleMs, throttleKey)

    fun verbose(
        message: () -> String,
            throwable: Throwable? = null,
            throttleMs: Long? = null,
            throttleKey: String? = null
    ) = log(LogLevel.VERBOSE, message(), throwable, throttleMs, throttleKey)

    fun debug(
            message: String,
            throwable: Throwable? = null,
            throttleMs: Long? = null,
            throttleKey: String? = null
    ) = log(LogLevel.DEBUG, message, throwable, throttleMs, throttleKey)

    fun debug(
        message: () -> String,
            throwable: Throwable? = null,
            throttleMs: Long? = null,
            throttleKey: String? = null
    ) = log(LogLevel.DEBUG, message(), throwable, throttleMs, throttleKey)

    fun info(
            message: String,
            throwable: Throwable? = null,
            throttleMs: Long? = null,
            throttleKey: String? = null
    ) = log(LogLevel.INFO, message, throwable, throttleMs, throttleKey)

    fun info(
        message: () -> String,
            throwable: Throwable? = null,
            throttleMs: Long? = null,
            throttleKey: String? = null
    ) = log(LogLevel.INFO, message(), throwable, throttleMs, throttleKey)

    fun warn(
            message: String,
            throwable: Throwable? = null,
            throttleMs: Long? = null,
            throttleKey: String? = null
    ) = log(LogLevel.WARN, message, throwable, throttleMs, throttleKey)

    fun warn(
        message: () -> String,
            throwable: Throwable? = null,
            throttleMs: Long? = null,
            throttleKey: String? = null
    ) = log(LogLevel.WARN, message(), throwable, throttleMs, throttleKey)

    fun error(
            message: String,
            throwable: Throwable? = null,
            throttleMs: Long? = null,
            throttleKey: String? = null
    ) = log(LogLevel.ERROR, message, throwable, throttleMs, throttleKey)

    fun error(
        message: () -> String,
            throwable: Throwable? = null,
            throttleMs: Long? = null,
            throttleKey: String? = null
    ) = log(LogLevel.ERROR, message(), throwable, throttleMs, throttleKey)

    @PublishedApi
    internal fun log(
            level: LogLevel,
            message: String,
            throwable: Throwable?,
            throttleMs: Long?,
            throttleKey: String?
    ) {
        L2DLogger.log(module, level, message, throwable, throttleMs, throttleKey)
    }
}

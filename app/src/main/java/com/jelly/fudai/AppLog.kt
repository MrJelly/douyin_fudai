package com.jelly.fudai

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AppLog(context: Context) {
    companion object {
        private const val MAX_LOG_BYTES = 4L * 1024L * 1024L
        private const val RETENTION_MS = 24L * 60L * 60L * 1000L
        private val DEBUG_PREFIXES = arrayOf(
            "[FSM]", "[截图", "[Root", "[内存监控]", "[自身内存保护]",
            "[无障碍Node节点]", "[任务节点]", "[点击]", "[手势恢复]",
            "[图标主识别]", "[多帧按钮]", "[第一遍识别]", "[第二遍识别]",
            "[福袋候选复试]", "[福袋候选排除]", "[OCR降维补救]",
            "[截图边界修正]", "[福袋扫描测试]", "[标志自愈]", "[假死监控]"
        )
    }
    fun interface Listener { fun onLine(line: String) }
    private val file = File(File(context.filesDir, "logs").apply { mkdirs() }, "latest.log")
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).apply { isLenient = false }
    private val legacyParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).apply { isLenient = false }
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2_048),
        { task -> Thread(task, "app-log-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
    private var listener: Listener? = null
    private var lastUserMessage = ""
    private var lastUserMessageAt = 0L
    init {
        file.parentFile?.listFiles()?.filter { it.name != file.name }?.forEach { it.delete() }
        pruneNow()
    }
    @Synchronized fun setListener(value: Listener?) { listener = value }
    @Synchronized fun clear() { enqueue { file.delete() } }
    @Synchronized fun maintain() {
        enqueue {
            file.parentFile?.listFiles()
                ?.filter { it.name != file.name }
                ?.forEach { it.delete() }
            pruneNow()
        }
    }
    /** Existing callers are routed automatically; diagnostic chatter never reaches the panel. */
    @Synchronized fun write(message: String) {
        if (isDebugMessage(message)) debug(message) else user(message)
    }

    @Synchronized fun user(message: String) {
        val clean = userMessage(message)
        if (clean.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (clean == lastUserMessage && now - lastUserMessageAt < 3_000L) return
        lastUserMessage = clean
        lastUserMessageAt = now
        val userLine = "${format.format(Date())} $clean"
        append(userLine)
        listener?.onLine(userLine)
    }

    /** Diagnostic detail: kept in the log file, never pushed to the user panel. */
    @Synchronized fun debug(message: String) {
        append("${format.format(Date())} [调试] $message")
    }

    private fun userMessage(message: String): String {
        val value = message.trim()
        if (value.startsWith("[调试]")) return ""
        val end = value.indexOf(']')
        val body = if (value.startsWith("[") && end in 1..18) value.substring(end + 1).trim() else value
        return body
            .replace(Regex("（?OCR=.*?[）)]?$"), "")
            .replace(Regex("坐标[-0-9.,@：:]+"), "")
            .replace(Regex("ID=[^，； ]+"), "")
            .replace("绝对严禁", "不会")
            .replace("坚决锁死", "将留在")
            .replace("绝对锁死", "将留在")
            .replace("终极自救", "自动恢复")
            .replace("硬拦截", "安全检查")
            .replace("终极熔断", "安全保护")
            .replace("打入探针点击救回", "准备确认")
            .replace("探针打入放行", "准备确认")
            .replace(Regex("[；，]\\s*[；，]+"), "；")
            .trim(' ', '；', '，')
    }

    private fun isDebugMessage(message: String): Boolean {
        val value = message.trim()
        return DEBUG_PREFIXES.any(value::startsWith) ||
            value.contains("Bounds", ignoreCase = true) ||
            value.contains("PSS=") ||
            value.contains("错误码=") ||
            value.contains("viewId", ignoreCase = true)
    }
    private fun append(line: String) = enqueue {
        if (file.length() > MAX_LOG_BYTES) pruneNow()
        appendNow(line)
    }

    /** Keep a rolling diagnostic window so stopping a task never destroys evidence. */
    private fun pruneNow() {
        if (!file.exists()) return
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        try {
            val kept = file.readLines(StandardCharsets.UTF_8).filter { line ->
                val timestamp = parseTimestamp(line)
                timestamp == null || timestamp >= cutoff
            }
            file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // Logging must never interrupt automation. A later maintenance pass retries.
        }
    }

    private fun parseTimestamp(line: String): Long? {
        if (line.length >= 23) {
            try { return parser.parse(line.substring(0, 23))?.time } catch (_: Exception) {}
        }
        // Upgrade logs written by builds that only prefixed HH:mm:ss.SSS.
        if (line.length >= 12 && line[2] == ':' && line[5] == ':' && line[8] == '.') {
            val now = System.currentTimeMillis()
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(now))
            return try {
                var value = legacyParser.parse("$day ${line.substring(0, 12)}")?.time ?: return null
                if (value > now + 60_000L) value -= 24L * 60L * 60L * 1000L
                value
            } catch (_: Exception) { null }
        }
        return null
    }

    private fun appendNow(line: String) {
        try {
            FileOutputStream(file, true).use {
                it.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            }
        } catch (_: Exception) {
        }
    }

    private fun enqueue(action: () -> Unit) {
        if (!writer.isShutdown) writer.execute(action)
    }

    fun close() {
        writer.shutdown()
        try {
            if (!writer.awaitTermination(500L, TimeUnit.MILLISECONDS)) writer.shutdownNow()
        } catch (_: InterruptedException) {
            writer.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}

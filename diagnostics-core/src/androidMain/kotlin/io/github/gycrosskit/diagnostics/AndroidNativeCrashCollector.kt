package io.github.gycrosskit.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.InputStream
import java.io.StringWriter
import java.util.Base64

/**
 * 显式准入后 start：Android 11+ 下次启动导入系统 native crash 历史；不安装 signal handler 或上传。
 * Android 12+ 系统保留的 trace 是 tombstone protobuf，报告保存有界 Base64 原文，不当作 UTF-8 栈解析。
 * 每个 Store 只能有一个实例，宿主先 close collector 再 close Store；close 不等待无期限系统 trace I/O，
 * 迟到数据会被丢弃。文件落盘/通知仍串行完成，通知必须轻量，不同步等待其他线程 close。
 */
class AndroidNativeCrashCollector internal constructor(
    private val store: DiagnosticStore,
    private val history: () -> Sequence<NativeCrashExit>,
    private val supported: Boolean,
    onReportStored: ((ReportKind) -> Unit)?,
) : java.io.Closeable {
    constructor(context: Context, store: DiagnosticStore, onReportStored: ((ReportKind) -> Unit)? = null) :
        this(store, { nativeCrashHistory(context.applicationContext) }, Build.VERSION.SDK_INT >= 30, onReportStored)

    private val gate = Any()
    private val reports = ReportRecorder(store, onReportStored)
    private val marker = File(store.storageDirectory, "android-native-crash-history")
    @Volatile private var closed = false
    private var worker: Thread? = null

    /** 旧系统返回 false；每个实例只启动一次，不在调用线程读系统历史或文件。 */
    fun start(): Boolean = synchronized(gate) {
        if (closed || !supported) return@synchronized false
        if (worker != null) return@synchronized true
        worker = Thread({ runCatching { importHistory() } }, "native-crash-history-import").apply {
            isDaemon = true
            start()
        }
        true
    }

    private fun importHistory() {
        val known = if (marker.isFile) marker.inputStream().use {
            it.readBounded(64 * 65).toString(Charsets.US_ASCII).lineSequence()
                .filter { id -> id.matches(Regex("[a-f0-9]{64}")) }.toMutableSet()
        } else linkedSetOf()
        for (exit in history().take(16)) {
            if (closed || Thread.currentThread().isInterrupted) return
            if (exit.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) continue
            val id = diagnosticFingerprint("${exit.timestamp}\n${exit.pid}\n${exit.processName}\n${exit.reason}".toByteArray())
            if (id in known) continue
            val output = StringWriter()
            val writer = BoundedReportWriter(output, store.limits.maxReportBytes, "\n[NATIVE CRASH REPORT TRUNCATED]\n")
            writer.appendLine("source=Android ApplicationExitInfo; reason=CRASH_NATIVE")
            writer.appendLine("timestamp=${exit.timestamp}; pid=${exit.pid}; process=${exit.processName}")
            writer.appendLine("description=${exit.description}")
            val trace = try { exit.trace()?.use { it.readBounded(192 * 1024) } } catch (_: Exception) { null }
            if (closed || Thread.currentThread().isInterrupted) return
            if (trace == null) writer.appendLine("tombstone=unavailable") else {
                writer.appendLine("tombstone=protobuf;base64; inputLimit=196608 bytes")
                writer.appendLine(Base64.getEncoder().encodeToString(trace))
            }
            writer.finish()
            synchronized(gate) {
                if (closed) return
                if (!reports.record(ReportKind.CRASH, output.toString())) return@synchronized
                known += id
                // 有界去重独立于可确认删除的报告；文件失败保留报告，重启可能重导入该条。
                val temporary = File(marker.parentFile, "${marker.name}.tmp")
                try {
                    temporary.writeText(known.toList().takeLast(64).joinToString("\n"), Charsets.US_ASCII)
                    check(temporary.renameTo(marker))
                } finally { temporary.delete() }
            }
        }
    }

    override fun close(): Unit = synchronized(gate) {
        closed = true
        reports.close()
        worker?.interrupt()
    }
}

internal data class NativeCrashExit(
    val reason: Int,
    val timestamp: Long,
    val pid: Int,
    val processName: String,
    val description: String,
    val trace: () -> InputStream?,
)

@android.annotation.TargetApi(30)
private fun nativeCrashHistory(context: Context): Sequence<NativeCrashExit> =
    context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(null, 0, 16)
        .orEmpty().asSequence().map { exit ->
            NativeCrashExit(exit.reason, exit.timestamp, exit.pid, exit.processName.orEmpty(), exit.description.orEmpty()) {
                if (Build.VERSION.SDK_INT >= 31) exit.traceInputStream else null
            }
        }

/** 不多读一个字节探测 EOF，系统流没有可依赖的超时；退出检查在每个有界 chunk 之间执行。 */
internal fun InputStream.readBounded(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit && !Thread.currentThread().isInterrupted) {
        val count = read(buffer, offset, minOf(8192, limit - offset))
        if (count < 0) break
        check(count > 0) { "系统 trace 没有读取进展" }
        offset += count
    }
    return buffer.copyOf(offset)
}

package io.github.gycrosskit.diagnostics

import android.content.Context
import java.io.Writer
import java.io.PrintWriter

/** 使用 Android 不参与 Auto Backup 的私有目录；不安装任何采集器。 */
fun androidDiagnosticStore(context: Context, limits: DiagnosticLimits = DiagnosticLimits()): DiagnosticStore =
    DiagnosticStore(java.io.File(context.noBackupFilesDir, "gycrosskit-diagnostics").absolutePath, limits)

/** 仅记录 JVM 未捕获异常，不捕获 native signal/ANR。宿主明确创建并持有，close 撤回自己的 handler。 */
class AndroidCrashRecorder(
    store: DiagnosticStore,
    onReportStored: ((ReportKind) -> Unit)? = null,
) : java.io.Closeable {
    private val downstream = checkNotNull(Thread.getDefaultUncaughtExceptionHandler()) {
        "没有系统异常处理器，不能安装采集器"
    }.also { check(it !is RecordingHandler) { "进程已有 AndroidCrashRecorder" } }
    private val reports = ReportRecorder(store, onReportStored)
    private val handler = RecordingHandler(store, reports, downstream)
    init { Thread.setDefaultUncaughtExceptionHandler(handler) }
    override fun close() {
        reports.close()
        if (Thread.getDefaultUncaughtExceptionHandler() === handler) Thread.setDefaultUncaughtExceptionHandler(downstream)
    }
    private class RecordingHandler(
        private val store: DiagnosticStore,
        private val reports: ReportRecorder,
        private val downstream: Thread.UncaughtExceptionHandler,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                val output = StringBuilder()
                val limit = store.limits.maxReportBytes / 4
                val writer = object : Writer() {
                    override fun write(buffer: CharArray, offset: Int, length: Int) {
                        val count = minOf(length, limit - output.length)
                        if (count > 0) output.append(buffer, offset, count)
                        if (count < length) throw ReportFull()
                    }
                    override fun flush() = Unit
                    override fun close() = Unit
                }
                output.append("thread=${thread.name.take(256)}\n")
                try { error.printStackTrace(PrintWriter(writer)) } catch (_: ReportFull) { output.append("\n[truncated]\n") }
                reports.record(ReportKind.CRASH, output.toString())
            } catch (_: Throwable) {
                // 采集失败仍交给安装前的系统处理器结束进程。
            } finally {
                downstream.uncaughtException(thread, error)
            }
        }
    }
    private class ReportFull : RuntimeException()
}

/** 源码使用的 renameTo 在 Android/Linux 同文件系统上原子覆盖，避免 API 26 才有的 NIO。 */
internal actual fun platformMove(source: kotlinx.io.files.Path, destination: kotlinx.io.files.Path) {
    check(java.io.File(source.toString()).renameTo(java.io.File(destination.toString()))) {
        "无法原子移动诊断文件，保留源文件"
    }
}

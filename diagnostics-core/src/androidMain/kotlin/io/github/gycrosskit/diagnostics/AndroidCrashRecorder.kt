package io.github.gycrosskit.diagnostics

import android.content.Context


/** 使用 Android 不参与 Auto Backup 的私有目录；不安装任何采集器。 */
fun androidDiagnosticStore(context: Context, limits: DiagnosticLimits = DiagnosticLimits(), legacySources: List<LegacyDiagnosticSource> = emptyList()): DiagnosticStore =
    DiagnosticStore(java.io.File(context.noBackupFilesDir, "gycrosskit-diagnostics").absolutePath, limits, legacySources)

/** 仅记录 JVM 未捕获异常，不捕获 native signal/ANR。宿主明确创建并持有，close 撤回自己的 handler。 */
class AndroidCrashRecorder(
    store: DiagnosticStore,
    flush: (() -> Unit)? = null,
    onUncaughtException: ((Thread, Throwable) -> Unit)? = null,
    onReportStored: ((ReportKind) -> Unit)? = null,
) : java.io.Closeable {
    /** 保留原第三个位置参数的单参数落盘回调。 */
    constructor(store: DiagnosticStore, flush: (() -> Unit)?, onReportStored: (ReportKind) -> Unit) :
        this(store, flush, null, onReportStored)

    private val downstream: Thread.UncaughtExceptionHandler
    private val reports = ReportRecorder(store, onReportStored)
    private val handler: RecordingHandler
    init {
        synchronized(installationLock) {
            downstream = checkNotNull(Thread.getDefaultUncaughtExceptionHandler()) {
                "没有系统异常处理器，不能安装采集器"
            }.also { check(it !is RecordingHandler) { "进程已有 AndroidCrashRecorder" } }
            handler = RecordingHandler(store, reports, downstream, flush, onUncaughtException)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }
    override fun close() {
        reports.close()
        synchronized(installationLock) {
            if (Thread.getDefaultUncaughtExceptionHandler() === handler) Thread.setDefaultUncaughtExceptionHandler(downstream)
        }
    }
    private companion object { val installationLock = Any() }
    private class RecordingHandler(
        private val store: DiagnosticStore,
        private val reports: ReportRecorder,
        private val downstream: Thread.UncaughtExceptionHandler,
        private val flush: (() -> Unit)?,
        private val onUncaughtException: ((Thread, Throwable) -> Unit)?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                reports.record(ReportKind.CRASH, boundedThrowableReport(thread, error, maxBytes = store.limits.maxReportBytes))
                onUncaughtException?.invoke(thread, error)
            } catch (_: Throwable) {
                // 采集失败仍交给安装前的系统处理器结束进程。
            } finally {
                try { flush?.invoke() } catch (_: Throwable) { }
                downstream.uncaughtException(thread, error)
            }
        }
    }
}

/** 源码使用的 renameTo 在 Android/Linux 同文件系统上原子覆盖，避免 API 26 才有的 NIO。 */
internal actual fun platformMove(source: kotlinx.io.files.Path, destination: kotlinx.io.files.Path) {
    check(java.io.File(source.toString()).renameTo(java.io.File(destination.toString()))) {
        "无法原子移动诊断文件，保留源文件"
    }
}

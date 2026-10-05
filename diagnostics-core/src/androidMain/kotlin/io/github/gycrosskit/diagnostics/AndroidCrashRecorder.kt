package io.github.gycrosskit.diagnostics

import android.content.Context


/** 使用 noBackupFilesDir 私有目录，同步创建 Store；后台调用，不安装采集器，宿主拥有并负责 close。 */
fun androidDiagnosticStore(context: Context, limits: DiagnosticLimits = DiagnosticLimits(), legacySources: List<LegacyDiagnosticSource> = emptyList()): DiagnosticStore =
    DiagnosticStore(java.io.File(context.noBackupFilesDir, "gycrosskit-diagnostics").absolutePath, limits, legacySources)

/**
 * 仅记录 JVM 未捕获异常，不捕获 native signal/ANR；宿主明确准入后创建并持有进程唯一实例。
 * 回调在发生异常的线程执行，不携带/记录凭据；原文隐私由宿主决定。
 * @param flush 系统终止前同步刷盘，失败不阻止原 handler；须有界且不能等待主线程。
 * @param onUncaughtException 原始异常通知，宿主不得抛错或无限等待。
 * @param onReportStored 仅成功保存报告后通知，容量满/文件失败不通知。
 */
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
    /** 先撤销报告回调，仅仍是默认 handler 时恢复原 handler；不关闭共享 Store。 */
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

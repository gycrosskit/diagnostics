package io.github.gycrosskit.diagnostics

/** 采集生命周期与通知串行；递归锁允许通知中 close，通知不在 DiagnosticStore 文件锁内运行。 */
internal class ReportRecorder(
    private val store: DiagnosticStore,
    private val onReportStored: ((ReportKind) -> Unit)?,
) {
    private val gate = StoreLock(recursive = true)
    private var closed = false

    fun record(kind: ReportKind, text: String): Boolean = gate.locked {
        if (closed) return@locked false
        // 文件失败或用户通知异常均不得越过系统回调 ABI，也不影响后续事件。
        try {
            if (!store.recordReport(kind, text)) return@locked false
        } catch (_: Throwable) {
            return@locked false
        }
        try { onReportStored?.invoke(kind) } catch (_: Throwable) { }
        true
    }

    fun close() = gate.locked { closed = true }
}

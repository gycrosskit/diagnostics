package consumer
import android.content.Context
import io.github.gycrosskit.diagnostics.*
fun androidStore(context: Context) = androidDiagnosticStore(context)
fun androidRecorder(store: DiagnosticStore) = AndroidCrashRecorder(store)

fun androidAnrStore(context: Context) = AnrReportStore(context)
fun androidAnrMonitor(context: Context, store: AnrReportStore) = AndroidAnrMonitor(context, store) { level, tag, message ->
    // 宿主在此接已有日志；Debug/QA 安装条件留在宿主。
    android.util.Log.println(if (level == AnrLogLevel.INFO) android.util.Log.INFO else android.util.Log.WARN, tag, message)
}
fun androidAnrSummary(store: AnrReportStore): List<AnrReportSummary> = store.summaries()
fun androidFileAnrStore(directory: java.io.File) = AnrReportStore(directory)

// 编译校验新增公开入口；start/close 与报告上传留给宿主显式控制。
fun androidNativeCrashCollector(context: Context, store: DiagnosticStore) =
    AndroidNativeCrashCollector(context, store)

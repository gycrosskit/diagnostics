package consumer

import android.content.Context
import io.github.gycrosskit.diagnostics.AndroidNativeCrashCollector
import io.github.gycrosskit.diagnostics.DiagnosticStore

// 编译校验公开入口；start/close 与上传由宿主显式控制。
fun androidNativeCrashCollector(context: Context, store: DiagnosticStore) =
    AndroidNativeCrashCollector(context, store)

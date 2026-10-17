package consumer

import android.content.Context
import io.github.gycrosskit.diagnostics.bugly.BuglyDiagnosticSdkBridge
import io.github.gycrosskit.diagnostics.umeng.UmengDiagnosticSdkBridge
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkBridge

// 只验证正式远程 API，不创建真实客户端或启动供应商 SDK。
fun buglyCrashFactory(context: Context): () -> DiagnosticSdkBridge = { BuglyDiagnosticSdkBridge(context) }
fun umengFactory(context: Context): () -> DiagnosticSdkBridge = { UmengDiagnosticSdkBridge(context) }

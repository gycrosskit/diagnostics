package consumer

import android.content.Context
import io.github.gycrosskit.diagnostics.sdk.installedAndroidDiagnosticSdkBridge

// 仅编译公共入口；真实应用在持久化隐私授权后才调用。
fun installedDiagnosticFactory(context: Context) = { installedAndroidDiagnosticSdkBridge(context) }

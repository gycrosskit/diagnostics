package io.github.gycrosskit.diagnostics.umeng

import android.content.Context
import io.github.gycrosskit.diagnostics.sdk.AndroidDiagnosticSdkFactory
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkBridge

/** 标准 SPI 无参入口；SDK 仍由 Runtime 在隐私授权后初始化。 */
class UmengAndroidDiagnosticSdkFactory : AndroidDiagnosticSdkFactory {
    override fun create(context: Context): DiagnosticSdkBridge = UmengDiagnosticSdkBridge(context)
}

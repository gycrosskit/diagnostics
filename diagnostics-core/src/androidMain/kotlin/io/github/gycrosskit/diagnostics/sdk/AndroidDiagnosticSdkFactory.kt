package io.github.gycrosskit.diagnostics.sdk

import android.content.Context
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/** 适配组件通过标准 SPI 注册无参实现；构造与 create 均不能提前初始化厂商 SDK。 */
interface AndroidDiagnosticSdkFactory {
    fun create(context: Context): DiagnosticSdkBridge
}

/** 仅在 Runtime 已通过隐私准入后调用；进程只允许安装一个远程适配组件。 */
fun installedAndroidDiagnosticSdkBridge(context: Context): DiagnosticSdkBridge =
    installedAndroidDiagnosticSdkFactory(context.classLoader).create(context.applicationContext)

internal fun installedAndroidDiagnosticSdkFactory(classLoader: ClassLoader): AndroidDiagnosticSdkFactory {
    try {
        val factories = ServiceLoader.load(AndroidDiagnosticSdkFactory::class.java, classLoader).iterator()
        check(factories.hasNext()) { "未安装诊断 SDK 适配组件" }
        val factory = factories.next()
        check(!factories.hasNext()) { "只能安装一个诊断 SDK 适配组件" }
        return factory
    } catch (_: ServiceConfigurationError) {
        // SPI 原异常可能包含实现类、路径或厂商构造参数，不向宿主暴露。
        error("诊断 SDK 适配组件注册无效")
    }
}

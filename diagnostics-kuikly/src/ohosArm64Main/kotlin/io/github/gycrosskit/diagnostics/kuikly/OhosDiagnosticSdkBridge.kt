@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.gycrosskit.diagnostics.kuikly

import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkBridge
import io.github.gycrosskit.diagnostics.sdk.DiagnosticSdkConfiguration
import com.tencent.kuikly.compose.coroutines.internal.KuiklyContextScheduler
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 业务进程复用 SDK；模块栈只在 Renderer 线程读写，跨线程仅发布当前页 ID。 */
object OhosDiagnosticSdkBridge : DiagnosticSdkBridge {
    private val modules = mutableListOf<DiagnosticSdkModule>()
    private val activePagerId = MutableStateFlow("")
    private val pendingLogs = MutableStateFlow(0)

    fun attach(module: DiagnosticSdkModule) {
        // 2.29.1 的 willInit 先于 injectVar；预绑定公开页 ID，才能在业务组合前读取私有配置。
        module.pagerId = BridgeManager.currentPageId
        check(module.pagerId.isNotBlank()) { "鸿蒙诊断缺少当前页面" }
        modules.remove(module)
        modules.add(module)
        activePagerId.value = module.pagerId
    }

    fun detach(module: DiagnosticSdkModule) {
        modules.remove(module)
        activePagerId.value = modules.lastOrNull()?.pagerId.orEmpty()
        module.dispose()
    }

    // shortcut: Kuikly 2.29.1 的 Renderer scheduler 尚为 internal；升级需复核，公开同等 API 后移除此抑制。
    private fun dispatch(unavailable: () -> Unit = {}, block: (DiagnosticSdkModule) -> Unit) {
        val target = activePagerId.value
        if (target.isBlank()) { unavailable(); return }
        KuiklyContextScheduler.runOnKuiklyThread(target) { cancel ->
            if (target != activePagerId.value) { dispatch(unavailable, block); return@runOnKuiklyThread }
            if (cancel) { unavailable(); return@runOnKuiklyThread }
            val module = modules.lastOrNull()
            if (module == null) unavailable() else block(module)
        }
    }

    override suspend fun initialize(configuration: DiagnosticSdkConfiguration, deviceId: String, versionName: String, buildNumber: String) {
        withTimeout(20_000L) {
            while (true) {
                activePagerId.first { it.isNotBlank() }
                try {
                    suspendCancellableCoroutine { continuation ->
                        dispatch(unavailable = {
                            if (continuation.isActive) continuation.resumeWithException(DiagnosticPageUnavailable())
                        }) { module ->
                            if (continuation.isActive) module.initialize(configuration, deviceId, versionName, buildNumber, continuation)
                        }
                    }
                    return@withTimeout
                } catch (_: DiagnosticPageUnavailable) {
                    // 仅重接页面 transport；ArkTS 继续等待进程内同一 SDK Promise，不重新初始化供应商。
                }
            }
        }
    }

    override fun writeLog(level: String, tag: String, message: String) {
        // Renderer 忙时最多挂起 64 份远程副本；完整本地日志已经先落盘。
        while (true) {
            val count = pendingLogs.value
            if (count >= 64) return
            if (pendingLogs.compareAndSet(count, count + 1)) break
        }
        try {
            dispatch(unavailable = { pendingLogs.update { it - 1 } }) { module ->
                try { module.writeLog(level, tag, message) } catch (_: Exception) { }
                finally { pendingLogs.update { it - 1 } }
            }
        } catch (_: Exception) { pendingLogs.update { it - 1 } }
    }
}

/** 专用 Module 不持有作用域；取消与销毁在所属 Renderer 清除原生回执。 */
class DiagnosticSdkModule : Module() {
    private class Request(val continuation: CancellableContinuation<Unit>) { var callback: CallbackRef? = null }
    private val requests = mutableSetOf<Request>()
    private var disposed = false
    override fun moduleName() = NAME

    fun configuration(): DiagnosticSdkConfiguration = runCatching {
        val value = JSONObject(syncToNativeMethod("configuration", data = null, callbackFn = null))
        DiagnosticSdkConfiguration(
            provider = value.optString("provider"),
            appId = value.optString("appId"),
            appKey = value.optString("appKey"),
            channel = value.optString("channel"),
            crashes = value.optBoolean("crashes", true),
            performance = value.optBoolean("performance", false),
            remoteLogs = value.optBoolean("remoteLogs", true),
            brandId = value.optString("brandId"),
            environment = value.optString("environment"),
        )
    }.getOrElse { DiagnosticSdkConfiguration() }

    fun initialize(configuration: DiagnosticSdkConfiguration, deviceId: String, versionName: String,
        buildNumber: String, continuation: CancellableContinuation<Unit>) {
        if (disposed) { continuation.resumeWithException(DiagnosticPageUnavailable()); return }
        val request = Request(continuation)
        requests.add(request)
        val ownerPagerId = pagerId
        continuation.invokeOnCancellation {
            KuiklyContextScheduler.runOnKuiklyThread(ownerPagerId) { cancel ->
                if (!cancel) remove(request)
            }
        }
        if (!continuation.isActive) { remove(request); return }
        try {
            request.callback = toNative(false, "initialize", JSONObject().apply {
                put("privacyGranted", true)
                put("configuration", JSONObject().apply {
                    put("provider", configuration.provider)
                    put("appId", configuration.appId)
                    put("appKey", configuration.appKey)
                    put("channel", configuration.channel)
                    put("crashes", configuration.crashes)
                    put("remoteLogs", configuration.remoteLogs)
                    put("performance", configuration.performance)
                    put("brandId", configuration.brandId)
                    put("environment", configuration.environment)
                })
                put("provider", configuration.provider)
                put("brandId", configuration.brandId)
                put("environment", configuration.environment)
                put("deviceId", deviceId)
                put("versionName", versionName)
                put("buildNumber", buildNumber)
            }.toString(), { response ->
                remove(request)
                if (continuation.isActive) {
                    if (response?.optString("status") == "ready") continuation.resume(Unit)
                    else continuation.resumeWithException(IllegalStateException("鸿蒙诊断 SDK 初始化不可用"))
                }
            }, false).callbackRef
            if (!continuation.isActive) remove(request)
        } catch (_: Exception) {
            remove(request)
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("鸿蒙诊断原生调用失败"))
        }
    }

    private fun remove(request: Request) {
        requests.remove(request)
        request.callback?.let(::removeCallback)
        request.callback = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        requests.toList().forEach { request ->
            remove(request)
            if (request.continuation.isActive) request.continuation.resumeWithException(DiagnosticPageUnavailable())
        }
    }

    fun writeLog(level: String, tag: String, message: String) {
        if (disposed) return
        asyncToNativeMethod("writeLog", JSONObject().apply {
            put("level", level); put("tag", tag); put("message", message)
        }, null)
    }

    companion object { const val NAME = "GycDiagnosticSdk" }
}

/** 页面消失不等于调用者取消或 SDK 初始化失败。 */
private class DiagnosticPageUnavailable : Exception()

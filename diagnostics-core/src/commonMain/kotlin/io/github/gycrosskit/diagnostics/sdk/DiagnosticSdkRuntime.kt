package io.github.gycrosskit.diagnostics.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DiagnosticSdkState { DISABLED, MISSING_CONFIGURATION, WAITING_FOR_PRIVACY, INITIALIZING, INITIALIZED, FAILED }

/** 进程内只选一个 SDK；失败保留本地 diagnostics，不自动循环重试。 */
class DiagnosticSdkRuntime(
    private val configuration: DiagnosticSdkConfiguration,
    brandId: String,
    environment: String,
    private val versionName: String,
    private val buildNumber: String,
    private val bridgeFactory: () -> DiagnosticSdkBridge,
) {
    private val phase = MutableStateFlow(when {
        configuration.provider == DiagnosticSdkProvider.NONE || !(configuration.crashes || configuration.remoteLogs || configuration.performance) -> DiagnosticSdkState.DISABLED
        !configuration.matches(brandId, environment) -> DiagnosticSdkState.MISSING_CONFIGURATION
        else -> DiagnosticSdkState.WAITING_FOR_PRIVACY
    })
    private val initialization = Mutex()
    private val bridge = MutableStateFlow<DiagnosticSdkBridge?>(null)
    private val logs = Channel<SdkLog>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val state: DiagnosticSdkState get() = phase.value

    /** 宿主等待持久化隐私授权后返回稳定安装 ID；null 表示仍未获授权。 */
    suspend fun initialize(authorizedDeviceId: suspend () -> String?) = initialization.withLock {
        if (state != DiagnosticSdkState.WAITING_FOR_PRIVACY) return@withLock
        try {
            val deviceId = authorizedDeviceId()?.takeIf { it.isNotBlank() } ?: return@withLock
            if (!phase.compareAndSet(DiagnosticSdkState.WAITING_FOR_PRIVACY, DiagnosticSdkState.INITIALIZING)) return@withLock
            val sdk = bridgeFactory()
            sdk.initialize(configuration, deviceId, versionName, buildNumber)
            bridge.value = sdk
            phase.value = DiagnosticSdkState.INITIALIZED
        } catch (cancelled: CancellationException) {
            phase.value = DiagnosticSdkState.FAILED
            throw cancelled
        } catch (_: Throwable) {
            // 厂商异常可能包含配置，也不能经同一日志出口递归调用 SDK。
            phase.value = DiagnosticSdkState.FAILED
        }
    }

    fun record(level: String, tag: String, message: String) {
        if (state != DiagnosticSdkState.INITIALIZED || !configuration.remoteLogs || tag == "KtorHttp") return
        logs.trySend(SdkLog(level, tag.take(80), safeDiagnosticSdkLog(message)))
    }

    /** 由进程级 Main 协程消费；不阻塞宿主原有本地落盘。 */
    suspend fun consumeLogs() {
        for (log in logs) runCatching { bridge.value?.writeLog(log.level, log.tag, log.message) }
    }

    private data class SdkLog(val level: String, val tag: String, val message: String)

    companion object {
        private val current = MutableStateFlow<DiagnosticSdkRuntime?>(null)

        /** 在进程作用域调用一次；页面销毁不能取消 SDK 初始化或日志消费者。 */
        suspend fun configure(
            configuration: DiagnosticSdkConfiguration,
            brandId: String,
            environment: String,
            versionName: String,
            buildNumber: String,
            bridgeFactory: () -> DiagnosticSdkBridge,
            authorizedDeviceId: suspend () -> String?,
        ) {
            val runtime = DiagnosticSdkRuntime(configuration, brandId, environment, versionName, buildNumber, bridgeFactory)
            if (!current.compareAndSet(null, runtime) || runtime.state != DiagnosticSdkState.WAITING_FOR_PRIVACY) return
            withContext(Dispatchers.Main) {
                runtime.initialize(authorizedDeviceId)
                if (runtime.state == DiagnosticSdkState.INITIALIZED && configuration.remoteLogs) runtime.consumeLogs()
            }
        }

        fun writeLog(level: String, tag: String, message: String) {
            current.value?.record(level, tag, message)
        }
    }
}

private val credentialField = Regex("""(?i)(authorization|access[_-]?token|refresh[_-]?token|token|password|app[_-]?key)(["']?\s*[:=]\s*|\s+)""")
private val authorizationScheme = Regex("""(?i)(Bearer|Basic)\s+[^\s,"']+""")

/** SDK 单条日志有长度限制；保留完整 UTF-8 字符，且不转发已知凭据字段。 */
internal fun safeDiagnosticSdkLog(message: String): String {
    val source = authorizationScheme.replace(boundedSdkLog(message)) {
        "${it.groupValues[1]} [redacted]"
    }
    val filtered = StringBuilder()
    var position = 0
    while (position < source.length) {
        val field = credentialField.find(source, position) ?: break
        filtered.append(source, position, field.range.first)
        filtered.append(field.groupValues[1]).append("=[redacted]")
        position = credentialValueEnd(source, field.range.last + 1)
    }
    filtered.append(source, position, source.length)
    // 短值替换成标记可能膨胀，最终交给 SDK 的字符串也必须满足容量限制。
    return boundedSdkLog(filtered.toString())
}

// 直接扫描转义/未闭合引号，避免长凭据触发正则递归，同时覆盖日志截断在值中间的情况。
private fun credentialValueEnd(source: String, start: Int): Int {
    var end = start
    val quote = source.getOrNull(start)
    if (quote == '"' || quote == '\'') {
        end++
        while (end < source.length) {
            val char = source[end++]
            if (char == quote) break
            if (char == '\\' && end < source.length) end++
        }
    } else {
        while (end < source.length && !source[end].isWhitespace() && source[end] !in ",}\"'") end++
    }
    return end
}

private fun boundedSdkLog(message: String): String {
    val bounded = StringBuilder()
    var bytes = 0
    var index = 0
    while (index < message.length) {
        val char = message[index]
        val surrogatePair = char.isHighSurrogate() && index + 1 < message.length && message[index + 1].isLowSurrogate()
        val size = when { surrogatePair -> 4; char.code < 0x80 -> 1; char.code < 0x800 -> 2; else -> 3 }
        if (bytes + size > 4096) break
        bounded.append(char)
        if (surrogatePair) bounded.append(message[++index])
        bytes += size
        index++
    }
    return bounded.toString()
}

package io.github.gycrosskit.diagnostics.ktor

import io.github.gycrosskit.diagnostics.NetworkCall
import io.github.gycrosskit.diagnostics.NetworkCapture
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.isSaved
import io.ktor.client.request.HttpSendPipeline
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

/** 安装 [DiagnosticsKtor] 时指定共享采集策略，不创建 HttpClient、协程或日志队列。 */
class DiagnosticsKtorConfig {
    /**
     * 必填采集接线；context/include/redactBody/onLog 在当前请求执行的协程线程同步调用。
     * 同一 HttpClient 可并发发送，宿主回调须线程安全、不阻塞，并返回不可变 context。
     * 关闭 HttpClient 后由宿主按自身生命周期释放 capture/store 与 StateFlow 观察 scope。
     */
    lateinit var capture: NetworkCapture<*>
}

/**
 * 每次真实发送独立关联，复用 [NetworkCapture] 的准入、脱敏与有界日志环。
 * 请求只观察 Ktor 自带的 TextContent/ByteArrayContent，不调用自定义或流式 content。
 * 响应只观察 Ktor 已保存的 UTF-8 文本，按实际字节数限额；streaming execute { } 仅记录响应头。
 * 本插件不 save、替换或消费网络流，不额外等待响应完成；耗时截至响应头。
 * 网络异常与 Coroutine 取消原样传播；日志回调失败由 [NetworkCapture] 隔离。
 */
@OptIn(InternalAPI::class)
val DiagnosticsKtor = createClientPlugin("DiagnosticsKtor", ::DiagnosticsKtorConfig) {
    val capture = pluginConfig.capture
    val callKey = AttributeKey<NetworkCall<*>>("DiagnosticsKtorCall")
    val headersPhase = PipelinePhase("DiagnosticsKtorHeaders")

    client.sendPipeline.intercept(HttpSendPipeline.Monitoring) { content ->
        // Monitoring 位于 ContentNegotiation 序列化之后，重试和重定向每次都会经过。
        val call = capture.begin(context.method.value, context.url.buildString(),
            context.headers.entries().associate { it.key to it.value }, requestText(content, capture))
        context.attributes.remove(callKey)
        if (call != null) context.attributes.put(callKey, call)
        try {
            proceed()
        } catch (cause: Throwable) {
            call?.failure(cause)
            throw cause
        }
    }

    // Receive 会先执行 Ktor 的 SaveBody，必须在其之前记录头部耗时和状态。
    client.sendPipeline.insertPhaseBefore(HttpSendPipeline.Receive, headersPhase)
    client.sendPipeline.intercept(headersPhase) { subject ->
        val response = (subject as HttpClientCall).response
        response.call.attributes.getOrNull(callKey)?.response(response.status.value,
            response.headers.entries().associate { it.key to it.value })
    }

    onResponse { response ->
        val call = response.call.attributes.getOrNull(callKey) ?: return@onResponse
        val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (!capture.captureBody || !response.isSaved || (length != null && length !in 0..capture.maxBodyBytes.toLong()) ||
            response.headers[HttpHeaders.ContentEncoding] != null ||
            !isUtf8Text(response.headers[HttpHeaders.ContentType])) return@onResponse
        try {
            // SavedHttpResponse.rawContent 每次返回独立的内存 channel；绝不读取 live channel。
            val bytes = response.rawContent.readRemaining(capture.maxBodyBytes.toLong() + 1).readByteArray()
            if (bytes.size <= capture.maxBodyBytes) call.responseBody(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            // 无效 UTF-8 或正文观察失败只丢弃日志，不改变调用方的响应。
        }
    }
}

private fun requestText(content: Any, capture: NetworkCapture<*>): String? {
    if (!capture.captureBody || content !is OutgoingContent ||
        !isUtf8Text(content.contentType?.toString())) return null
    val length = content.contentLength ?: return null
    if (length !in 0..capture.maxBodyBytes.toLong()) return null
    return try {
        when (content) {
            is TextContent -> content.text
            // 只使用 Ktor 的 final 实现；抽象 ByteArrayContent.bytes() 可能是 one-shot。
            is ByteArrayContent -> content.bytes().takeIf { it.size <= capture.maxBodyBytes }
                ?.decodeToString(throwOnInvalidSequence = true)
            else -> null
        }
    } catch (_: Exception) { null }
}

private fun isUtf8Text(value: String?): Boolean {
    val type = try { value?.let(ContentType::parse) } catch (_: Exception) { null } ?: return false
    val charset = type.parameters.firstOrNull { it.name.equals("charset", ignoreCase = true) }?.value
    if (charset != null && !charset.equals("utf-8", ignoreCase = true)) return false
    return type.contentType.equals("text", ignoreCase = true) ||
        (type.contentType.equals("application", ignoreCase = true) &&
            (type.contentSubtype.equals("json", ignoreCase = true) ||
                type.contentSubtype.endsWith("+json", ignoreCase = true) ||
                type.contentSubtype.equals("xml", ignoreCase = true) ||
                type.contentSubtype.endsWith("+xml", ignoreCase = true) ||
                type.contentSubtype.equals("x-www-form-urlencoded", ignoreCase = true)))
}

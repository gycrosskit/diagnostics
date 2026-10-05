package io.github.gycrosskit.diagnostics.okhttp

import io.github.gycrosskit.diagnostics.NetworkCapture
import okhttp3.*
import okio.*
import java.nio.charset.StandardCharsets

/**
 * 通过 addInterceptor 安装，一个逻辑 Call 共用 requestId（OkHttp 内部重试/重定向仍属同一次 Call）。
 * 不预读响应或重放请求，正文仅在正常 writeTo/read 到完成时观察；提前 close/超额/非 UTF-8 文本不记录。
 * Duplex 正文不包装；one-shot 请求仍只写一次，保留原 contentLength/contentType/isOneShot。
 * 不安装本适配时 core 不会引入 OkHttp；Android/JVM 专用，KMP 三端使用 diagnostics-ktor。
 */
class NetworkDiagnosticsInterceptor(private val capture: NetworkCapture<*>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val call = capture.begin(request.method, request.url.toString(), request.headers.toMultimap())
            ?: return chain.proceed(request)
        val originalBody = request.body
        val observed = if (capture.captureBody && originalBody != null && !originalBody.isDuplex() && originalBody.contentType().isUtf8Text()) {
            request.newBuilder().method(request.method, object : RequestBody() {
                override fun contentType() = originalBody.contentType()
                override fun contentLength() = originalBody.contentLength()
                override fun isOneShot() = originalBody.isOneShot()
                override fun isDuplex() = originalBody.isDuplex()
                override fun writeTo(sink: BufferedSink) {
                    val sample = BodySample(capture.maxBodyBytes)
                    val tee = object : ForwardingSink(sink) {
                        override fun write(source: Buffer, byteCount: Long) {
                            sample.append(source, 0, byteCount)
                            super.write(source, byteCount)
                        }
                    }.buffer()
                    try {
                        originalBody.writeTo(tee)
                        if (tee.isOpen) tee.emit()
                        call.requestBody(sample.complete())
                    } finally { sample.clear() }
                }
            }).build()
        } else request
        val response = try { chain.proceed(observed) } catch (failure: Exception) {
            call.failure(failure)
            throw failure
        }
        call.response(response.code, response.headers.toMultimap())
        val body = response.body
        if (!capture.captureBody || body == null || !body.contentType().isUtf8Text()) return response
        // 只观察正常消费，不以 peekBody/request(n) 触发额外网络读取或等待流结束。
        val sample = BodySample(capture.maxBodyBytes)
        val source = object : ForwardingSource(body.source()) {
            private var completed = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                val before = sink.size
                return try {
                    val count = super.read(sink, byteCount)
                    if (count > 0) sample.append(sink, before, count)
                    if (count == -1L && !completed) {
                        completed = true
                        call.responseBody(sample.complete())
                        sample.clear()
                    }
                    count
                } catch (failure: Exception) {
                    if (!completed) { completed = true; call.failure(failure); sample.clear() }
                    throw failure
                }
            }
            override fun close() { try { super.close() } finally { sample.clear() } }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source(): BufferedSource = source
        }).build()
    }
}

private fun MediaType?.isUtf8Text(): Boolean {
    if (this == null || charset(StandardCharsets.UTF_8) != StandardCharsets.UTF_8) return false
    // SSE/NDJSON 是无限或逐条流，整段 JSON 脱敏不适用；只留下 metadata。
    return subtype != "event-stream" && subtype != "x-ndjson" &&
        (type == "text" || subtype == "json" || subtype.endsWith("+json"))
}

private class BodySample(private val limit: Int) {
    private val bytes = Buffer()
    private var overflow = false
    fun append(source: Buffer, offset: Long, count: Long) {
        if (overflow) return
        if (count > limit - bytes.size) { overflow = true; bytes.clear(); return }
        source.copyTo(bytes, offset, count)
    }
    fun complete(): String? = if (overflow) null else try {
        bytes.readByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (_: Exception) { null }
    fun clear() { bytes.clear() }
}

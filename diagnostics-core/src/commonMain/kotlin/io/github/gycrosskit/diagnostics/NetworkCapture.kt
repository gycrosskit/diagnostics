package io.github.gycrosskit.diagnostics

import kotlinx.serialization.json.*
import kotlin.time.TimeSource

/**
 * 可选网络采集接线，复用有界日志环，不创建队列或持有 HttpClient。
 * Body 默认关闭；开启后仍须提供 [redactBody]，返回 null 表示不记录。
 * URL 默认去掉 query、fragment 和 userinfo；路径可能含个人信息，由 [redactUrl] 继续处理。
 * Headers 默认按 [allowedHeaders] 放行并遮盖凭据；提供 [redactHeader] 时由宿主决定每个值。
 * [context] 应返回不可变环境值；[include] 用于排除上传日志等会递归采集的接口。
 * 回调异常只关闭相应采集，不改变网络执行。回调在网络调用线程执行，宿主不得阻塞。
 * @property maxBodyBytes 每段完整 UTF-8 文本上限（1..32 KiB）；超额整段丢弃，避免截断后无法脱敏。
 * @property captureBody 是否观察正文，适配器据此避免不必要的复制。
 * @property redactHeader 可选的 header 策略，收到原始名称与前四个值的合并文本。
 * 非 null 时替代默认放行/凭据遮盖，可返回原文；返回 null 或抛异常仅遮盖当前 header。
 * 输出仍最多 20 个 header，名称 128 字符、值 512 字符并清除换行；不修改 HTTP 数据。
 */
class NetworkCapture<C>(
    private val store: NetworkLogStore<C>,
    private val context: () -> C,
    val maxBodyBytes: Int = 4096,
    val captureBody: Boolean = false,
    private val redactBody: (String) -> String? = { null },
    private val redactUrl: (String) -> String = { it },
    private val allowedHeaders: Set<String> = setOf("content-type", "content-length", "accept"),
    private val include: (String) -> Boolean = { true },
    private val redactHeader: ((name: String, value: String) -> String?)? = null,
    private val onLog: (String) -> Unit = {},
) {
    /** 保留旧九参数位置调用；新增策略不改变已有 onLog 参数位置。 */
    constructor(
        store: NetworkLogStore<C>, context: () -> C, maxBodyBytes: Int, captureBody: Boolean,
        redactBody: (String) -> String?, redactUrl: (String) -> String,
        allowedHeaders: Set<String>, include: (String) -> Boolean, onLog: (String) -> Unit,
    ) : this(store, context, maxBodyBytes, captureBody, redactBody, redactUrl, allowedHeaders,
        include, redactHeader = null, onLog = onLog)

    private val allowed = allowedHeaders.map(String::lowercase).toSet()
    init { require(maxBodyBytes in 1..32 * 1024) }

    /** 适配器每次发送调用一次；准入失败返回 null，不保存原始 URL 或请求正文。 */
    fun begin(method: String, url: String, headers: Map<String, List<String>>, body: String? = null): NetworkCall<C>? {
        return try {
            if (!include(url)) return null
            val safeUrl = redactUrl(networkUrlWithoutSecrets(url)).take(2048).singleLine()
            NetworkCall(this, context(), store.allocateRequestId(), method.take(32).singleLine(), safeUrl).also {
                it.request(headers, body)
            }
        } catch (_: Exception) { null }
    }

    internal fun emit(context: C, message: String) {
        // 两个出口拿到完全相同的脱敏有界文本，不让 Logcat 绕过日志环额度。
        val bounded = message.take(store.maxMessageCharacters)
        store.record(message, context)
        try { onLog(bounded) } catch (_: Exception) { /* 日志出口不能改变网络结果。 */ }
    }

    internal fun headers(headers: Map<String, List<String>>): String = headers.entries.take(20).joinToString("\n") { (name, values) ->
        val key = name.lowercase()
        val policy = redactHeader
        val value = if (policy != null) {
            try { policy(name, values.take(4).joinToString(", ")) } catch (_: Exception) { null }
        } else if (key in allowed && !isCredentialHeader(key)) {
            values.take(4).joinToString(", ")
        } else null
        "${name.take(128).singleLine()}: ${(value ?: "<redacted>").take(512).singleLine()}"
    }

    internal fun body(body: String?): String {
        if (!captureBody || body == null || body.length > maxBodyBytes || body.encodeToByteArray().size > maxBodyBytes) return ""
        val safe = try { redactBody(body) } catch (_: Exception) { null } ?: return ""
        if (safe.length > maxBodyBytes || safe.encodeToByteArray().size > maxBodyBytes) return ""
        return "\nBODY:\n$safe"
    }
}

/**
 * 单次发送的关联值；不持有原始请求，重试应重新 begin。耗时使用单调时钟，表示到响应头的时间。
 * Body 可能在消费完成后作为同 requestId 的补充记录出现；关闭/未消费/超额正文不输出。
 */
class NetworkCall<C> internal constructor(
    private val capture: NetworkCapture<C>, private val context: C, val requestId: Long,
    private val method: String, private val url: String,
) {
    private val started = TimeSource.Monotonic.markNow()
    private val lock = StoreLock()
    private var responseStatus: Int? = null
    private var responseDuration: Long? = null

    internal fun request(headers: Map<String, List<String>>, body: String?) {
        capture.emit(context, "REQUEST: $url\n[$requestId] $method\n${capture.headers(headers)}${capture.body(body)}")
    }
    /** 请求正文完整写入后补充同一请求的脱敏日志，不再次读取请求体。 */
    fun requestBody(body: String?) {
        val safe = capture.body(body)
        if (safe.isNotEmpty()) capture.emit(context, "REQUEST: $url\n[$requestId] $method$safe")
    }
    /** 在收到响应头时记录，不等待响应正文；可直接传入适配器已完整观察的小正文。 */
    fun response(statusCode: Int, headers: Map<String, List<String>>, body: String? = null) {
        val duration = started.elapsedNow().inWholeMilliseconds
        lock.locked {
            responseStatus = statusCode
            responseDuration = duration
        }
        capture.emit(context, "RESPONSE: $statusCode\nFROM: $url\n[$requestId] ${duration}ms\n${capture.headers(headers)}${capture.body(body)}")
    }
    /** 响应正文消费完毕后补充；不会把 body 消费耗时冒充响应头耗时。 */
    fun responseBody(body: String?) {
        val (status, duration) = lock.locked { responseStatus to responseDuration }
        if (status == null) return
        val safe = capture.body(body)
        if (safe.isNotEmpty()) capture.emit(context, "RESPONSE: $status\nFROM: $url\n[$requestId] ${duration}ms$safe")
    }
    /** 只保存异常类型；异常 message/stack 经常包含原 URL、凭据或服务器原文。 */
    fun failure(cause: Throwable) {
        val duration = started.elapsedNow().inWholeMilliseconds
        capture.emit(context, "[$requestId] ${duration}ms $method failed with exception ${cause::class.simpleName.orEmpty().take(120)}\nFROM: $url")
    }
}

/** 完整 JSON 文本脱敏；不能解析时丢弃，不输出可能包含未闭合敏感字段的文本片段。 */
fun redactNetworkJson(body: String, sensitiveFields: Set<String> = emptySet()): String? {
    // 当前 parser 的深层数组仍使用调用栈，须先限深。保留根 depth=0 的约定：最多 65 层容器，
    // 第 65 层中的非空子值仍由下方 depth<=64 拒绝，空容器的既有行为不变。
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in body) {
        if (quoted) {
            if (escaped) escaped = false
            else when (char) {
                '\\' -> escaped = true
                '"' -> quoted = false
            }
        } else when (char) {
            '"' -> quoted = true
            '[', '{' -> if (++depth > 65) return null
            ']', '}' -> depth--
        }
    }
    val denied = NETWORK_SENSITIVE_FIELDS + sensitiveFields.map(String::lowercase)
    fun redact(value: JsonElement, depth: Int): JsonElement {
        require(depth <= 64)
        return when (value) {
            is JsonObject -> JsonObject(value.mapValues { (key, item) -> if (key.lowercase() in denied) JsonPrimitive("<redacted>") else redact(item, depth + 1) })
            is JsonArray -> JsonArray(value.map { redact(it, depth + 1) })
            else -> value
        }
    }
    return try { redact(Json.parseToJsonElement(body), 0).toString() } catch (_: Exception) { null }
}

private val NETWORK_SENSITIVE_FIELDS = setOf("authorization", "cookie", "password", "passwd", "token", "access_token", "refresh_token", "accesstoken", "refreshtoken", "secret", "phone", "mobile", "telephone", "sms", "smscode", "verifycode", "verificationcode", "code", "idcard", "openid", "unionid")
private fun isCredentialHeader(name: String): Boolean = name in setOf("authorization", "proxy-authorization", "cookie", "set-cookie") || name.contains("token") || name.contains("key") || name.contains("secret")
private fun String.singleLine(): String = replace('\r', ' ').replace('\n', ' ')
private fun networkUrlWithoutSecrets(url: String): String {
    val withoutQuery = url.substringBefore('?').substringBefore('#')
    val scheme = withoutQuery.indexOf("://")
    if (scheme < 0) return "<invalid-url>"
    val authorityEnd = withoutQuery.indexOf('/', scheme + 3).let { if (it < 0) withoutQuery.length else it }
    val authority = withoutQuery.substring(scheme + 3, authorityEnd).substringAfterLast('@')
    return withoutQuery.substring(0, scheme + 3) + authority + withoutQuery.substring(authorityEnd)
}

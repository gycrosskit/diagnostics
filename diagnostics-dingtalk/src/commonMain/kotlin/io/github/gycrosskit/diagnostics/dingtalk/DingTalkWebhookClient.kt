package io.github.gycrosskit.diagnostics.dingtalk

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.encodeBase64
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 单次发送结果；没有自动重试，CLOSED/NOT_CONFIGURED 不执行请求。 */
enum class DingTalkSendStatus { SUCCESS, NOT_CONFIGURED, CLOSED, HTTP_FAILURE, REJECTED, INVALID_RESPONSE, TRANSPORT_FAILURE }
/**
 * @property status 单次协议/传输状态。
 * @property httpStatus 收到的 HTTP 状态码，未收到响应为 null。
 * @property errorCode 合法响应中的 errcode，无法解析为 null。
 * @property message 保留 API 字段；本实现不回显服务端/异常正文，避免泄露签名和消息原文。
 * @property success 是否得到 HTTP 成功响应且 errcode=0，不代表业务接收人已阅读。
 */
data class DingTalkSendResult(val status: DingTalkSendStatus, val httpStatus: Int? = null, val errorCode: Int? = null,
    val message: String = "") { val success: Boolean get() = status == DingTalkSendStatus.SUCCESS }

/**
 * 单次 HTTPS 传输，可跨协程发送；不安装 Logger、不调度、不重试。
 * 凭据与通知许可由宿主注入，异常/响应正文不回显。宿主停止发送后 close。
 * @param webhook 官方 HTTPS robot/send 未签名 URL，含 access_token，勿记录完整 URL。
 * @param secret HMAC 密钥，勿记录。
 * @param client 可借用宿主 engine，close 不关闭借用者；未传时本实例负责释放 engine。
 * @param currentTimeMillis Unix 毫秒时钟，签名时间必须非负。
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
class DingTalkWebhookClient(
    private val webhook: String,
    private val secret: String,
    client: HttpClient? = null,
    private val currentTimeMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val ownsClient = client == null
    private val engineOwner = client ?: defaultDingTalkHttpClient()
    private val client = HttpClient(engineOwner.engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 5000; connectTimeoutMillis = 5000; socketTimeoutMillis = 5000 }
    }
    private val closed = AtomicInt(0)
    /** 仅检查两份配置非空白，不证明 URL 合法或服务端权限有效。 */
    val configured: Boolean get() = webhook.isNotBlank() && secret.isNotBlank()
    /**
     * 发送宿主已脱敏的 markdown，三类超时均为 5 秒；拒绝重定向，响应最多读取 16 KiB+1 字节。
     * 取消向调用方传播，其他传输失败返回不含凭据的状态；不会重试或改变宿主通知策略。
     */
    suspend fun send(title: String, markdown: String): DingTalkSendResult {
        if (closed.load() != 0) return DingTalkSendResult(DingTalkSendStatus.CLOSED)
        if (!configured) return DingTalkSendResult(DingTalkSendStatus.NOT_CONFIGURED)
        return try {
            client.preparePost(signedDingTalkWebhook(webhook, secret, currentTimeMillis())) {
                contentType(ContentType.Application.Json)
                setBody(encodeDingTalkMarkdownPayload(title, markdown))
            }.execute { response ->
            val status = response.status.value
            if (status !in 200..299) return@execute DingTalkSendResult(DingTalkSendStatus.HTTP_FAILURE, status)
            val bytes = response.bodyAsChannel().readRemaining(16 * 1024L + 1).readByteArray()
            if (bytes.size > 16 * 1024) return@execute DingTalkSendResult(DingTalkSendStatus.INVALID_RESPONSE, status)
            decodeDingTalkWebhookResponse(bytes.decodeToString(), status)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // 异常可能含签名 URL，不把原异常文本放进结果或日志。
            DingTalkSendResult(DingTalkSendStatus.TRANSPORT_FAILURE)
        }
    }
    /** 幂等关闭本实例请求客户端；只释放本实例创建的 engine，之后 send 返回 CLOSED。 */
    fun close() { if (closed.compareAndSet(0, 1)) { client.close(); if (ownsClient) engineOwner.close() } }
}

/** 纯签名函数：timestampMillis 为非负 Unix 毫秒；拒绝 userinfo/非官方端点/旧签名/碎片，结果含密钥派生签名勿记录。 */
fun signedDingTalkWebhook(webhook: String, secret: String, timestampMillis: Long): String {
    require(secret.isNotBlank() && timestampMillis >= 0) { "Invalid signing configuration" }
    val url = Url(webhook)
    require(url.protocol == URLProtocol.HTTPS && url.host == "oapi.dingtalk.com" && url.port == 443 &&
        url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.encodedPath == "/robot/send" && url.fragment.isEmpty()) {
        "Invalid official HTTPS endpoint"
    }
    require(!url.parameters.contains("timestamp") && !url.parameters.contains("sign")) { "Endpoint already signed" }
    require(!url.parameters["access_token"].isNullOrBlank()) { "Missing access token" }
    val signature = hmacSha256(secret.encodeToByteArray(), "$timestampMillis\n$secret".encodeToByteArray()).encodeBase64()
    return URLBuilder(url).apply {
        parameters.append("timestamp", timestampMillis.toString())
        parameters.append("sign", signature)
    }.buildString()
}
/** JSON 编码标题和 markdown 原文，保留转义/换行；不执行隐私过滤或请求。 */
fun encodeDingTalkMarkdownPayload(title: String, markdown: String): String = buildJsonObject {
    put("msgtype", "markdown")
    putJsonObject("markdown") { put("title", title); put("text", markdown) }
}.toString()
/** 解析 errcode，非法 JSON/类型返回 INVALID_RESPONSE；不回显正文，httpStatus 由调用方提供。 */
fun decodeDingTalkWebhookResponse(body: String, httpStatus: Int = 200): DingTalkSendResult {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val code = (root?.get("errcode") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?: return DingTalkSendResult(DingTalkSendStatus.INVALID_RESPONSE, httpStatus)
    // 服务响应 errMsg 也可能回显输入；只输出协议错误码，不转发服务端正文。
    return DingTalkSendResult(if (code == 0) DingTalkSendStatus.SUCCESS else DingTalkSendStatus.REJECTED, httpStatus, code)
}
internal expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
internal expect fun defaultDingTalkHttpClient(): HttpClient

package io.github.gycrosskit.diagnostics.dingtalk

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.encodeBase64
import io.ktor.utils.io.readRemaining
import kotlinx.io.readString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

enum class DingTalkSendStatus { SUCCESS, NOT_CONFIGURED, CLOSED, HTTP_FAILURE, REJECTED, INVALID_RESPONSE, TRANSPORT_FAILURE }
data class DingTalkSendResult(val status: DingTalkSendStatus, val httpStatus: Int? = null, val errorCode: Int? = null,
    val message: String = "") { val success: Boolean get() = status == DingTalkSendStatus.SUCCESS }

/** 一次独立 HTTPS 传输，不安装 Logger、不调度、不重试。凭据只能通过宿主注入。 */
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
    val configured: Boolean get() = webhook.isNotBlank() && secret.isNotBlank()
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
            val body = response.bodyAsChannel().readRemaining(16 * 1024L + 1).readString()
            if (body.length > 16 * 1024) return@execute DingTalkSendResult(DingTalkSendStatus.INVALID_RESPONSE, status)
            decodeDingTalkWebhookResponse(body, status)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // 异常可能含签名 URL，不把原异常文本放进结果或日志。
            DingTalkSendResult(DingTalkSendStatus.TRANSPORT_FAILURE)
        }
    }
    fun close() { if (closed.compareAndSet(0, 1)) { client.close(); if (ownsClient) engineOwner.close() } }
}

/** 拒绝重定向、userinfo、非官方 host/端口/路径、旧签名和碎片。 */
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
fun encodeDingTalkMarkdownPayload(title: String, markdown: String): String = buildJsonObject {
    put("msgtype", "markdown")
    putJsonObject("markdown") { put("title", title); put("text", markdown) }
}.toString()
fun decodeDingTalkWebhookResponse(body: String, httpStatus: Int = 200): DingTalkSendResult {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val code = (root?.get("errcode") as? JsonPrimitive)?.intOrNull
        ?: return DingTalkSendResult(DingTalkSendStatus.INVALID_RESPONSE, httpStatus)
    // 服务响应 errMsg 也可能回显输入；只输出协议错误码，不转发服务端正文。
    return DingTalkSendResult(if (code == 0) DingTalkSendStatus.SUCCESS else DingTalkSendStatus.REJECTED, httpStatus, code)
}
internal expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
internal expect fun defaultDingTalkHttpClient(): HttpClient
